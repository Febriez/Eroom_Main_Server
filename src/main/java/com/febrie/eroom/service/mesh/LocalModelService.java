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

    // HTTP 관련 상수
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final int TIMEOUT_SECONDS = 30;
    private static final String API_ENDPOINT = "/api/create_model";
    private static final String HTTP_PREFIX = "http://";
    private static final String HTTPS_PREFIX = "https://";

    // 응답 필드 상수
    private static final String FIELD_MODEL_ID = "model_id";
    private static final String FIELD_ID = "id";
    private static final String FIELD_TRACKING_ID = "tracking_id";
    private static final String FIELD_PROMPT = "prompt";

    // 오류 타입 상수
    private static final String ERROR_TYPE_LOCAL = "local";
    private static final String ERROR_TYPE_NO_ID = "no-id";
    private static final String ERROR_TYPE_EXCEPTION = "exception";

    private final List<String> serverUrls;
    private final OkHttpClient httpClient;
    private final AtomicInteger serverIndex = new AtomicInteger(0);

    /**
     * LocalModelService 생성자
     * 로컬 모델 서비스를 초기화합니다.
     */
    public LocalModelService(@NotNull List<String> serverUrls) {
        this.serverUrls = serverUrls;
        this.httpClient = createHttpClient();
        log.info("LocalModelService 초기화 완료. 서버 {}개 등록됨", serverUrls.size());
    }

    /**
     * 3D 모델을 생성합니다.
     * 라운드 로빈 방식으로 서버를 선택하여 요청을 전송합니다.
     */
    @Override
    public String generateModel(String prompt, String objectName, int keyIndex) {
        try {
            String serverUrl = getNextServerUrl();
            logModelGenerationStart(objectName, serverUrl, prompt);

            JsonObject requestBody = createRequestBody(prompt);
            Response response = executeRequest(serverUrl, requestBody);

            return processResponse(response, objectName, serverUrl);

        } catch (Exception e) {
            return handleGenerationError(objectName, e);
        }
    }

    /**
     * HTTP 클라이언트를 생성합니다.
     */
    @NotNull
    private OkHttpClient createHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 모델 생성 시작을 로깅합니다.
     */
    private void logModelGenerationStart(String objectName, String serverUrl, String prompt) {
        log.info("{}의 모델 생성 시작, 서버: {}, 프롬프트: '{}'",
                objectName, serverUrl, prompt);
    }

    /**
     * 요청 본문을 생성합니다.
     */
    @NotNull
    private JsonObject createRequestBody(String prompt) {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty(FIELD_PROMPT, prompt);
        return requestBody;
    }

    /**
     * HTTP 요청을 실행합니다.
     */
    @NotNull
    private Response executeRequest(String serverUrl, JsonObject requestBody) throws Exception {
        Request request = buildRequest(serverUrl, requestBody);
        return httpClient.newCall(request).execute();
    }

    /**
     * HTTP 요청을 빌드합니다.
     */
    @NotNull
    private Request buildRequest(String serverUrl, @NotNull JsonObject requestBody) {
        return new Request.Builder()
                .url(serverUrl)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(requestBody.toString(), JSON))
                .build();
    }

    /**
     * 응답을 처리합니다.
     */
    private String processResponse(@NotNull Response response, String objectName, String serverUrl) throws Exception {
        try (response) {
            if (!response.isSuccessful()) {
                return handleUnsuccessfulResponse(response, objectName, serverUrl);
            }

            return extractModelIdFromResponse(response, objectName);
        }
    }

    /**
     * 실패한 응답을 처리합니다.
     */
    @NotNull
    private String handleUnsuccessfulResponse(@NotNull Response response, String objectName, String serverUrl) {
        log.error("로컬 서버 응답 실패. 상태 코드: {}, 서버: {}",
                response.code(), serverUrl);
        return generateErrorId(ERROR_TYPE_LOCAL);
    }

    /**
     * 응답에서 모델 ID를 추출합니다.
     */
    @NotNull
    private String extractModelIdFromResponse(@NotNull Response response, String objectName) throws Exception {
        assert response.body() != null;
        String responseBody = response.body().string();
        JsonObject responseJson = JsonParser.parseString(responseBody).getAsJsonObject();

        String modelId = extractModelId(responseJson);
        if (modelId != null) {
            log.info("{}의 모델 생성 완료. ID: {}", objectName, modelId);
            return modelId;
        } else {
            log.error("모델 ID를 추출할 수 없습니다: {}", objectName);
            return generateErrorId(ERROR_TYPE_NO_ID);
        }
    }

    /**
     * 생성 오류를 처리합니다.
     */
    @NotNull
    private String handleGenerationError(String objectName, @NotNull Exception e) {
        log.error("{}의 모델 생성 중 오류 발생: {}", objectName, e.getMessage());
        return generateErrorId(ERROR_TYPE_EXCEPTION);
    }

    /**
     * 다음 서버 URL을 가져옵니다.
     * 라운드 로빈 방식으로 서버를 선택합니다.
     */
    @NotNull
    private String getNextServerUrl() {
        validateServerUrlsNotEmpty();

        int index = serverIndex.getAndIncrement() % serverUrls.size();
        String baseUrl = serverUrls.get(index);

        return buildFullUrl(baseUrl);
    }

    /**
     * 서버 URL 목록이 비어있지 않은지 검증합니다.
     */
    private void validateServerUrlsNotEmpty() {
        if (serverUrls.isEmpty()) {
            throw new IllegalStateException("사용 가능한 로컬 서버가 없습니다");
        }
    }

    /**
     * 전체 URL을 빌드합니다.
     */
    @NotNull
    private String buildFullUrl(String baseUrl) {
        String urlWithProtocol = ensureProtocol(baseUrl);
        return urlWithProtocol + API_ENDPOINT;
    }

    /**
     * URL에 프로토콜이 있는지 확인하고 없으면 추가합니다.
     */
    @NotNull
    private String ensureProtocol(@NotNull String url) {
        if (!url.startsWith(HTTP_PREFIX) && !url.startsWith(HTTPS_PREFIX)) {
            return HTTP_PREFIX + url;
        }
        return url;
    }

    /**
     * 응답에서 모델 ID를 추출합니다.
     * 여러 가능한 필드 이름을 확인합니다.
     */
    @Nullable
    private String extractModelId(@NotNull JsonObject responseJson) {
        // 가능한 필드 이름들을 순차적으로 확인
        String[] possibleFields = {FIELD_MODEL_ID, FIELD_ID, FIELD_TRACKING_ID};

        for (String field : possibleFields) {
            if (responseJson.has(field)) {
                return responseJson.get(field).getAsString();
            }
        }

        return null;
    }

    /**
     * 오류 ID를 생성합니다.
     */
    @NotNull
    private String generateErrorId(String errorType) {
        return String.format("error-%s-%s", errorType, UUID.randomUUID());
    }
}