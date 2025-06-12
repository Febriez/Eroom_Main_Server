package com.febrie.eroom.handler;

import com.febrie.eroom.model.RoomCreationRequest;
import com.febrie.eroom.service.JobResultStore;
import com.febrie.eroom.service.ResponseFormatter;
import com.febrie.eroom.service.RoomRequestQueueManager;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.StatusCodes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Deque;
import java.util.Optional;

public class ApiHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiHandler.class);
    private final Gson gson;
    private final RoomRequestQueueManager queueManager;
    private final JobResultStore resultStore;
    private final ResponseFormatter responseFormatter;

    public ApiHandler(Gson gson, RoomRequestQueueManager queueManager, JobResultStore resultStore) {
        this.gson = gson;
        this.queueManager = queueManager;
        this.resultStore = resultStore;
        this.responseFormatter = new ResponseFormatter(gson);
    }

    public void handleRoot(@NotNull HttpServerExchange exchange) {
        JsonObject response = new JsonObject();
        response.addProperty("status", "online");
        response.addProperty("message", "Eroom 서버가 작동 중입니다");
        responseFormatter.sendJsonResponse(exchange, StatusCodes.OK, response);
    }

    public void handleHealth(@NotNull HttpServerExchange exchange) {
        JsonObject response = new JsonObject();
        response.addProperty("status", "healthy");
        response.add("queue", formatQueueStatus(queueManager.getQueueStatus()));
        responseFormatter.sendJsonResponse(exchange, StatusCodes.OK, response);
    }

    @NotNull
    private JsonObject formatQueueStatus(@NotNull RoomRequestQueueManager.QueueStatus status) {
        JsonObject queue = new JsonObject();
        queue.addProperty("queued", status.queued());
        queue.addProperty("active", status.active());
        queue.addProperty("completed", status.completed());
        queue.addProperty("maxConcurrent", status.maxConcurrent());
        return queue;
    }

    public void handleQueueStatus(@NotNull HttpServerExchange exchange) {
        responseFormatter.sendJsonResponse(exchange, StatusCodes.OK, formatQueueStatus(queueManager.getQueueStatus()));
    }

    public void handleRoomCreate(@NotNull HttpServerExchange exchange) {
        exchange.getRequestReceiver().receiveFullString((httpServerExchange, message) -> {
            try {
                RoomCreationRequest request = gson.fromJson(message, RoomCreationRequest.class);
                if (isInvalidRequest(request)) {
                    responseFormatter.sendErrorResponse(httpServerExchange, StatusCodes.BAD_REQUEST, "유효하지 않은 요청 본문 또는 'uuid' (userId)가 누락되었습니다.");
                    return;
                }

                String ruid = queueManager.submitRequest(request);
                JsonObject response = createRoomCreationResponse(ruid);
                responseFormatter.sendJsonResponse(httpServerExchange, StatusCodes.ACCEPTED, response);

            } catch (JsonSyntaxException e) {
                responseFormatter.sendErrorResponse(httpServerExchange, StatusCodes.BAD_REQUEST, "JSON 요청 본문 파싱에 실패했습니다.");
            } catch (Exception e) {
                log.error("큐에 요청 제출 중 오류 발생", e);
                responseFormatter.sendErrorResponse(httpServerExchange, StatusCodes.INTERNAL_SERVER_ERROR, "요청 큐 등록 실패: " + e.getMessage());
            }
        }, (httpServerExchange, e) -> {
            log.error("요청 본문 읽기 실패", e);
            responseFormatter.sendErrorResponse(httpServerExchange, StatusCodes.INTERNAL_SERVER_ERROR, "요청 본문을 읽는데 실패했습니다.");
        });
    }

    private boolean isInvalidRequest(RoomCreationRequest request) {
        return request == null || request.getUuid() == null || request.getUuid().trim().isEmpty();
    }

    @NotNull
    private JsonObject createRoomCreationResponse(String ruid) {
        JsonObject response = new JsonObject();
        response.addProperty("ruid", ruid);
        response.addProperty("status", "대기중");
        response.addProperty("message", "방 생성 요청이 수락되었습니다. 상태 확인을 위해 /room/result?ruid=" + ruid + "를 폴링하세요.");
        return response;
    }

    public void handleRoomResult(@NotNull HttpServerExchange exchange) {
        String ruid = extractRuidFromQuery(exchange);
        if (ruid == null) {
            responseFormatter.sendErrorResponse(exchange, StatusCodes.BAD_REQUEST, "쿼리 파라미터 'ruid'가 필요합니다.");
            return;
        }

        Optional<JobResultStore.JobState> jobStateOptional = resultStore.getJobState(ruid);
        if (jobStateOptional.isEmpty()) {
            responseFormatter.sendErrorResponse(exchange, StatusCodes.NOT_FOUND,
                    "ruid '" + ruid + "'에 해당하는 작업을 찾을 수 없습니다. 이미 처리되었거나 존재하지 않는 작업입니다.");
            return;
        }

        processJobState(exchange, ruid, jobStateOptional.get());
    }

    @Nullable
    private String extractRuidFromQuery(@NotNull HttpServerExchange exchange) {
        String ruid = Optional.ofNullable(exchange.getQueryParameters().get("ruid"))
                .map(Deque::peekFirst)
                .orElse(null);

        return (ruid == null || ruid.trim().isEmpty()) ? null : ruid;
    }

    private void processJobState(HttpServerExchange exchange, String ruid, @NotNull JobResultStore.JobState jobState) {
        switch (jobState.status()) {
            case QUEUED, PROCESSING -> handleInProgressJob(exchange, ruid, jobState);
            case COMPLETED -> handleCompletedJob(exchange, ruid, jobState, false);
            case FAILED -> handleCompletedJob(exchange, ruid, jobState, true);
        }
    }

    private void handleInProgressJob(HttpServerExchange exchange, String ruid, @NotNull JobResultStore.JobState jobState) {
        JsonObject statusResponse = new JsonObject();
        statusResponse.addProperty("ruid", ruid);
        statusResponse.addProperty("status", jobState.status().name());
        responseFormatter.sendJsonResponse(exchange, StatusCodes.OK, statusResponse);
    }

    private void handleCompletedJob(HttpServerExchange exchange, String ruid, @NotNull JobResultStore.JobState jobState, boolean isFailed) {
        responseFormatter.sendJsonResponse(exchange, StatusCodes.OK, jobState.result());
        resultStore.deleteJob(ruid);
        if (isFailed) {
            log.warn("ruid '{}'에 대한 실패 결과가 전달되고 삭제되었습니다.", ruid);
        } else {
            log.info("ruid '{}'에 대한 결과가 전달되고 삭제되었습니다.", ruid);
        }
    }
}
