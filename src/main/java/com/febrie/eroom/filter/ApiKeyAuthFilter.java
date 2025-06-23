package com.febrie.eroom.filter;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.StatusCodes;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * API 키 기반 인증을 처리하는 필터
 */
public class ApiKeyAuthFilter implements HttpHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);

    // 헤더 이름 상수
    private static final String AUTH_HEADER = "Authorization";

    // 오류 메시지 상수
    private static final String ERROR_AUTH_REQUIRED = "인증이 필요합니다";
    private static final String ERROR_AUTH_FAILED = "인증 실패";

    // 로그 메시지 상수
    private static final String LOG_NO_AUTH_HEADER = "Authorization 헤더가 요청에 없습니다: {}";
    private static final String LOG_INVALID_API_KEY = "잘못된 API 키가 제공되었습니다: {}";
    private static final String LOG_FILTER_INITIALIZED = "ApiKeyAuthFilter가 초기화되었습니다. API 키가 설정되었습니다.";

    // JSON 응답 포맷
    private static final String JSON_ERROR_FORMAT = "{\"error\":\"%s\"}";

    private final String validApiKey;
    private final HttpHandler next;

    /**
     * ApiKeyAuthFilter 생성자
     * API 키 인증 필터를 초기화합니다.
     */
    public ApiKeyAuthFilter(HttpHandler next, String validApiKey) {
        this.next = next;
        this.validApiKey = validApiKey;
        log.info(LOG_FILTER_INITIALIZED);
    }

    /**
     * HTTP 요청을 처리합니다.
     * API 키를 검증하고 유효한 경우 다음 핸들러로 전달합니다.
     */
    @Override
    public void handleRequest(@NotNull HttpServerExchange exchange) throws Exception {
        String authHeader = extractAuthHeader(exchange);

        if (!isAuthHeaderPresent(authHeader)) {
            logAndRejectRequest(exchange, LOG_NO_AUTH_HEADER, ERROR_AUTH_REQUIRED);
            return;
        }

        if (!isValidApiKey(authHeader)) {
            logAndRejectRequest(exchange, LOG_INVALID_API_KEY, ERROR_AUTH_FAILED);
            return;
        }

        // API 키가 유효하면 요청을 다음 핸들러로 전달
        next.handleRequest(exchange);
    }

    /**
     * Authorization 헤더를 추출합니다.
     */
    private String extractAuthHeader(@NotNull HttpServerExchange exchange) {
        return exchange.getRequestHeaders().getFirst(AUTH_HEADER);
    }

    /**
     * 인증 헤더가 존재하는지 확인합니다.
     */
    private boolean isAuthHeaderPresent(String authHeader) {
        return authHeader != null && !authHeader.isEmpty();
    }

    /**
     * API 키가 유효한지 확인합니다.
     */
    private boolean isValidApiKey(String authHeader) {
        return validApiKey.equals(authHeader);
    }

    /**
     * 요청을 로깅하고 거부합니다.
     */
    private void logAndRejectRequest(@NotNull HttpServerExchange exchange, String logMessage, String errorMessage) {
        log.warn(logMessage, exchange.getRequestPath());
        sendUnauthorizedResponse(exchange, errorMessage);
    }

    /**
     * 인증 실패 응답을 전송합니다.
     */
    private void sendUnauthorizedResponse(@NotNull HttpServerExchange exchange, String message) {
        exchange.setStatusCode(StatusCodes.UNAUTHORIZED);
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");

        String jsonResponse = String.format(JSON_ERROR_FORMAT, message);
        exchange.getResponseSender().send(jsonResponse);
    }
}