package com.febrie.handler;

import com.febrie.api.MeshyExample;
import com.google.gson.JsonObject;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import lombok.extern.slf4j.Slf4j;

/**
 * 중단된 태스크 확인 HTTP 핸들러
 */
@Slf4j
public class MeshyCheckInterruptedHandler implements HttpHandler {

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        if (exchange.isInIoThread()) {
            exchange.dispatch(this);
            return;
        }

        JsonObject responseJson = new JsonObject();

        try {
            // 쿼리 파라미터 추출
            String previewTaskId = getQueryParam(exchange, "previewTaskId");
            String refineTaskId = getQueryParam(exchange, "refineTaskId");

            if ((previewTaskId == null || previewTaskId.isEmpty()) && 
                (refineTaskId == null || refineTaskId.isEmpty())) {
                responseJson.addProperty("success", false);
                responseJson.addProperty("error", "필수 매개변수 누락: previewTaskId 또는 refineTaskId가 필요합니다");
                sendResponse(exchange, responseJson, 400);
                return;
            }

            log.info("중단된 태스크 확인 요청 - Preview ID: {}, Refine ID: {}", 
                     previewTaskId != null ? previewTaskId : "없음", 
                     refineTaskId != null ? refineTaskId : "없음");

            // 태스크 확인 (비동기)
            new Thread(() -> {
                MeshyExample.retrieveCompletedTaskResults(previewTaskId, refineTaskId);
            }).start();

            // 요청 접수 응답
            responseJson.addProperty("success", true);
            responseJson.addProperty("message", "중단된 태스크 확인 작업이 시작되었습니다");
            if (previewTaskId != null) responseJson.addProperty("previewTaskId", previewTaskId);
            if (refineTaskId != null) responseJson.addProperty("refineTaskId", refineTaskId);
            sendResponse(exchange, responseJson, 202); // Accepted

        } catch (Exception e) {
            log.error("중단된 태스크 확인 처리 중 오류: {}", e.getMessage(), e);
            responseJson.addProperty("success", false);
            responseJson.addProperty("error", "요청 처리 중 오류가 발생했습니다: " + e.getMessage());
            sendResponse(exchange, responseJson, 500);
        }
    }

    private String getQueryParam(HttpServerExchange exchange, String paramName) {
        if (exchange.getQueryParameters().containsKey(paramName)) {
            return exchange.getQueryParameters().get(paramName).getFirst();
        }
        return null;
    }

    private void sendResponse(HttpServerExchange exchange, JsonObject responseJson, int statusCode) {
        exchange.setStatusCode(statusCode);
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        exchange.getResponseSender().send(responseJson.toString());
    }
}
