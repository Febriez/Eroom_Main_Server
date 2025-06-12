package com.febrie.eroom.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.StatusCodes;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * API 응답 형식을 일관되게 유지하기 위한 유틸리티 클래스
 */
public class ResponseFormatter {
    private static final Logger log = LoggerFactory.getLogger(ResponseFormatter.class);
    private final Gson gson;

    public ResponseFormatter(Gson gson) {
        this.gson = gson;
    }

    /**
     * JSON 응답을 클라이언트에게 전송
     */
    public void sendJsonResponse(HttpServerExchange exchange, int statusCode, JsonObject body) {
        if (body == null) {
            exchange.setStatusCode(statusCode);
            exchange.endExchange();
            return;
        }

        if (!exchange.isResponseStarted()) {
            exchange.setStatusCode(statusCode);
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
            exchange.getResponseSender().send(gson.toJson(body));
        }
    }

    /**
     * 성공 응답 전송
     */
    public void sendSuccessResponse(HttpServerExchange exchange, @NotNull JsonObject data) {
        if (!data.has("success")) {
            data.addProperty("success", true);
        }
        sendJsonResponse(exchange, StatusCodes.OK, data);
    }

    /**
     * 성공 응답 전송 (커스텀 상태 코드)
     */
    public void sendSuccessResponse(HttpServerExchange exchange, int statusCode, @NotNull JsonObject data) {
        if (!data.has("success")) {
            data.addProperty("success", true);
        }
        sendJsonResponse(exchange, statusCode, data);
    }

    /**
     * 단순 메시지 기반 성공 응답 전송
     */
    public void sendSuccessMessage(HttpServerExchange exchange, String message) {
        JsonObject response = new JsonObject();
        response.addProperty("success", true);
        response.addProperty("message", message);
        sendJsonResponse(exchange, StatusCodes.OK, response);
    }

    /**
     * 에러 응답 전송
     */
    public void sendErrorResponse(HttpServerExchange exchange, int statusCode, String errorMessage) {
        JsonObject errorResponse = new JsonObject();
        errorResponse.addProperty("success", false);
        errorResponse.addProperty("error", errorMessage);
        errorResponse.addProperty("timestamp", String.valueOf(System.currentTimeMillis()));
        sendJsonResponse(exchange, statusCode, errorResponse);
    }

    /**
     * 에러 응답 전송 (로깅 포함)
     */
    public void sendErrorResponse(HttpServerExchange exchange, int statusCode, String errorMessage, Exception e) {
        log.error(errorMessage, e);
        sendErrorResponse(exchange, statusCode, errorMessage);
    }

    /**
     * 에러 응답 전송 (로깅 포함, 상세 메시지 노출 여부 선택)
     */
    public void sendErrorResponse(HttpServerExchange exchange, int statusCode, String errorMessage,
                                  Exception e, boolean includeExceptionMessage) {
        log.error(errorMessage, e);
        String finalMessage = includeExceptionMessage && e != null
                ? errorMessage + ": " + e.getMessage()
                : errorMessage;
        sendErrorResponse(exchange, statusCode, finalMessage);
    }

    /**
     * 쿼리 파라미터 추출 유틸리티
     */
    public Optional<String> getQueryParam(@NotNull HttpServerExchange exchange, String paramName) {
        return Optional.ofNullable(exchange.getQueryParameters().get(paramName))
                .map(deque -> deque.isEmpty() ? null : deque.getFirst())
                .filter(value -> !value.trim().isEmpty());
    }
}