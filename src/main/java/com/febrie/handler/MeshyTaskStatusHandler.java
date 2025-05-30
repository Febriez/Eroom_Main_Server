package com.febrie.handler;

import com.febrie.api.MeshyTextTo3D;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import lombok.extern.slf4j.Slf4j;

/**
 * 태스크 상태 확인 HTTP 핸들러
 */
@Slf4j
public class MeshyTaskStatusHandler implements HttpHandler {

    private final Gson gson = new Gson();

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        if (exchange.isInIoThread()) {
            exchange.dispatch(this);
            return;
        }

        JsonObject responseJson = new JsonObject();

        try {
            // 쿼리 파라미터에서 taskId 추출
            String taskId = getQueryParam(exchange, "taskId");

            if (taskId == null || taskId.isEmpty()) {
                responseJson.addProperty("success", false);
                responseJson.addProperty("error", "필수 매개변수 누락: taskId가 필요합니다");
                sendResponse(exchange, responseJson, 400);
                return;
            }

            log.info("태스크 상태 확인 요청 - 태스크 ID: {}", taskId);

            // API를 통해 태스크 상태 조회
            MeshyTextTo3D meshy = new MeshyTextTo3D();
            MeshyTextTo3D.TaskStatus status = meshy.getTaskStatus(taskId);

            if (status != null) {
                responseJson.addProperty("success", true);
                responseJson.addProperty("taskId", taskId);
                responseJson.addProperty("status", status.status);
                responseJson.addProperty("progress", status.progress);

                // 성공적으로 완료된 경우 모델 URL 정보 추가
                if ("SUCCEEDED".equals(status.status) && status.modelUrls != null) {
                    JsonObject modelUrls = new JsonObject();
                    if (status.modelUrls.glb != null) modelUrls.addProperty("glb", status.modelUrls.glb);
                    if (status.modelUrls.fbx != null) modelUrls.addProperty("fbx", status.modelUrls.fbx);
                    if (status.modelUrls.obj != null) modelUrls.addProperty("obj", status.modelUrls.obj);
                    if (status.modelUrls.mtl != null) modelUrls.addProperty("mtl", status.modelUrls.mtl);
                    if (status.modelUrls.usdz != null) modelUrls.addProperty("usdz", status.modelUrls.usdz);
                    responseJson.add("modelUrls", modelUrls);

                    if (status.thumbnailUrl != null) {
                        responseJson.addProperty("thumbnailUrl", status.thumbnailUrl);
                    }
                }

                // 실패한 경우 오류 정보 추가
                if ("FAILED".equals(status.status) && status.error != null) {
                    responseJson.addProperty("error", status.error);
                }

                sendResponse(exchange, responseJson, 200);
            } else {
                responseJson.addProperty("success", false);
                responseJson.addProperty("error", "태스크 상태를 가져올 수 없습니다");
                sendResponse(exchange, responseJson, 500);
            }

        } catch (Exception e) {
            log.error("태스크 상태 확인 처리 중 오류: {}", e.getMessage(), e);
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
