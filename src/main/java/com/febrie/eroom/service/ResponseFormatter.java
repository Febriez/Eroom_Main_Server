package com.febrie.eroom.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;

public class ResponseFormatter {

    private final Gson gson;

    public ResponseFormatter(Gson gson) {
        this.gson = gson;
    }

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

    public void sendErrorResponse(HttpServerExchange exchange, int statusCode, String errorMessage) {
        JsonObject errorResponse = new JsonObject();
        errorResponse.addProperty("success", false);
        errorResponse.addProperty("error", errorMessage);
        sendJsonResponse(exchange, statusCode, errorResponse);
    }
}
