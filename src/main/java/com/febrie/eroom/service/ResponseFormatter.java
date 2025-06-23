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
    private static final String CONTENT_TYPE_JSON = "application/json; charset=utf-8";
    private static final String SUCCESS_KEY = "success";
    private static final String ERROR_KEY = "error";
    private static final String MESSAGE_KEY = "message";
    private static final String TIMESTAMP_KEY = "timestamp";

    private final Gson gson;

    /**
     * ResponseFormatter 생성자
     */
    public ResponseFormatter(Gson gson) {
        this.gson = gson;
    }

    /**
     * JSON 응답을 클라이언트에게 전송합니다.
     */
    public void sendJsonResponse(HttpServerExchange exchange, int statusCode, JsonObject body) {
        if (body == null) {
            sendEmptyResponse(exchange, statusCode);
            return;
        }

        if (!exchange.isResponseStarted()) {
            prepareJsonResponse(exchange, statusCode);
            sendResponseBody(exchange, body);
        }
    }

    /**
     * 빈 응답을 전송합니다.
     */
    private void sendEmptyResponse(HttpServerExchange exchange, int statusCode) {
        exchange.setStatusCode(statusCode);
        exchange.endExchange();
    }

    /**
     * JSON 응답 헤더를 준비합니다.
     */
    private void prepareJsonResponse(HttpServerExchange exchange, int statusCode) {
        exchange.setStatusCode(statusCode);
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, CONTENT_TYPE_JSON);
    }

    /**
     * 응답 본문을 전송합니다.
     */
    private void sendResponseBody(HttpServerExchange exchange, JsonObject body) {
        exchange.getResponseSender().send(gson.toJson(body));
    }

    /**
     * 성공 응답을 전송합니다.
     */
    public void sendSuccessResponse(HttpServerExchange exchange, @NotNull JsonObject data) {
        ensureSuccessFlag(data);
        sendJsonResponse(exchange, StatusCodes.OK, data);
    }

    /**
     * 커스텀 상태 코드와 함께 성공 응답을 전송합니다.
     */
    public void sendSuccessResponse(HttpServerExchange exchange, int statusCode, @NotNull JsonObject data) {
        ensureSuccessFlag(data);
        sendJsonResponse(exchange, statusCode, data);
    }

    /**
     * 성공 플래그를 확인하고 추가합니다.
     */
    private void ensureSuccessFlag(JsonObject data) {
        if (!data.has(SUCCESS_KEY)) {
            data.addProperty(SUCCESS_KEY, true);
        }
    }

    /**
     * 단순 메시지 기반 성공 응답을 전송합니다.
     */
    public void sendSuccessMessage(HttpServerExchange exchange, String message) {
        JsonObject response = createSuccessMessage(message);
        sendJsonResponse(exchange, StatusCodes.OK, response);
    }

    /**
     * 성공 메시지 객체를 생성합니다.
     */
    private JsonObject createSuccessMessage(String message) {
        JsonObject response = new JsonObject();
        response.addProperty(SUCCESS_KEY, true);
        response.addProperty(MESSAGE_KEY, message);
        return response;
    }

    /**
     * 에러 응답을 전송합니다.
     */
    public void sendErrorResponse(HttpServerExchange exchange, int statusCode, String errorMessage) {
        JsonObject errorResponse = createErrorResponse(errorMessage);
        sendJsonResponse(exchange, statusCode, errorResponse);
    }

    /**
     * 에러 응답 객체를 생성합니다.
     */
    private JsonObject createErrorResponse(String errorMessage) {
        JsonObject errorResponse = new JsonObject();
        errorResponse.addProperty(SUCCESS_KEY, false);
        errorResponse.addProperty(ERROR_KEY, errorMessage);
        errorResponse.addProperty(TIMESTAMP_KEY, String.valueOf(System.currentTimeMillis()));
        return errorResponse;
    }

    /**
     * 로깅과 함께 에러 응답을 전송합니다.
     */
    public void sendErrorResponse(HttpServerExchange exchange, int statusCode, String errorMessage, Exception e) {
        log.error(errorMessage, e);
        sendErrorResponse(exchange, statusCode, errorMessage);
    }

    /**
     * 로깅과 상세 메시지 노출 옵션과 함께 에러 응답을 전송합니다.
     */
    public void sendErrorResponse(HttpServerExchange exchange, int statusCode, String errorMessage,
                                  Exception e, boolean includeExceptionMessage) {
        log.error(errorMessage, e);
        String finalMessage = buildFinalErrorMessage(errorMessage, e, includeExceptionMessage);
        sendErrorResponse(exchange, statusCode, finalMessage);
    }

    /**
     * 최종 에러 메시지를 빌드합니다.
     */
    private String buildFinalErrorMessage(String errorMessage, Exception e, boolean includeExceptionMessage) {
        if (includeExceptionMessage && e != null) {
            return errorMessage + ": " + e.getMessage();
        }
        return errorMessage;
    }

    /**
     * HTTP Exchange에서 쿼리 파라미터를 추출합니다.
     */
    public Optional<String> getQueryParam(@NotNull HttpServerExchange exchange, String paramName) {
        return Optional.ofNullable(exchange.getQueryParameters().get(paramName))
                .map(deque -> deque.isEmpty() ? null : deque.getFirst())
                .filter(value -> !value.trim().isEmpty());
    }
}