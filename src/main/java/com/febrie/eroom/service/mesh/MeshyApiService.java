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

    // HTTP 관련 상수
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String MESHY_API_BASE_URL = "https://api.meshy.ai/openapi/v2/text-to-3d";
    private static final int TIMEOUT_SECONDS = 30;

    // 폴링 관련 상수
    private static final int MAX_POLLING_ATTEMPTS = 30;
    private static final int POLLING_INTERVAL_MS = 10000;

    // 폴리곤 수 설정 상수
    private static final int PREVIEW_POLYCOUNT = 4096;
    private static final int REFINE_POLYCOUNT = 4096;

    // 응답 필드 상수
    private static final String FIELD_RESULT = "result";
    private static final String FIELD_STATUS = "status";
    private static final String FIELD_PROGRESS = "progress";
    private static final String FIELD_TASK_ERROR = "task_error";
    private static final String FIELD_MESSAGE = "message";
    private static final String FIELD_MODEL_URLS = "model_urls";
    private static final String FIELD_FBX = "fbx";

    // 상태 상수
    private static final String STATUS_SUCCEEDED = "SUCCEEDED";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_CANCELED = "CANCELED";

    private final ApiKeyProvider apiKeyProvider;
    private final OkHttpClient httpClient;

    /**
     * MeshyApiService 생성자
     * Meshy API 서비스를 초기화합니다.
     */
    public MeshyApiService(ApiKeyProvider apiKeyProvider) {
        this.apiKeyProvider = apiKeyProvider;
        this.httpClient = createHttpClient();
    }

    /**
     * 3D 모델을 생성합니다.
     * 프리뷰 생성 후 정제 과정을 거쳐 최종 FBX URL을 반환합니다.
     */
    @Override
    public String generateModel(String prompt, String objectName, int keyIndex) {
        try {
            String apiKey = apiKeyProvider.getMeshyKey(keyIndex);
            log.info("{}의 모델 생성 시작, 키 인덱스: {}", objectName, keyIndex);
            return processModelGeneration(prompt, objectName, apiKey);
        } catch (Exception e) {
            log.error("{}의 모델 생성 중 오류 발생: {}", objectName, e.getMessage());
            return generateErrorId("general");
        }
    }

    /**
     * HTTP 클라이언트를 생성합니다.
     */
    @NotNull
    @Contract(" -> new")
    private OkHttpClient createHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 모델 생성 프로세스를 처리합니다.
     */
    @NotNull
    private String processModelGeneration(String prompt, String objectName, String apiKey) {
        try {
            String previewId = createPreview(prompt, apiKey);
            if (previewId == null) {
                return logAndReturnError(objectName, "프리뷰 생성 실패", "preview");
            }

            log.info("{}의 프리뷰가 ID: {}로 생성됨", objectName, previewId);
            return processPreview(previewId, objectName, apiKey);
        } catch (Exception e) {
            return logAndReturnError(objectName, "프리뷰 생성 단계에서 오류 발생: " + e.getMessage(),
                    "preview-exception");
        }
    }

    /**
     * 오류를 로그하고 오류 ID를 반환합니다.
     */
    @NotNull
    private String logAndReturnError(String objectName, String errorMessage, String errorType) {
        log.error("{}의 {}", objectName, errorMessage);
        return generateErrorId(errorType);
    }

    /**
     * 프리뷰를 처리합니다.
     */
    @NotNull
    private String processPreview(String previewId, String objectName, String apiKey) {
        try {
            if (isTaskFailed(previewId, apiKey, "프리뷰")) {
                log.error("{}의 프리뷰 생성 실패 또는 시간 초과", objectName);
                return generateTimeoutId("preview", previewId);
            }

            return refineModelAfterPreview(previewId, objectName, apiKey);
        } catch (Exception e) {
            log.error("{}의 프리뷰 완료 대기 중 오류 발생: {}", objectName, e.getMessage());
            return generateErrorId("wait-exception", previewId);
        }
    }

    /**
     * 프리뷰 후 모델을 정제합니다.
     */
    @NotNull
    private String refineModelAfterPreview(String previewId, String objectName, String apiKey) {
        try {
            String refineId = refineModel(previewId, apiKey);
            if (refineId == null) {
                log.error("{}의 모델 정제 실패", objectName);
                return generateErrorId("refine", previewId);
            }

            logRefineStart(objectName, refineId);

            if (isTaskFailed(refineId, apiKey, "정제")) {
                log.error("{}의 정제 작업 실패 또는 시간 초과", objectName);
                return generateTimeoutId("refine", refineId);
            }

            return extractFinalModelUrl(refineId, objectName, apiKey);

        } catch (Exception e) {
            log.error("{}의 모델 정제 단계에서 오류 발생: {}", objectName, e.getMessage());
            return generateErrorId("refine-exception", previewId);
        }
    }

    /**
     * 정제 시작을 로깅합니다.
     */
    private void logRefineStart(String objectName, String refineId) {
        log.info("{}의 정제 작업이 ID: {}로 시작됨 (target_polycount: {}). 완료 대기 중...",
                objectName, refineId, REFINE_POLYCOUNT);
    }

    /**
     * 최종 모델 URL을 추출합니다.
     */
    @NotNull
    private String extractFinalModelUrl(String refineId, String objectName, String apiKey) {
        JsonObject taskDetails = getCompletedTaskDetails(refineId, apiKey);
        if (taskDetails == null) {
            log.error("{}의 완료된 작업 정보 조회 실패", objectName);
            return generateErrorId("fetch-details", refineId);
        }

        String fbxUrl = extractFbxUrl(taskDetails);
        if (fbxUrl == null) {
            log.error("{}의 FBX URL 추출 실패", objectName);
            return generateErrorId("no-fbx", refineId);
        }

        log.info("{}의 모델 생성 완료. FBX URL: {}", objectName, fbxUrl);
        return fbxUrl;
    }

    /**
     * 프리뷰를 생성합니다.
     */
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

    /**
     * 프리뷰 요청 본문을 생성합니다.
     */
    @NotNull
    private JsonObject createPreviewRequestBody(String prompt) {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("mode", "preview");
        requestBody.addProperty("prompt", prompt);
        requestBody.addProperty("art_style", "realistic");
        requestBody.addProperty("ai_model", "meshy-4");
        requestBody.addProperty("topology", "triangle");
        requestBody.addProperty("target_polycount", PREVIEW_POLYCOUNT);
        requestBody.addProperty("should_remesh", false);
        return requestBody;
    }

    /**
     * 모델을 정제합니다.
     */
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

    /**
     * 정제 요청 본문을 생성합니다.
     */
    @NotNull
    private JsonObject createRefineRequestBody(String previewId) {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("mode", "refine");
        requestBody.addProperty("preview_task_id", previewId);
        requestBody.addProperty("enable_pbr", false);
        requestBody.addProperty("target_polycount", REFINE_POLYCOUNT);
        requestBody.addProperty("topology", "triangle");
        requestBody.addProperty("should_remesh", false);

        log.info("Refine 요청 설정 - target_polycount: {}, enable_pbr: false, should_remesh: false",
                REFINE_POLYCOUNT);

        return requestBody;
    }

    /**
     * Meshy API를 호출합니다.
     */
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

    /**
     * API 요청을 빌드합니다.
     */
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

    /**
     * 작업 상태를 조회합니다.
     */
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

    /**
     * 상태 확인 요청을 빌드합니다.
     */
    @NotNull
    private Request buildStatusRequest(String url, String apiKey) {
        return new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + apiKey)
                .get()
                .build();
    }

    /**
     * HTTP 요청을 실행합니다.
     */
    @Nullable
    private JsonObject executeRequest(Request request) throws IOException {
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                handleUnsuccessfulResponse(response);
                return null;
            }

            return parseResponse(response);
        }
    }

    /**
     * 실패한 응답을 처리합니다.
     */
    private void handleUnsuccessfulResponse(@NotNull Response response) throws IOException {
        log.error("API 호출 실패. 상태 코드: {}, 메시지: {}", response.code(), response.message());
        if (response.body() != null) {
            String errorBody = response.body().string();
            log.error("에러 응답: {}", errorBody);
        }
    }

    /**
     * 응답을 파싱합니다.
     */
    private JsonObject parseResponse(@NotNull Response response) throws IOException {
        assert response.body() != null;
        String responseBody = response.body().string();
        return JsonParser.parseString(responseBody).getAsJsonObject();
    }

    /**
     * 응답에서 리소스 ID를 추출합니다.
     */
    private String extractResourceId(JsonObject responseJson) {
        if (responseJson != null && responseJson.has(FIELD_RESULT)) {
            return responseJson.get(FIELD_RESULT).getAsString();
        }
        return null;
    }

    /**
     * 작업이 실패했는지 확인합니다.
     *
     * @return 작업이 실패했으면 true, 성공했으면 false
     */
    private boolean isTaskFailed(String taskId, String apiKey, String taskType) {
        try {
            for (int i = 0; i < MAX_POLLING_ATTEMPTS; i++) {
                TaskStatus status = checkTaskStatus(taskId, apiKey);

                if (status == null) {
                    return true;
                }

                logTaskProgress(status);

                switch (status.status()) {
                    case STATUS_SUCCEEDED:
                        return false;
                    case STATUS_FAILED:
                    case STATUS_CANCELED:
                        logTaskError(status);
                        return true;
                }

                Thread.sleep(POLLING_INTERVAL_MS);
            }

            log.error("{} 생성 시간 초과", taskType);
            return true;

        } catch (Exception e) {
            log.error("상태 확인 중 오류 발생: {}", e.getMessage());
            return true;
        }
    }

    /**
     * 작업 상태를 확인합니다.
     */
    @Nullable
    private TaskStatus checkTaskStatus(String taskId, String apiKey) {
        JsonObject taskStatus = getTaskStatus(taskId, apiKey);
        if (taskStatus == null) {
            log.error("작업 상태 조회 실패");
            return null;
        }

        String status = taskStatus.get(FIELD_STATUS).getAsString();
        int progress = taskStatus.get(FIELD_PROGRESS).getAsInt();
        String errorMessage = extractTaskError(taskStatus);

        return new TaskStatus(status, progress, errorMessage);
    }

    /**
     * 작업 진행 상태를 로깅합니다.
     */
    private void logTaskProgress(@NotNull TaskStatus status) {
        log.info("작업 상태: {}, 진행률: {}%", status.status(), status.progress());
    }

    /**
     * 작업 오류를 로깅합니다.
     */
    private void logTaskError(@NotNull TaskStatus status) {
        if (status.errorMessage() != null) {
            log.error("작업 실패: {}", status.errorMessage());
        }
    }

    /**
     * 작업 오류 메시지를 추출합니다.
     */
    @Nullable
    private String extractTaskError(@NotNull JsonObject taskStatus) {
        if (taskStatus.has(FIELD_TASK_ERROR)) {
            JsonObject taskError = taskStatus.getAsJsonObject(FIELD_TASK_ERROR);
            if (taskError.has(FIELD_MESSAGE)) {
                return taskError.get(FIELD_MESSAGE).getAsString();
            }
        }
        return null;
    }

    /**
     * 완료된 작업의 세부 정보를 가져옵니다.
     */
    public JsonObject getCompletedTaskDetails(String taskId, String apiKey) {
        try {
            JsonObject taskStatus = getTaskStatus(taskId, apiKey);
            if (isTaskSucceeded(taskStatus)) {
                return taskStatus;
            }
            return null;
        } catch (Exception e) {
            log.error("작업 상세 정보 조회 중 오류 발생: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 작업이 성공했는지 확인합니다.
     */
    private boolean isTaskSucceeded(JsonObject taskStatus) {
        return taskStatus != null &&
                STATUS_SUCCEEDED.equals(taskStatus.get(FIELD_STATUS).getAsString());
    }

    /**
     * 작업 응답에서 FBX URL을 추출합니다.
     */
    @Nullable
    private String extractFbxUrl(JsonObject taskDetails) {
        try {
            if (taskDetails.has(FIELD_MODEL_URLS)) {
                JsonObject modelUrls = taskDetails.getAsJsonObject(FIELD_MODEL_URLS);
                if (modelUrls.has(FIELD_FBX)) {
                    return modelUrls.get(FIELD_FBX).getAsString();
                }
            }
            return null;
        } catch (Exception e) {
            log.error("FBX URL 추출 중 오류 발생: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 오류 ID를 생성합니다.
     */
    @NotNull
    private String generateErrorId(String errorType) {
        return generateErrorId(errorType, null);
    }

    /**
     * 추가 정보와 함께 오류 ID를 생성합니다.
     */
    @NotNull
    private String generateErrorId(String errorType, String additionalInfo) {
        String baseId = "error-" + errorType + "-" + UUID.randomUUID();
        if (additionalInfo != null) {
            return baseId + "-" + additionalInfo;
        }
        return baseId;
    }

    /**
     * 타임아웃 ID를 생성합니다.
     */
    @NotNull
    @Contract(pure = true)
    private String generateTimeoutId(String stage, String taskId) {
        return "timeout-" + stage + "-" + taskId;
    }

    /**
     * 작업 상태를 나타내는 내부 레코드
     */
    private record TaskStatus(String status, int progress, String errorMessage) {
    }
}