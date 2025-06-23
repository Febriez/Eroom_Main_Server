package com.febrie.eroom.handler;

import io.undertow.server.HttpServerExchange;

/**
 * HTTP 요청을 처리하는 핸들러 인터페이스
 */
public interface RequestHandler {

    /**
     * 루트 경로 요청을 처리합니다.
     */
    void handleRoot(HttpServerExchange exchange);

    /**
     * 헬스체크 요청을 처리합니다.
     */
    void handleHealth(HttpServerExchange exchange);

    /**
     * 큐 상태 조회 요청을 처리합니다.
     */
    void handleQueueStatus(HttpServerExchange exchange);

    /**
     * 방 생성 요청을 처리합니다.
     */
    void handleRoomCreate(HttpServerExchange exchange);

    /**
     * 방 생성 결과 조회 요청을 처리합니다.
     */
    void handleRoomResult(HttpServerExchange exchange);
}