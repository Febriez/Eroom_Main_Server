package com.febrie.eroom.handler;

import com.febrie.eroom.model.RoomCreationRequest;
import com.febrie.eroom.service.RoomService;
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

public class ApiHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiHandler.class);
    private final Gson gson;
    private final RoomService roomService;

    public ApiHandler(Gson gson, RoomService roomService) {
        this.gson = gson;
        this.roomService = roomService;
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

            JsonObject response = roomService.createRoom(request);
            sendResponse(exchange, 200, response.toString());

        } catch (Exception e) {
            log.error("방 생성 중 오류 발생", e);
            JsonObject errorResponse = new JsonObject();
            errorResponse.addProperty("error", e.getMessage());
            sendResponse(exchange, 500, errorResponse.toString());
        }
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
        exchange.setStatusCode(statusCode);
        exchange.getResponseSender().send(ByteBuffer.wrap(body.getBytes(StandardCharsets.UTF_8)));
    }
}
