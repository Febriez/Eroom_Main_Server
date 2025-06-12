package com.febrie.eroom.handler;

import io.undertow.server.HttpServerExchange;

public interface RequestHandler {
    void handleRoot(HttpServerExchange exchange);
    void handleHealth(HttpServerExchange exchange);
    void handleQueueStatus(HttpServerExchange exchange);
    void handleRoomCreate(HttpServerExchange exchange);
    void handleRoomResult(HttpServerExchange exchange);
}