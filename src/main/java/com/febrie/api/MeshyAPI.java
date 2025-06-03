package com.febrie.api;

import com.febrie.model.MeshyTask;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;
import okhttp3.*;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.time.LocalDateTime;

public class MeshyAPI {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MeshyAPI.class);
    private static final String API_BASE_URL = "https://api.meshy.ai";
    private String apiKey;

    private String getApiKey() {
        if (apiKey == null) {
            apiKey = System.getenv("MESHY_API_KEY");

            if (apiKey == null || apiKey.isEmpty()) {
                log.error("MESHY_API_KEY 환경 변수가 설정되지 않았습니다.");
                return "";
            }
        }
        return apiKey;
    }

    private final OkHttpClient client = new OkHttpClient();
    private final Gson gson = new Gson();

    private static MeshyAPI instance;

    public static synchronized MeshyAPI getInstance() {
        if (instance == null) {
            instance = new MeshyAPI();
        }
        return instance;
    }

    private MeshyAPI() {
        String envApiKey = System.getenv("MESHY_API_KEY");
        if (envApiKey == null || envApiKey.isEmpty()) {
            log.warn("MESHY_API_KEY 환경 변수가 설정되지 않았습니다. API 호출이 실패할 수 있습니다.");
        } else {
            log.info("MESHY_API_KEY 환경 변수가 설정되었습니다.");
        }
    }

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

    public String createPreviewTask(String prompt) throws IOException {
        MediaType JSON = MediaType.get("application/json; charset=utf-8");

        String processedPrompt = preprocessText(prompt);

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

    public String createRefineTask(String previewTaskId, boolean enablePbr, String texturePrompt) throws IOException {
        MediaType JSON = MediaType.get("application/json; charset=utf-8");

        String processedTexturePrompt = preprocessText(texturePrompt);

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

    private String executeApiRequest(String json, MediaType mediaType) throws IOException {
        log.info("API 요청 JSON: {}", json);

        RequestBody body = RequestBody.create(json, mediaType);
        Request request = new Request.Builder()
                .url(API_BASE_URL + "/openapi/v2/text-to-3d")
                .addHeader("Authorization", "Bearer " + getApiKey())
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build();

        try (Response response = client.newCall(request).execute()) {

            ResponseBody responseBody = response.body();
            String responseBodyString = responseBody != null ? responseBody.string() : "";
            log.info("API 응답 코드: {}", response.code());
            log.info("API 응답 내용: {}", responseBodyString);

            if (!response.isSuccessful()) {
                throw new IOException("API 요청 실패 (" + response.code() + "): " + responseBodyString);
            }

            return extractTaskId(responseBodyString);
        }
    }

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

    public TaskStatus getTaskStatus(String taskId) throws IOException {
        Request request = new Request.Builder()
                .url(API_BASE_URL + "/openapi/v2/text-to-3d/" + taskId)
                .addHeader("Authorization", "Bearer " + getApiKey())
                .get()
                .build();

        try (Response response = client.newCall(request).execute()) {

            ResponseBody responseBodyObj = response.body();
            String responseBody = responseBodyObj != null ? responseBodyObj.string() : "";

            if (!response.isSuccessful()) {
                log.error("태스크 상태 확인 실패 - 코드: {}, 응답: {}", response.code(), responseBody);

                // 실패해도 가능한 한 응답 파싱 시도
                try {
                    TaskStatus status = parseTaskStatus(responseBody);
                    // 상태 코드가 실패인데 응답이 성공 상태를 보고하면 상태를 FAILED로 변경
                    if (status != null && !"FAILED".equals(status.status)) {
                        status.status = "FAILED";
                        status.error = "API 요청 실패: " + response.code();
                    }
                    return status;
                } catch (Exception e) {
                    // 파싱 실패 시 기본 예외 발생
                    throw new IOException("태스크 상태 확인 실패 (" + response.code() + "): " + responseBody);
                }
            }

            return parseTaskStatus(responseBody);
        }
    }

    private TaskStatus parseTaskStatus(String jsonResponse) {
        return gson.fromJson(jsonResponse, TaskStatus.class);
    }

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
