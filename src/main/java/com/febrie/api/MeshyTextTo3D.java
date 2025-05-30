package com.febrie.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;
import okhttp3.*;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public class MeshyTextTo3D {
    private static final String API_BASE_URL = "https://api.meshy.ai";
    private static final String API_KEY = "msy_HAjvVphXhqQ37r0x4NMHPKkb8N69TjBe7MYT";
    private final OkHttpClient client = new OkHttpClient();
    private final Gson gson = new Gson();

    /**
     * 문자열을 JSON에 안전하게 사용할 수 있도록 전처리합니다.
     * 특수 문자를 이스케이프 처리하고 줄바꿈 등을 제거합니다.
     *
     * @param input 전처리할 문자열
     * @return 전처리된 문자열
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

    public String createPreviewTask(String prompt, String artStyle) throws IOException {
        MediaType JSON = MediaType.get("application/json; charset=utf-8");

        // 공통 전처리 메소드 사용
        String processedPrompt = preprocessText(prompt);
        String processedArtStyle = preprocessText(artStyle);

        String json = String.format("""
                {
                    "mode": "preview",
                    "prompt": "%s",
                    "art_style": "%s",
                    "ai_model": "meshy-4",
                    "topology": "triangle",
                    "target_polycount": 5000,
                    "should_remesh": true,
                    "symmetry_mode": "off",
                    "moderation": false
                }
                """, processedPrompt, processedArtStyle);

        return executeApiRequest(json, JSON);
    }

    public String createRefineTask(String previewTaskId, boolean enablePbr,
                                   String texturePrompt) throws IOException {
        MediaType JSON = MediaType.get("application/json; charset=utf-8");

        // 공통 전처리 메소드 사용
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
        System.out.println("[DEBUG] API 요청 JSON: " + json);

        RequestBody body = RequestBody.create(json, mediaType);
        Request request = new Request.Builder()
                .url(API_BASE_URL + "/openapi/v2/text-to-3d")
                .addHeader("Authorization", "Bearer " + API_KEY)
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build();

        try (Response response = client.newCall(request).execute()) {
            ResponseBody responseBody = response.body();
            String responseBodyString = responseBody != null ? responseBody.string() : "";
            System.out.println("[DEBUG] API 응답 코드: " + response.code());
            System.out.println("[DEBUG] API 응답 내용: " + responseBodyString);

            if (!response.isSuccessful()) {
                throw new IOException("API 요청 실패 (" + response.code() + "): " + responseBodyString);
            }

            return extractTaskId(responseBodyString);
        }
    }

    private String extractTaskId(String jsonResponse) {
        JsonObject responseObj = gson.fromJson(jsonResponse, JsonObject.class);

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
        System.err.println("[ERROR] 응답에서 태스크 ID를 찾을 수 없습니다. 응답 내용: " + jsonResponse);
        throw new IllegalStateException("응답에서 태스크 ID를 찾을 수 없습니다.");
    }

    public TaskStatus getTaskStatus(String taskId) throws IOException {
        Request request = new Request.Builder()
                .url(API_BASE_URL + "/openapi/v2/text-to-3d/" + taskId)
                .addHeader("Authorization", "Bearer " + API_KEY)
                .get()
                .build();

        try (Response response = client.newCall(request).execute()) {
            ResponseBody responseBodyObj = response.body();
            String responseBody = responseBodyObj != null ? responseBodyObj.string() : "";

            if (!response.isSuccessful()) {
                System.err.println("[ERROR] 태스크 상태 확인 실패 - 코드: " + response.code() + ", 응답: " + responseBody);

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

    public static class TaskStatus {
        public String id;

        @SerializedName("model_urls")
        public ModelUrls modelUrls;

        @SerializedName("thumbnail_url")
        public String thumbnailUrl;

        // 나머지 필드들...
        public String status; // PENDING, IN_PROGRESS, SUCCEEDED, FAILED, CANCELED
        public int progress;
        public String error; // 오류 메시지 (FAILED 상태일 때)

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

    public void streamTaskProgress(String taskId, TaskProgressCallback callback) {
        Request request = buildStreamRequest(taskId);
        client.newCall(request).enqueue(new TaskStreamCallback(callback));
    }

    @NotNull
    private Request buildStreamRequest(String taskId) {
        return new Request.Builder()
                .url(API_BASE_URL + "/openapi/v2/text-to-3d/" + taskId + "/stream")
                .addHeader("Authorization", "Bearer " + API_KEY)
                .get()
                .build();
    }

    private class TaskStreamCallback implements Callback {
        private final TaskProgressCallback userCallback;

        public TaskStreamCallback(TaskProgressCallback userCallback) {
            this.userCallback = userCallback;
        }

        @Override
        public void onFailure(@NotNull Call call, @NotNull IOException e) {
            userCallback.onError(e);
        }

        @Override
        public void onResponse(@NotNull Call call, @NotNull Response response) throws IOException {
            if (!response.isSuccessful()) {
                userCallback.onError(new IOException("Unexpected code " + response));
                return;
            }

            processStreamResponse(response);
        }

        private void processStreamResponse(@NotNull Response response) throws IOException {
            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                userCallback.onError(new IOException("응답 본문이 null입니다."));
                return;
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(responseBody.byteStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    processStreamLine(line);
                }
            }
        }

        private void processStreamLine(@NotNull String line) {
            if (line.startsWith("data: ")) {
                String data = line.substring(6);
                TaskStatus status = parseTaskStatus(data);
                userCallback.onProgress(status);

                if (isTaskCompleted(status)) {
                    // 작업이 완료되었으므로 스트림 처리 중단
                    return;
                }
            }
        }

        private boolean isTaskCompleted(@NotNull TaskStatus status) {
            return "SUCCEEDED".equals(status.status) || "FAILED".equals(status.status);
        }
    }

    public interface TaskProgressCallback {
        void onProgress(TaskStatus status);

        void onError(Exception e);
    }


}