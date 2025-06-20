package com.febrie.eroom.service.mesh;

import com.febrie.eroom.config.ApiKeyProvider;
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

public class MeshyApiService implements MeshService {
    private static final Logger log = LoggerFactory.getLogger(MeshyApiService.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String MESHY_API_BASE_URL = "https://api.meshy.ai/openapi/v2/text-to-3d";
    private static final int TIMEOUT_SECONDS = 30;
    private static final int MAX_POLLING_ATTEMPTS = 100;
    private static final int POLLING_INTERVAL_MS = 5000;

    private final ApiKeyProvider apiKeyProvider;
    private final OkHttpClient httpClient;

    public MeshyApiService(ApiKeyProvider apiKeyProvider) {
        this.apiKeyProvider = apiKeyProvider;
        this.httpClient = createHttpClient();
    }

    @Override
    public String generateModel(String prompt, String objectName, int keyIndex) {
        try {
            String apiKey = apiKeyProvider.getMeshyKey(keyIndex);
            log.info("{}의 모델 생성 시작, 키 인덱스: {}", objectName, keyIndex);
            return processModelGeneration(prompt, objectName, apiKey);
        } catch (Exception e) {
            log.error("{}의 모델 생성 중 오류 발생: {}", objectName, e.getMessage());
            return "error-general-" + UUID.randomUUID().toString();
        }
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

    @NotNull
    private String processModelGeneration(String prompt, String objectName, String apiKey) {
        try {
            String previewId = createPreview(prompt, apiKey);
            if (previewId == null) {
                log.error("{}의 프리뷰 생성 실패", objectName);
                return "error-preview-" + UUID.randomUUID();
            }

            log.info("{}의 프리뷰가 ID: {}로 생성됨", objectName, previewId);
            return processPreview(previewId, objectName, apiKey);
        } catch (Exception e) {
            log.error("{}의 프리뷰 생성 단계에서 오류 발생: {}", objectName, e.getMessage());
            return "error-preview-exception-" + UUID.randomUUID();
        }
    }

    @NotNull
    private String processPreview(String previewId, String objectName, String apiKey) {
        try {
            if (isTaskFailed(previewId, apiKey)) {
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

            log.info("{}의 정제 작업이 ID: {}로 시작됨. 완료 대기 중...", objectName, refineId);

            // Refine 작업 완료 대기
            if (isTaskFailed(refineId, apiKey)) {
                log.error("{}의 정제 작업 완료 시간 초과", objectName);
                return "timeout-refine-" + refineId;
            }

            // 완료된 작업의 상세 정보 조회
            JsonObject taskDetails = getCompletedTaskDetails(refineId, apiKey);
            if (taskDetails == null) {
                log.error("{}의 완료된 작업 정보 조회 실패", objectName);
                return "error-fetch-details-" + refineId;
            }

            // FBX URL 추출
            String fbxUrl = extractFbxUrl(taskDetails);
            if (fbxUrl == null) {
                log.error("{}의 FBX URL 추출 실패", objectName);
                return "error-no-fbx-" + refineId;
            }

            log.info("{}의 모델 생성 완료. FBX URL: {}", objectName, fbxUrl);
            return fbxUrl;

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
        requestBody.addProperty("mode", "preview");
        requestBody.addProperty("prompt", prompt);
        requestBody.addProperty("art_style", "realistic");
        requestBody.addProperty("ai_model", "meshy-4");
        requestBody.addProperty("topology", "triangle");
        requestBody.addProperty("target_polycount", 15000);
        requestBody.addProperty("should_remesh", false);
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
        requestBody.addProperty("mode", "refine");
        requestBody.addProperty("preview_task_id", previewId);
        requestBody.addProperty("enable_pbr", false);
        return requestBody;
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
                .url(MESHY_API_BASE_URL)
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer " + apiKey)
                .post(body)
                .build();
    }

    @Nullable
    private JsonObject getTaskStatus(String taskId, String apiKey) {
        try {
            log.info("작업 상태 확인: {}", taskId);
            String statusUrl = MESHY_API_BASE_URL + "/" + taskId;
            Request request = buildStatusRequest(statusUrl, apiKey);
            return executeRequest(request);
        } catch (IOException e) {
            log.error("작업 상태 확인 중 오류 발생: {}", e.getMessage());
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
                log.error("API 호출 실패. 상태 코드: {}, 메시지: {}", response.code(), response.message());
                if (response.body() != null) {
                    String errorBody = response.body().string();
                    log.error("에러 응답: {}", errorBody);
                }
                return null;
            }

            assert response.body() != null;
            String responseBody = response.body().string();
            return JsonParser.parseString(responseBody).getAsJsonObject();
        }
    }

    private String extractResourceId(JsonObject responseJson) {
        if (responseJson != null && responseJson.has("result")) {
            return responseJson.get("result").getAsString();
        }
        return null;
    }

    private boolean isTaskFailed(String taskId, String apiKey) {
        try {
            for (int i = 0; i < MAX_POLLING_ATTEMPTS; i++) {
                JsonObject taskStatus = getTaskStatus(taskId, apiKey);
                if (taskStatus == null) {
                    log.error("작업 상태 조회 실패");
                    return true;
                }

                String status = taskStatus.get("status").getAsString();
                int progress = taskStatus.get("progress").getAsInt();

                log.info("작업 상태: {}, 진행률: {}%", status, progress);

                if ("SUCCEEDED".equals(status)) {
                    return false;
                } else if ("FAILED".equals(status) || "CANCELED".equals(status)) {
                    if (taskStatus.has("task_error") && taskStatus.getAsJsonObject("task_error").has("message")) {
                        String errorMessage = taskStatus.getAsJsonObject("task_error").get("message").getAsString();
                        log.error("작업 실패: {}", errorMessage);
                    }
                    return true;
                }

                Thread.sleep(POLLING_INTERVAL_MS);
            }

            log.error("작업 생성 시간 초과");
            return true;
        } catch (Exception e) {
            log.error("상태 확인 중 오류 발생: {}", e.getMessage());
            return true;
        }
    }

    /**
     * 완료된 작업의 모델 URL을 가져옵니다.
     */
    public JsonObject getCompletedTaskDetails(String taskId, String apiKey) {
        try {
            JsonObject taskStatus = getTaskStatus(taskId, apiKey);
            if (taskStatus != null && "SUCCEEDED".equals(taskStatus.get("status").getAsString())) {
                return taskStatus;
            }
            return null;
        } catch (Exception e) {
            log.error("작업 상세 정보 조회 중 오류 발생: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 작업 응답에서 FBX URL을 추출합니다.
     */
    @Nullable
    private String extractFbxUrl(JsonObject taskDetails) {
        try {
            if (taskDetails.has("model_urls")) {
                JsonObject modelUrls = taskDetails.getAsJsonObject("model_urls");
                if (modelUrls.has("fbx")) {
                    return modelUrls.get("fbx").getAsString();
                }
            }
            return null;
        } catch (Exception e) {
            log.error("FBX URL 추출 중 오류 발생: {}", e.getMessage());
            return null;
        }
    }
}