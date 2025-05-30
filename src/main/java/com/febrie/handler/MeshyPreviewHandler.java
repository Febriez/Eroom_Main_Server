package com.febrie.handler;

import com.febrie.api.MeshyExample;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.stream.Collectors;

/**
 * Preview 모델 생성 HTTP 핸들러
 */
@Slf4j
public class MeshyPreviewHandler implements HttpHandler {

    private final Gson gson = new Gson();

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        if (exchange.isInIoThread()) {
            exchange.dispatch(this);
            return;
        }

        exchange.startBlocking();
        String requestBody = new BufferedReader(
                new InputStreamReader(exchange.getInputStream()))
                .lines()
                .collect(Collectors.joining("\n"));

        JsonObject responseJson = new JsonObject();

        try {
            JsonObject requestJson = gson.fromJson(requestBody, JsonObject.class);

            // 필수 매개변수 확인
            if (!requestJson.has("prompt") || !requestJson.has("artStyle")) {
                responseJson.addProperty("success", false);
                responseJson.addProperty("error", "필수 매개변수 누락: prompt, artStyle이 필요합니다");
                sendResponse(exchange, responseJson, 400);
                return;
            }

            String prompt = requestJson.get("prompt").getAsString();
            String artStyle = requestJson.get("artStyle").getAsString();

            log.info("Preview 모델 생성 요청 - 프롬프트: {}, 스타일: {}", prompt, artStyle);

            // Preview 모델 생성
            String taskId = MeshyExample.createPreviewModel(prompt, artStyle);

            if (taskId != null) {
                responseJson.addProperty("success", true);
                responseJson.addProperty("taskId", taskId);
                responseJson.addProperty("message", "Preview 모델 생성 작업이 시작되었습니다");
                sendResponse(exchange, responseJson, 200);
            } else {
                responseJson.addProperty("success", false);
                responseJson.addProperty("error", "Preview 모델 생성 실패");
                sendResponse(exchange, responseJson, 500);
            }

        } catch (Exception e) {
            log.error("Preview 모델 생성 처리 중 오류: {}", e.getMessage(), e);
            responseJson.addProperty("success", false);
            responseJson.addProperty("error", "요청 처리 중 오류가 발생했습니다: " + e.getMessage());
            sendResponse(exchange, responseJson, 500);
        }
    }

    private void sendResponse(HttpServerExchange exchange, JsonObject responseJson, int statusCode) {
        exchange.setStatusCode(statusCode);
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        exchange.getResponseSender().send(responseJson.toString());
    }
}
