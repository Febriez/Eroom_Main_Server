package com.febrie.eroom.handler;

import com.febrie.eroom.model.RoomCreationRequest;
import com.febrie.eroom.service.JobResultStore;
import com.febrie.eroom.service.RoomRequestQueueManager;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.StatusCodes;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Deque;
import java.util.Optional;

public class ApiHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiHandler.class);
    private final Gson gson;
    private final RoomRequestQueueManager queueManager;
    private final JobResultStore resultStore;

    public ApiHandler(Gson gson, RoomRequestQueueManager queueManager, JobResultStore resultStore) {
        this.gson = gson;
        this.queueManager = queueManager;
        this.resultStore = resultStore;
    }

    public void handleRoot(@NotNull HttpServerExchange exchange) {
        JsonObject response = new JsonObject();
        response.addProperty("status", "online");
        response.addProperty("message", "Eroom 서버가 작동 중입니다");
        sendJsonResponse(exchange, StatusCodes.OK, response);
    }

    public void handleHealth(@NotNull HttpServerExchange exchange) {
        JsonObject response = new JsonObject();
        response.addProperty("status", "healthy");

        RoomRequestQueueManager.QueueStatus queueStatus = queueManager.getQueueStatus();
        JsonObject queue = new JsonObject();
        queue.addProperty("queued", queueStatus.queued());
        queue.addProperty("active", queueStatus.active());
        queue.addProperty("completed", queueStatus.completed());
        queue.addProperty("maxConcurrent", queueStatus.maxConcurrent());
        response.add("queue", queue);

        sendJsonResponse(exchange, StatusCodes.OK, response);
    }

    public void handleQueueStatus(@NotNull HttpServerExchange exchange) {
        RoomRequestQueueManager.QueueStatus status = queueManager.getQueueStatus();
        JsonObject response = new JsonObject();
        response.addProperty("queued", status.queued());
        response.addProperty("active", status.active());
        response.addProperty("completed", status.completed());
        response.addProperty("maxConcurrent", status.maxConcurrent());
        sendJsonResponse(exchange, StatusCodes.OK, response);
    }

    /**
     * 방 생성 요청을 큐에 넣고 서버에서 생성한 ruid를 즉시 반환합니다.
     */
    public void handleRoomCreate(@NotNull HttpServerExchange exchange) {
        exchange.getRequestReceiver().receiveFullString((httpServerExchange, message) -> {
            try {
                RoomCreationRequest request = gson.fromJson(message, RoomCreationRequest.class);
                if (request == null || request.getUuid() == null || request.getUuid().trim().isEmpty()) {
                    sendErrorResponse(httpServerExchange, StatusCodes.BAD_REQUEST, "Invalid request body or missing 'uuid' (userId).");
                    return;
                }

                String ruid = queueManager.submitRequest(request);

                JsonObject response = new JsonObject();
                response.addProperty("ruid", ruid);
                response.addProperty("status", "Queued");
                response.addProperty("message", "Room creation request has been accepted. Poll /room/result?ruid=" + ruid + " for status.");

                sendJsonResponse(httpServerExchange, StatusCodes.ACCEPTED, response);

            } catch (JsonSyntaxException e) {
                sendErrorResponse(httpServerExchange, StatusCodes.BAD_REQUEST, "Failed to parse JSON request body.");
            } catch (Exception e) {
                log.error("Error submitting request to queue", e);
                sendErrorResponse(httpServerExchange, StatusCodes.INTERNAL_SERVER_ERROR, "Failed to queue request: " + e.getMessage());
            }
        }, (httpServerExchange, e) -> {
            log.error("Failed to read request body", e);
            sendErrorResponse(httpServerExchange, StatusCodes.INTERNAL_SERVER_ERROR, "Failed to read request body.");
        });
    }

    /**
     * ruid를 사용하여 방 생성 결과를 조회하고, 결과를 반환 후 삭제합니다.
     */
    public void handleRoomResult(@NotNull HttpServerExchange exchange) {
        String ruid = Optional.ofNullable(exchange.getQueryParameters().get("ruid"))
                .map(Deque::peekFirst)
                .orElse(null);

        if (ruid == null || ruid.trim().isEmpty()) {
            sendErrorResponse(exchange, StatusCodes.BAD_REQUEST, "Query parameter 'ruid' is required.");
            return;
        }

        Optional<JobResultStore.JobState> jobStateOptional = resultStore.getJobState(ruid);

        if (jobStateOptional.isEmpty()) {
            sendErrorResponse(exchange, StatusCodes.NOT_FOUND, "Job with ruid '" + ruid + "' not found. It may have been already claimed or never existed.");
            return;
        }

        JobResultStore.JobState jobState = jobStateOptional.get();

        switch (jobState.status()) {
            case QUEUED, PROCESSING -> {
                JsonObject statusResponse = new JsonObject();
                statusResponse.addProperty("ruid", ruid);
                statusResponse.addProperty("status", jobState.status().name());
                sendJsonResponse(exchange, StatusCodes.OK, statusResponse);
            }
            case COMPLETED -> {
                sendJsonResponse(exchange, StatusCodes.OK, jobState.result());
                resultStore.deleteJob(ruid);
                log.info("Result for ruid '{}' delivered and deleted.", ruid);
            }
            case FAILED -> {
                sendJsonResponse(exchange, StatusCodes.OK, jobState.result());
                resultStore.deleteJob(ruid);
                log.warn("Failed result for ruid '{}' delivered and deleted.", ruid);
            }
        }
    }

    private void sendJsonResponse(HttpServerExchange exchange, int statusCode, JsonObject body) {
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

    private void sendErrorResponse(HttpServerExchange exchange, int statusCode, String errorMessage) {
        JsonObject errorResponse = new JsonObject();
        errorResponse.addProperty("success", false);
        errorResponse.addProperty("error", errorMessage);
        sendJsonResponse(exchange, statusCode, errorResponse);
    }
}