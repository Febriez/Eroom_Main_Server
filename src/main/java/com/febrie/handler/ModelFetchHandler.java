package com.febrie.handler;

import com.febrie.service.MeshyModelService;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.StatusCodes;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.stream.Collectors;

/**
 * 생성된 3D 모델 URL을 조회하는 API 핸들러
 */
public class ModelFetchHandler implements HttpHandler {

    private static final String CONTENT_TYPE_JSON = "application/json";

    @Override
    public void handleRequest(HttpServerExchange exchange) {
        if (exchange.isInIoThread()) {
            exchange.dispatch(this);
            return;
        }

        try {
            if (!exchange.getRequestMethod().toString().equals("POST")) {
                sendMethodNotAllowed(exchange);
                return;
            }

            // 요청 본문 읽기
            String requestBody = readRequestBody(exchange);
            JsonObject requestJson = JsonParser.parseString(requestBody).getAsJsonObject();

            // PUID 추출
            if (!requestJson.has("puid")) {
                sendError(exchange, StatusCodes.BAD_REQUEST, "PUID가 제공되지 않았습니다.");
                return;
            }

            String puid = requestJson.get("puid").getAsString();

            // 모델 URL 조회
            JsonObject modelUrls = MeshyModelService.getInstance().getGeneratedModelUrls(puid);

            // 응답 전송
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, CONTENT_TYPE_JSON);
            exchange.getResponseSender().send(modelUrls.toString());

        } catch (Exception e) {
            sendError(exchange, StatusCodes.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    private String readRequestBody(HttpServerExchange exchange) throws Exception {
        exchange.startBlocking();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(exchange.getInputStream(), StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }

    private void sendMethodNotAllowed(HttpServerExchange exchange) {
        exchange.setStatusCode(StatusCodes.METHOD_NOT_ALLOWED);
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, CONTENT_TYPE_JSON);
        JsonObject response = new JsonObject();
        response.addProperty("error", "지원하지 않는 HTTP 메서드입니다.");
        exchange.getResponseSender().send(response.toString());
    }

    private void sendError(HttpServerExchange exchange, int statusCode, String message) {
        exchange.setStatusCode(statusCode);
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, CONTENT_TYPE_JSON);

        JsonObject errorResponse = new JsonObject();
        errorResponse.addProperty("error", message);
        errorResponse.addProperty("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME));

        exchange.getResponseSender().send(errorResponse.toString());
    }
}
