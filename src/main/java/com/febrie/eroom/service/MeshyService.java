package com.febrie.eroom.service;

import com.febrie.eroom.config.ApiKeyConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.*;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class MeshyService {

    private static final Logger log = LoggerFactory.getLogger(MeshyService.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final ApiKeyConfig apiKeyConfig;
    private final OkHttpClient httpClient;
    private static final String MESHY_API_URL = "https://api.meshy.ai/v2/text-to-3d";
    private static final String MESHY_API_STATUS_URL = "https://api.meshy.ai/v2/resources/";

    public MeshyService(ApiKeyConfig apiKeyConfig) {
        this.apiKeyConfig = apiKeyConfig;
        this.httpClient = new OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS).build();
    }

    @Nullable
    private JsonObject callMeshyApi(JsonObject requestBody, String apiKey) {
        try {
            log.info("Meshy API 호출: {}", requestBody);

            RequestBody body = RequestBody.create(requestBody.toString(), JSON);
            Request request = new Request.Builder().url(MESHY_API_URL).addHeader("Content-Type", "application/json").addHeader("Authorization", "Bearer " + apiKey).post(body).build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.error("API 호출 실패. 상태 코드: {}", response.code());
                    return null;
                }

                assert response.body() != null;
                String responseBody = response.body().string();
                return JsonParser.parseString(responseBody).getAsJsonObject();
            }
        } catch (IOException e) {
            log.error("API 호출 중 오류 발생: {}", e.getMessage());
            return null;
        }
    }

    @Nullable
    private JsonObject getResourceStatus(String resourceId, String apiKey) {
        try {
            log.info("리소스 상태 확인: {}", resourceId);
            String statusUrl = MESHY_API_STATUS_URL + resourceId;

            Request request = new Request.Builder().url(statusUrl).addHeader("Authorization", "Bearer " + apiKey).get().build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.error("리소스 상태 확인 실패. 상태 코드: {}", response.code());
                    return null;
                }

                assert response.body() != null;
                String responseBody = response.body().string();
                return JsonParser.parseString(responseBody).getAsJsonObject();
            }
        } catch (IOException e) {
            log.error("리소스 상태 확인 중 오류 발생: {}", e.getMessage());
            return null;
        }
    }

    public String generateModel(String prompt, String objectName, int keyIndex) {
        try {
            String apiKey = apiKeyConfig.getMeshyKey(keyIndex);
            log.info("{}의 모델 생성 시작, 키 인덱스: {}", objectName, keyIndex);

            try {
                String previewId = createPreview(prompt, apiKey);
                if (previewId == null) {
                    log.error("{}의 프리뷰 생성 실패", objectName);
                    return "error-preview-" + UUID.randomUUID().toString();
                }

                log.info("{}의 프리뷰가 ID: {}로 생성됨", objectName, previewId);

                try {
                    boolean previewCompleted = waitForCompletion(previewId, apiKey);
                    if (!previewCompleted) {
                        log.error("{}의 프리뷰 생성 시간 초과", objectName);
                        return "timeout-preview-" + previewId;
                    }

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
                } catch (Exception e) {
                    log.error("{}의 프리뷰 완료 대기 중 오류 발생: {}", objectName, e.getMessage());
                    return "error-wait-exception-" + previewId;
                }
            } catch (Exception e) {
                log.error("{}의 프리뷰 생성 단계에서 오류 발생: {}", objectName, e.getMessage());
                return "error-preview-exception-" + UUID.randomUUID().toString();
            }
        } catch (Exception e) {
            log.error("{}의 모델 생성 중 오류 발생: {}", objectName, e.getMessage());
            return "error-general-" + UUID.randomUUID().toString();
        }
    }

    @Nullable
    private String createPreview(String prompt, String apiKey) {
        try {
            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("prompt", prompt);
            requestBody.addProperty("negative_prompt", "low quality, fast create");
            requestBody.addProperty("mode", "preview");

            JsonObject responseJson = callMeshyApi(requestBody, apiKey);
            if (responseJson != null && responseJson.has("resource_id")) {
                return responseJson.get("resource_id").getAsString();
            }

            return null;
        } catch (Exception e) {
            log.error("프리뷰 생성 중 오류 발생: {}", e.getMessage());
            return null;
        }
    }

    @Nullable
    private String refineModel(String previewId, String apiKey) {
        try {
            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("resource_id", previewId);
            requestBody.addProperty("format", "fbx");
            requestBody.addProperty("mode", "refine");

            JsonObject responseJson = callMeshyApi(requestBody, apiKey);
            if (responseJson != null && responseJson.has("resource_id")) {
                return responseJson.get("resource_id").getAsString();
            }

            return null;
        } catch (Exception e) {
            log.error("모델 정제 중 오류 발생: {}", e.getMessage());
            return null;
        }
    }

    private boolean waitForCompletion(String resourceId, String apiKey) {
        try {
            for (int i = 0; i < 200; i++) { // 최대 10분(3초 간격으로 200번) 대기
                JsonObject responseJson = getResourceStatus(resourceId, apiKey);
                if (responseJson == null) {
                    return false;
                }

                String status = responseJson.get("status").getAsString();
                int progress = responseJson.get("progress").getAsInt();

                log.info("리소스 {} 상태: {}, 진행률: {}%", resourceId, status, progress);

                if ("completed".equals(status)) {
                    return true;
                } else if ("failed".equals(status)) {
                    log.error("리소스 생성 실패: {}", responseJson);
                    return false;
                }

                Thread.sleep(3000); // 3초 대기
            }

            log.error("10분 후 리소스 생성 시간 초과");
            return false;
        } catch (Exception e) {
            log.error("상태 확인 중 오류 발생: {}", e.getMessage());
            return false;
        }
    }

}
