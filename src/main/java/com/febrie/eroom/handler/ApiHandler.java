package com.febrie.eroom.handler;

import com.febrie.eroom.model.RoomCreationRequest;
import com.febrie.eroom.service.RoomRequestQueueManager;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

public class ApiHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiHandler.class);
    private final Gson gson;
    private final RoomRequestQueueManager queueManager;

    public ApiHandler(Gson gson, RoomRequestQueueManager queueManager) {
        this.gson = gson;
        this.queueManager = queueManager;
    }

    public void handleRoot(@NotNull HttpServerExchange exchange) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");

        JsonObject response = new JsonObject();
        response.addProperty("status", "online");
        response.addProperty("message", "Eroom 서버가 작동 중입니다");

        sendResponse(exchange, 200, response.toString());
    }

    public void handleHealth(@NotNull HttpServerExchange exchange) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");

        JsonObject response = new JsonObject();
        response.addProperty("status", "healthy");

        // 큐 상태 정보 추가
        RoomRequestQueueManager.QueueStatus queueStatus = queueManager.getQueueStatus();
        JsonObject queue = new JsonObject();
        queue.addProperty("queued", queueStatus.queued());
        queue.addProperty("active", queueStatus.active());
        queue.addProperty("completed", queueStatus.completed());
        queue.addProperty("maxConcurrent", queueStatus.maxConcurrent());
        response.add("queue", queue);

        sendResponse(exchange, 200, response.toString());
    }

    public void handleRoomCreate(@NotNull HttpServerExchange exchange) {
        if (exchange.isInIoThread()) {
            exchange.dispatch(this::handleRoomCreate);
            return;
        }

        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");

        try {
            String requestBody = getRequestBody(exchange);
            RoomCreationRequest request = gson.fromJson(requestBody, RoomCreationRequest.class);
            log.info("방 생성 요청 수신: {}", request);

            // 큐에 요청 제출
            CompletableFuture<JsonObject> future = queueManager.submitRequest(request);

            // 비동기로 결과 대기 및 응답 전송
            future.whenComplete((result, throwable) -> {
                if (throwable != null) {
                    log.error("방 생성 중 오류 발생", throwable);
                    JsonObject errorResponse = new JsonObject();
                    errorResponse.addProperty("error", throwable.getMessage());
                    sendResponse(exchange, 500, errorResponse.toString());
                } else {
                    sendResponse(exchange, 200, result.toString());
                }
            });

        } catch (Exception e) {
            log.error("요청 처리 중 오류 발생", e);
            JsonObject errorResponse = new JsonObject();
            errorResponse.addProperty("error", e.getMessage());
            sendResponse(exchange, 500, errorResponse.toString());
        }
    }

    /**
     * 큐 상태를 조회하는 새로운 엔드포인트
     */
    public void handleQueueStatus(@NotNull HttpServerExchange exchange) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");

        RoomRequestQueueManager.QueueStatus status = queueManager.getQueueStatus();
        JsonObject response = new JsonObject();
        response.addProperty("queued", status.queued());
        response.addProperty("active", status.active());
        response.addProperty("completed", status.completed());
        response.addProperty("maxConcurrent", status.maxConcurrent());

        sendResponse(exchange, 200, response.toString());
    }

    @NotNull
    private String getRequestBody(@NotNull HttpServerExchange exchange) throws IOException {
        exchange.startBlocking();
        try (InputStream inputStream = exchange.getInputStream()) {
            StringBuilder body = new StringBuilder();
            byte[] buffer = new byte[1024];
            int read;

            while ((read = inputStream.read(buffer)) > 0) {
                body.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
            }

            return body.toString();
        }
    }

    private void sendResponse(@NotNull HttpServerExchange exchange, int statusCode, @NotNull String body) {
        if (!exchange.isResponseStarted()) {
            exchange.setStatusCode(statusCode);
            exchange.getResponseSender().send(ByteBuffer.wrap(body.getBytes(StandardCharsets.UTF_8)));
        }
    }
}