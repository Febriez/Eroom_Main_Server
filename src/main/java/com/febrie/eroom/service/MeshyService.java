package com.febrie.eroom.service;

import com.febrie.eroom.config.ApiKeyConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.*;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class MeshyService {

    private static final Logger log = LoggerFactory.getLogger(MeshyService.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String MESHY_API_URL = "https://api.meshy.ai/v2/text-to-3d";
    private static final String MESHY_API_STATUS_URL = "https://api.meshy.ai/v2/resources/";
    private static final int TIMEOUT_SECONDS = 30;
    private static final int MAX_POLLING_ATTEMPTS = 200;
    private static final int POLLING_INTERVAL_MS = 3000;

    private final ApiKeyConfig apiKeyConfig;
    private final OkHttpClient httpClient;

    public MeshyService(ApiKeyConfig apiKeyConfig) {
        this.apiKeyConfig = apiKeyConfig;
        this.httpClient = createHttpClient();
    }

    @NotNull
    @Contract(" -> new")
    private OkHttpClient createHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build();
    }

    @Nullable
    private JsonObject callMeshyApi(JsonObject requestBody, String apiKey) {
        try {
            log.info("Meshy API 호출: {}", requestBody);
            Request request = buildApiRequest(requestBody, apiKey);
            return executeRequest(request);
        } catch (IOException e) {
            log.error("API 호출 중 오류 발생: {}", e.getMessage());
            return null;
        }
    }

    @NotNull
    private Request buildApiRequest(@NotNull JsonObject requestBody, String apiKey) {
        RequestBody body = RequestBody.create(requestBody.toString(), JSON);
        return new Request.Builder()
                .url(MeshyService.MESHY_API_URL)
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer " + apiKey)
                .post(body)
                .build();
    }

    @Nullable
    private JsonObject getResourceStatus(String resourceId, String apiKey) {
        try {
            log.info("리소스 상태 확인: {}", resourceId);
            String statusUrl = MESHY_API_STATUS_URL + resourceId;
            Request request = buildStatusRequest(statusUrl, apiKey);
            return executeRequest(request);
        } catch (IOException e) {
            log.error("리소스 상태 확인 중 오류 발생: {}", e.getMessage());
            return null;
        }
    }

    @NotNull
    private Request buildStatusRequest(String url, String apiKey) {
        return new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + apiKey)
                .get()
                .build();
    }

    @Nullable
    private JsonObject executeRequest(Request request) throws IOException {
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.error("API 호출 실패. 상태 코드: {}", response.code());
                return null;
            }

            assert response.body() != null;
            String responseBody = response.body().string();
            return JsonParser.parseString(responseBody).getAsJsonObject();
        }
    }

    public String generateModel(String prompt, String objectName, int keyIndex) {
        try {
            String apiKey = apiKeyConfig.getMeshyKey(keyIndex);
            log.info("{}의 모델 생성 시작, 키 인덱스: {}", objectName, keyIndex);
            return processModelGeneration(prompt, objectName, apiKey);
        } catch (Exception e) {
            log.error("{}의 모델 생성 중 오류 발생: {}", objectName, e.getMessage());
            return "error-general-" + UUID.randomUUID().toString();
        }
    }

    @NotNull
    private String processModelGeneration(String prompt, String objectName, String apiKey) {
        try {
            String previewId = createPreview(prompt, apiKey);
            if (previewId == null) {
                log.error("{}의 프리뷰 생성 실패", objectName);
                return "error-preview-" + UUID.randomUUID().toString();
            }

            log.info("{}의 프리뷰가 ID: {}로 생성됨", objectName, previewId);
            return processPreview(previewId, objectName, apiKey);
        } catch (Exception e) {
            log.error("{}의 프리뷰 생성 단계에서 오류 발생: {}", objectName, e.getMessage());
            return "error-preview-exception-" + UUID.randomUUID().toString();
        }
    }

    @NotNull
    private String processPreview(String previewId, String objectName, String apiKey) {
        try {
            if (!waitForCompletion(previewId, apiKey)) {
                log.error("{}의 프리뷰 생성 시간 초과", objectName);
                return "timeout-preview-" + previewId;
            }

            return refineModelAfterPreview(previewId, objectName, apiKey);
        } catch (Exception e) {
            log.error("{}의 프리뷰 완료 대기 중 오류 발생: {}", objectName, e.getMessage());
            return "error-wait-exception-" + previewId;
        }
    }

    @NotNull
    private String refineModelAfterPreview(String previewId, String objectName, String apiKey) {
        try {
            String refineId = refineModel(previewId, apiKey);
            if (refineId == null) {
                log.error("{}의 모델 정제 실패", objectName);
                return "error-refine-" + previewId;
            }

            log.info("{}의 정제 작업이 ID: {}로 시작됨. 추적 ID를 반환합니다.", objectName, refineId);
            return refineId;
        } catch (Exception e) {
            log.error("{}의 모델 정제 단계에서 오류 발생: {}", objectName, e.getMessage());
            return "error-refine-exception-" + previewId;
        }
    }

    @Nullable
    private String createPreview(String prompt, String apiKey) {
        try {
            JsonObject requestBody = createPreviewRequestBody(prompt);
            JsonObject responseJson = callMeshyApi(requestBody, apiKey);
            return extractResourceId(responseJson);
        } catch (Exception e) {
            log.error("프리뷰 생성 중 오류 발생: {}", e.getMessage());
            return null;
        }
    }

    @NotNull
    private JsonObject createPreviewRequestBody(String prompt) {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("prompt", prompt);
        requestBody.addProperty("negative_prompt", "low quality, fast create");
        requestBody.addProperty("mode", "preview");
        return requestBody;
    }

    @Nullable
    private String refineModel(String previewId, String apiKey) {
        try {
            JsonObject requestBody = createRefineRequestBody(previewId);
            JsonObject responseJson = callMeshyApi(requestBody, apiKey);
            return extractResourceId(responseJson);
        } catch (Exception e) {
            log.error("모델 정제 중 오류 발생: {}", e.getMessage());
            return null;
        }
    }

    @NotNull
    private JsonObject createRefineRequestBody(String previewId) {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("resource_id", previewId);
        requestBody.addProperty("format", "fbx");
        requestBody.addProperty("mode", "refine");
        return requestBody;
    }

    @Nullable
    private String extractResourceId(JsonObject responseJson) {
        if (responseJson != null && responseJson.has("resource_id")) {
            return responseJson.get("resource_id").getAsString();
        }
        return null;
    }

    private boolean waitForCompletion(String resourceId, String apiKey) {
        try {
            for (int i = 0; i < MAX_POLLING_ATTEMPTS; i++) {
                JsonObject responseJson = getResourceStatus(resourceId, apiKey);
                if (responseJson == null) {
                    return false;
                }

                if (isResourceCompleted(responseJson)) {
                    return true;
                } else if (isResourceFailed(responseJson)) {
                    log.error("리소스 생성 실패: {}", responseJson);
                    return false;
                }

                Thread.sleep(POLLING_INTERVAL_MS);
            }

            log.error("리소스 생성 시간 초과");
            return false;
        } catch (Exception e) {
            log.error("상태 확인 중 오류 발생: {}", e.getMessage());
            return false;
        }
    }

    private boolean isResourceCompleted(@NotNull JsonObject responseJson) {
        String status = responseJson.get("status").getAsString();
        int progress = responseJson.get("progress").getAsInt();
        log.info("리소스 상태: {}, 진행률: {}%", status, progress);
        return "completed".equals(status);
    }

    private boolean isResourceFailed(@NotNull JsonObject responseJson) {
        return "failed".equals(responseJson.get("status").getAsString());
    }

}
