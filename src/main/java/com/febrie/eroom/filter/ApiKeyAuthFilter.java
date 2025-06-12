package com.febrie.eroom.filter;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.StatusCodes;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ApiKeyAuthFilter implements HttpHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);
    private static final String AUTH_HEADER = "Authorization";
    private final String validApiKey;
    private final HttpHandler next;

    public ApiKeyAuthFilter(HttpHandler next, String validApiKey) {
        this.next = next;
        this.validApiKey = validApiKey;
        log.info("ApiKeyAuthFilter가 초기화되었습니다. API 키가 설정되었습니다.");
    }

    @Override
    public void handleRequest(@NotNull HttpServerExchange exchange) throws Exception {

        String authHeader = exchange.getRequestHeaders().getFirst(AUTH_HEADER);

        if (authHeader == null || authHeader.isEmpty()) {
            log.warn("Authorization 헤더가 요청에 없습니다: {}", exchange.getRequestPath());
            sendUnauthorizedResponse(exchange, "인증이 필요합니다");
            return;
        }

        if (!validApiKey.equals(authHeader)) {
            log.warn("잘못된 API 키가 제공되었습니다: {}", exchange.getRequestPath());
            sendUnauthorizedResponse(exchange, "인증 실패");
            return;
        }

        // API 키가 유효하면 요청을 다음 핸들러로 전달
        next.handleRequest(exchange);
    }

    private void sendUnauthorizedResponse(@NotNull HttpServerExchange exchange, String message) {
        exchange.setStatusCode(StatusCodes.UNAUTHORIZED);
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        exchange.getResponseSender().send("{\"error\":\"" + message + "\"}");
    }
}
