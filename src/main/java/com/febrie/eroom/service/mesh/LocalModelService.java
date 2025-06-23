package com.febrie.eroom.service.mesh;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class LocalModelService implements MeshService {
    private static final Logger log = LoggerFactory.getLogger(LocalModelService.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final int TIMEOUT_SECONDS = 30;
    private static final String API_ENDPOINT = "/api/create_model";

    private final List<String> serverUrls;
    private final OkHttpClient httpClient;
    private final AtomicInteger serverIndex = new AtomicInteger(0);

    public LocalModelService(List<String> serverUrls) {
        this.serverUrls = serverUrls;
        this.httpClient = createHttpClient();
        log.info("LocalModelService 초기화 완료. 서버 {}개 등록됨", serverUrls.size());
    }

    @Override
    public String generateModel(String prompt, String objectName, int keyIndex) {
        try {
            String serverUrl = getNextServerUrl();
            log.info("{}의 모델 생성 시작, 서버: {}, 프롬프트: '{}'",
                    objectName, serverUrl, prompt);

            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("prompt", prompt);

            Request request = new Request.Builder()
                    .url(serverUrl)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(requestBody.toString(), JSON))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.error("로컬 서버 응답 실패. 상태 코드: {}, 서버: {}",
                            response.code(), serverUrl);
                    return "error-local-" + UUID.randomUUID();
                }

                assert response.body() != null;
                String responseBody = response.body().string();
                JsonObject responseJson = JsonParser.parseString(responseBody).getAsJsonObject();

                // 로컬 서버의 응답 형식에 따라 수정 필요
                String modelId = extractModelId(responseJson);
                if (modelId != null) {
                    log.info("{}의 모델 생성 완료. ID: {}", objectName, modelId);
                    return modelId;
                } else {
                    log.error("모델 ID를 추출할 수 없습니다: {}", objectName);
                    return "error-no-id-" + UUID.randomUUID();
                }
            }

        } catch (Exception e) {
            log.error("{}의 모델 생성 중 오류 발생: {}", objectName, e.getMessage());
            return "error-exception-" + UUID.randomUUID();
        }
    }

    @NotNull
    private OkHttpClient createHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build();
    }

    private String getNextServerUrl() {
        if (serverUrls.isEmpty()) {
            throw new IllegalStateException("사용 가능한 로컬 서버가 없습니다");
        }

        // 라운드 로빈 방식으로 서버 선택
        int index = serverIndex.getAndIncrement() % serverUrls.size();
        String baseUrl = serverUrls.get(index);

        // http:// 프로토콜이 없으면 추가
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            baseUrl = "http://" + baseUrl;
        }

        return baseUrl + API_ENDPOINT;
    }

    @Nullable
    private String extractModelId(@NotNull JsonObject responseJson) {
        // 로컬 서버의 응답 형식에 따라 수정
        // 예: {"model_id": "xxx", "status": "success"}
        if (responseJson.has("model_id")) {
            return responseJson.get("model_id").getAsString();
        } else if (responseJson.has("id")) {
            return responseJson.get("id").getAsString();
        } else if (responseJson.has("tracking_id")) {
            return responseJson.get("tracking_id").getAsString();
        }
        return null;
    }
}