package com.febrie.api;

import com.febrie.model.MeshyTask;
import com.febrie.service.ApiKeyRotator;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.time.LocalDateTime;

/**
 * Meshy API와 통신하는 클래스
 * 여러 API 키를 로테이션하여 부하 분산 및 안정성 확보
 */
@Slf4j
public class MeshyAPI {
    private static final String API_BASE_URL = "https://api.meshy.ai";

    // API 키 로테이터 추가
    private final ApiKeyRotator apiKeyRotator;
    private final OkHttpClient client = new OkHttpClient();
    private final Gson gson = new Gson();

    private static MeshyAPI instance;

    /**
     * 싱글톤 인스턴스 반환
     */
    public static synchronized MeshyAPI getInstance() {
        if (instance == null) {
            instance = new MeshyAPI();
        }
        return instance;
    }

    /**
     * 생성자 - API 키 로테이터 초기화
     */
    private MeshyAPI() {
        // API 키 로테이터 초기화
        this.apiKeyRotator = ApiKeyRotator.getInstance();

        // API 키 유효성 검사
        if (!this.apiKeyRotator.hasValidKeys()) {
            log.warn("유효한 MESHY_API_KEY 환경 변수가 설정되지 않았습니다. API 호출이 실패할 수 있습니다.");
        } else {
            log.info("{}개의 Meshy API 키가 설정되었습니다.", this.apiKeyRotator.getKeyCount());
        }
    }

    /**
     * API 키를 로테이션으로 가져오는 메서드
     * 여러 개의 API 키를 번갈아가며 사용
     */
    private String getApiKey() {
        String key = apiKeyRotator.getNextApiKey();
        if (key.isEmpty()) {
            log.error("API 키가 설정되지 않았습니다. 환경 변수 MESHY_API_KEY 또는 MESHY_API_KEY_2, MESHY_API_KEY_3 등을 확인하세요.");
        }
        return key;
    }

    /**
     * 텍스트 전처리 (API 요청용)
     */
    @NotNull
    private String preprocessText(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }

