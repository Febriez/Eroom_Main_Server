package com.febrie;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import okhttp3.*;

import java.io.IOException;

public class RefineRequest {
    private static final String BASE_URL = "https://api.example.com";
    private static final String API_ENDPOINT = "/v1/refine";
    private static final String apiKey = System.getenv("API_KEY");
    private static final OkHttpClient client = new OkHttpClient();
    private static final Gson gson = new Gson();

    private String mode = "refine";
    private String preview_task_id;
    private boolean enable_pbr = false;
    private String texture_prompt;
    private String texture_image_url;
    private boolean moderation = false;

    public RefineRequest(String previewTaskId) {
        this.preview_task_id = previewTaskId;
    }

    // setter 메서드들...

    public String createRefineTask(RefineRequest request) throws IOException {
        String jsonBody = gson.toJson(request);

        RequestBody body = RequestBody.create(
                jsonBody,
                MediaType.get("application/json; charset=utf-8")
        );

        Request httpRequest = new Request.Builder()
                .url(BASE_URL + API_ENDPOINT)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build();

        try (Response response = client.newCall(httpRequest).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("API 호출 실패: " + response.code() + " " + response.message());
            }

            String responseBody = response.body().string();
            JsonObject jsonResponse = gson.fromJson(responseBody, JsonObject.class);
            return jsonResponse.get("result").getAsString();
        }
    }
}