        return input
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", " ")
                .replace("\r", " ")
                .replace("\t", " ");
    }

    /**
     * Preview 태스크 생성 요청
     */
    public String createPreviewTask(String prompt) throws IOException {
        MediaType JSON = MediaType.get("application/json; charset=utf-8");

        String processedPrompt = preprocessText(prompt);
        log.info("Preview 모델 생성 요청 (프롬프트 길이: {}자)", prompt.length());

        String json = String.format("""
                {
                    "mode": "preview",
                    "prompt": "%s",
                    "ai_model": "meshy-4",
                    "topology": "triangle",
                    "target_polycount": 1000,
                    "should_remesh": false,
                    "symmetry_mode": "off",
                    "moderation": false
                }
                """, processedPrompt);

        return executeApiRequest(json, JSON);
    }

    /**
     * Refine 태스크 생성 요청
     */
    public String createRefineTask(String previewTaskId, boolean enablePbr, String texturePrompt) throws IOException {
        MediaType JSON = MediaType.get("application/json; charset=utf-8");

        String processedTexturePrompt = preprocessText(texturePrompt);
        log.info("Refine 모델 생성 요청 (Preview ID: {}, PBR: {}, 텍스처 프롬프트 길이: {}자)", 
                previewTaskId, enablePbr, texturePrompt.length());

        String json = String.format("""
                {
                    "mode": "refine",
                    "preview_task_id": "%s",
                    "enable_pbr": %s,
                    "texture_prompt": "%s",
                    "moderation": false
                }
                """, previewTaskId, enablePbr, processedTexturePrompt);

        return executeApiRequest(json, JSON);
    }

    /**
     * API 요청 실행 (여러 API 키 로테이션 및 재시도 지원)
     */
    private String executeApiRequest(@NotNull String json, MediaType mediaType) throws IOException {
        log.info("API 요청 JSON: {}", json.replace("\n", " ").replace("  ", " "));

        // 최대 재시도 횟수 (API 키 개수만큼, 최소 1회)
        int maxRetries = Math.max(1, apiKeyRotator.getKeyCount());
        IOException lastException = null;

        // 여러 API 키로 재시도 로직
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            String currentApiKey = getApiKey();
            if (currentApiKey.isEmpty()) {
                log.error("API 키가 비어 있습니다. 요청을 수행할 수 없습니다.");
                throw new IOException("API 키가 설정되지 않았습니다.");
            }

            RequestBody body = RequestBody.create(json, mediaType);
            Request request = new Request.Builder()
                    .url(API_BASE_URL + "/openapi/v2/text-to-3d")
                    .addHeader("Authorization", "Bearer " + currentApiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(body)
                    .build();

            try (Response response = client.newCall(request).execute()) {
                ResponseBody responseBody = response.body();
                String responseBodyString = responseBody != null ? responseBody.string() : "";
                log.info("API 응답 코드: {}", response.code());

                if (response.isSuccessful()) {
                    log.info("API 호출 성공 (시도 #{}).", attempt + 1);
                    return extractTaskId(responseBodyString);
                } else {
                    // 실패 상세 정보 로깅
                    String errorMessage = "API 요청 실패 (" + response.code() + "): " + responseBodyString;
                    log.error("시도 #{}: {}", attempt + 1, errorMessage);

                    // 401/403 오류는 API 키 문제일 가능성이 높음
                    if (response.code() == 401 || response.code() == 403) {
                        log.warn("API 키 인증 오류로 다른 키로 재시도합니다.");
                    }
                    // 429 오류는 요청 제한 초과일 가능성이 높음
                    else if (response.code() == 429) {
                        log.warn("요청 제한 초과로 다른 키로 재시도합니다.");
                    }

                    // 마지막 시도가 아니면 다음 API 키로 재시도
                    if (attempt < maxRetries - 1) {
                        log.info("남은 재시도 횟수: {}", maxRetries - attempt - 1);
                        continue;
                    }

                    // 모든 재시도 실패 시 오류 기록
                    com.febrie.util.ErrorLogger.logApiFailure(
                            "Meshy API 호출 실패 (모든 키 실패)",
                            json,
                            responseBodyString,
                            new IOException(errorMessage)
                    );
                    throw new IOException(errorMessage);
                }
            } catch (IOException e) {
                lastException = e;
                log.error("API 통신 오류 (시도 #{}): {}", attempt + 1, e.getMessage());

                // 마지막 시도가 아니면 다음 API 키로 재시도
                if (attempt < maxRetries - 1) {
                    log.info("통신 오류로 다른 API 키로 재시도합니다. 남은 재시도: {}", maxRetries - attempt - 1);
                    continue;
                }
            }
        }

        // 모든 재시도 실패
        String errorMsg = "모든 API 키로 시도했으나 요청에 실패했습니다 (" + maxRetries + "회 시도).";
        log.error(errorMsg);
        com.febrie.util.ErrorLogger.logApiFailure(
                "Meshy API 통신 오류 (모든 키 실패)",
                json,
                "응답 없음 (통신 오류)",
                lastException != null ? lastException : new IOException(errorMsg)
        );
        throw lastException != null ? lastException : new IOException(errorMsg);
    }

    /**
     * 응답에서 태스크 ID 추출
     */
    private String extractTaskId(String jsonResponse) {
        if (jsonResponse == null || jsonResponse.isEmpty()) {
            log.error("응답이 비어있거나 null입니다.");
            throw new IllegalStateException("API 응답이 비어있습니다.");
        }

        JsonObject responseObj = gson.fromJson(jsonResponse, JsonObject.class);
        if (responseObj == null) {
            log.error("JSON 파싱 결과가 null입니다. 응답: {}", jsonResponse);
            throw new IllegalStateException("JSON 파싱 실패");
        }

        // "result" 필드에서 태스크 ID 추출 시도
        if (responseObj.has("result")) {
            if (responseObj.get("result").isJsonPrimitive()) {
                return responseObj.get("result").getAsString();
            } else if (responseObj.get("result").isJsonObject()) {
                // 일부 API가 result 내부에 id 필드로 반환하는 경우
                JsonObject resultObj = responseObj.get("result").getAsJsonObject();
                if (resultObj.has("id")) {
                    return resultObj.get("id").getAsString();
                }
            }
        }

        // "id" 필드 직접 확인
        if (responseObj.has("id")) {
            return responseObj.get("id").getAsString();
        }

        // 예외 발생 전 응답 내용 로깅
        log.error("응답에서 태스크 ID를 찾을 수 없습니다. 응답 내용: {}", jsonResponse);
        throw new IllegalStateException("응답에서 태스크 ID를 찾을 수 없습니다.");
    }

    /**
     * 태스크 상태 조회
     */
    public TaskStatus getTaskStatus(String taskId) throws IOException {
        // 최대 재시도 횟수 (API 키 개수만큼, 최소 1회)
        int maxRetries = Math.max(1, apiKeyRotator.getKeyCount());
        IOException lastException = null;

        // 여러 API 키로 재시도 로직
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            String currentApiKey = getApiKey();
            if (currentApiKey.isEmpty()) {
                log.error("API 키가 비어 있습니다. 상태 요청을 수행할 수 없습니다.");
                throw new IOException("API 키가 설정되지 않았습니다.");
            }

            Request request = new Request.Builder()
                    .url(API_BASE_URL + "/openapi/v2/text-to-3d/" + taskId)
                    .addHeader("Authorization", "Bearer " + currentApiKey)
                    .get()
                    .build();

            try (Response response = client.newCall(request).execute()) {
                ResponseBody responseBodyObj = response.body();
                String responseBody = responseBodyObj != null ? responseBodyObj.string() : "";

                if (response.isSuccessful()) {
                    log.debug("태스크 상태 확인 성공 (시도 #{}).", attempt + 1);
                    return parseTaskStatus(responseBody);
                } else {
                    log.error("시도 #{}: 태스크 상태 확인 실패 - 코드: {}, 응답: {}", 
                           attempt + 1, response.code(), responseBody);

                    // 401/403 오류는 API 키 문제일 가능성이 높음
                    if (response.code() == 401 || response.code() == 403) {
                        log.warn("API 키 인증 오류로 다른 키로 재시도합니다.");
                        // 다음 시도로 넘어감
                        if (attempt < maxRetries - 1) continue;
                    }

                    // 마지막 시도이거나 재시도 의미 없는 오류인 경우 응답 파싱 시도
                    try {
                        TaskStatus status = parseTaskStatus(responseBody);
                        // 상태 코드가 실패인데 응답이 성공 상태를 보고하면 상태를 FAILED로 변경
                        if (status != null && !"FAILED".equals(status.status)) {
                            status.status = "FAILED";
                            status.error = "API 요청 실패: " + response.code();
                        }
                        return status;
                    } catch (Exception e) {
                        // 다음 키로 재시도
                        if (attempt < maxRetries - 1) {
                            log.info("파싱 오류로 다른 API 키로 재시도합니다. 남은 재시도: {}", maxRetries - attempt - 1);
                            continue;
                        }
                        // 모든 재시도 실패 시 예외 발생
                        throw new IOException("태스크 상태 확인 실패 (" + response.code() + "): " + responseBody);
                    }
                }
            } catch (IOException e) {
                lastException = e;
                log.error("API 통신 오류 (시도 #{}): {}", attempt + 1, e.getMessage());

                // 마지막 시도가 아니면 다음 API 키로 재시도
                if (attempt < maxRetries - 1) {
                    log.info("통신 오류로 다른 API 키로 재시도합니다. 남은 재시도: {}", maxRetries - attempt - 1);
                    continue;
                }
            }
        }

        // 모든 재시도 실패
        String errorMsg = "모든 API 키로 태스크 상태 확인을 시도했으나 실패했습니다 (" + maxRetries + "회 시도).";
        log.error(errorMsg);
        throw lastException != null ? lastException : new IOException(errorMsg);
    }

    /**
     * 태스크 상태 파싱
     */
    private TaskStatus parseTaskStatus(String jsonResponse) {
        return gson.fromJson(jsonResponse, TaskStatus.class);
    }

    /**
     * API 응답을 모델 객체로 변환
     */
    @NotNull
    public MeshyTask createMeshyTask(@NotNull TaskStatus apiStatus, @NotNull MeshyTask.TaskType taskType, @NotNull String prompt) {
        return MeshyTask.builder()
                .taskId(apiStatus.id)
                .taskType(taskType)
                .status(MeshyTask.TaskStatus.fromApiStatus(apiStatus.status))
                .progress(apiStatus.progress)
                .prompt(prompt)
                .createdAt(LocalDateTime.now())
                .lastUpdatedAt(LocalDateTime.now())
                .thumbnailUrl(apiStatus.thumbnailUrl)
                .modelUrls(apiStatus.modelUrls)
                .errorMessage(apiStatus.error)
                .build();
    }

    /**
     * API 응답 모델 클래스
     */
    public static class TaskStatus {
        public String id;

        @SerializedName("model_urls")
        public ModelUrls modelUrls;

        @SerializedName("thumbnail_url")
        public String thumbnailUrl;

        public String status;
        public int progress;
        public String error;

        @SerializedName("texture_urls")
        public TextureUrl[] textureUrls;

        public static class ModelUrls {
            public String glb;
            public String fbx;
            public String obj;
            public String mtl;
            public String usdz;
        }

        public static class TextureUrl {
            @SerializedName("base_color")
            public String baseColor;

            public String metallic;
            public String normal;
            public String roughness;
        }
    }
}
