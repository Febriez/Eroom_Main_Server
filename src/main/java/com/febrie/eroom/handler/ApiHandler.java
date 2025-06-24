package com.febrie.eroom.handler;

import com.febrie.eroom.model.RoomCreationRequest;
import com.febrie.eroom.service.JobResultStore;
import com.febrie.eroom.service.ResponseFormatter;
import com.febrie.eroom.service.queue.QueueManager;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.StatusCodes;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

public class ApiHandler implements RequestHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiHandler.class);

    // 응답 메시지 상수
    private static final String MSG_SERVER_ONLINE = "Eroom 서버가 작동 중입니다";
    private static final String MSG_ROOM_CREATION_ACCEPTED = "방 생성 요청이 수락되었습니다. 상태 확인을 위해 /room/result?ruid=%s 를 폴링하세요. room: %s";

    // 오류 메시지 상수
    private static final String ERROR_INVALID_REQUEST = "유효하지 않은 요청 본문 또는 'uuid' (userId)가 누락되었습니다.";
    private static final String ERROR_JSON_PARSE = "JSON 요청 본문 파싱에 실패했습니다.";
    private static final String ERROR_QUEUE_SUBMIT = "요청 큐 등록 실패";
    private static final String ERROR_REQUEST_READ = "요청 본문을 읽는데 실패했습니다.";
    private static final String ERROR_RUID_REQUIRED = "쿼리 파라미터 'ruid'가 필요합니다.";
    private static final String ERROR_JOB_NOT_FOUND = "ruid '%s'에 해당하는 작업을 찾을 수 없습니다. 이미 처리되었거나 존재하지 않는 작업입니다.";

    // 필드 이름 상수
    private static final String FIELD_STATUS = "status";
    private static final String FIELD_MESSAGE = "message";
    private static final String FIELD_QUEUE = "queue";
    private static final String FIELD_RUID = "ruid";

    private final Gson gson;
    private final QueueManager queueManager;
    private final JobResultStore resultStore;
    private final ResponseFormatter responseFormatter;

    /**
     * ApiHandler 생성자
     * API 요청을 처리하는 핸들러를 초기화합니다.
     */
    public ApiHandler(Gson gson, QueueManager queueManager, JobResultStore resultStore) {
        this.gson = gson;
        this.queueManager = queueManager;
        this.resultStore = resultStore;
        this.responseFormatter = new ResponseFormatter(gson);
    }

    /**
     * 루트 경로 요청을 처리합니다.
     * 서버 상태를 반환합니다.
     */
    @Override
    public void handleRoot(HttpServerExchange exchange) {
        JsonObject response = createStatusResponse("online", MSG_SERVER_ONLINE);
        responseFormatter.sendSuccessResponse(exchange, response);
    }

    /**
     * 헬스체크 요청을 처리합니다.
     * 서버 상태와 큐 상태를 반환합니다.
     */
    @Override
    public void handleHealth(HttpServerExchange exchange) {
        JsonObject response = createHealthResponse();
        responseFormatter.sendSuccessResponse(exchange, response);
    }

    /**
     * 큐 상태 조회 요청을 처리합니다.
     */
    @Override
    public void handleQueueStatus(HttpServerExchange exchange) {
        JsonObject queueStatus = formatQueueStatus(queueManager.getQueueStatus());
        responseFormatter.sendJsonResponse(exchange, StatusCodes.OK, queueStatus);
    }

    /**
     * 방 생성 요청을 처리합니다.
     * 비동기로 요청 본문을 읽고 처리합니다.
     */
    @Override
    public void handleRoomCreate(@NotNull HttpServerExchange exchange) {
        exchange.getRequestReceiver().receiveFullString(
                this::processRoomCreationRequest,
                this::handleRequestReadError
        );
    }

    /**
     * 방 생성 결과 조회 요청을 처리합니다.
     */
    @Override
    public void handleRoomResult(HttpServerExchange exchange) {
        String ruid = extractRuidFromQuery(exchange);
        if (ruid == null) {
            responseFormatter.sendErrorResponse(exchange, StatusCodes.BAD_REQUEST, ERROR_RUID_REQUIRED);
            return;
        }

        processResultQuery(exchange, ruid);
    }

    /**
     * 상태 응답을 생성합니다.
     */
    @NotNull
    private JsonObject createStatusResponse(String status, String message) {
        JsonObject response = new JsonObject();
        response.addProperty(FIELD_STATUS, status);
        response.addProperty(FIELD_MESSAGE, message);
        return response;
    }

    /**
     * 헬스 응답을 생성합니다.
     */
    @NotNull
    private JsonObject createHealthResponse() {
        JsonObject response = new JsonObject();
        response.addProperty(FIELD_STATUS, "healthy");
        response.add(FIELD_QUEUE, formatQueueStatus(queueManager.getQueueStatus()));
        return response;
    }

    /**
     * 큐 상태를 포맷팅합니다.
     */
    @NotNull
    private JsonObject formatQueueStatus(@NotNull QueueManager.QueueStatus status) {
        JsonObject queue = new JsonObject();
        queue.addProperty("queued", status.queued());
        queue.addProperty("active", status.active());
        queue.addProperty("completed", status.completed());
        queue.addProperty("maxConcurrent", status.maxConcurrent());
        return queue;
    }

    /**
     * 방 생성 요청을 처리합니다.
     */
    private void processRoomCreationRequest(HttpServerExchange exchange, String message) {
        try {
            RoomCreationRequest request = parseRoomCreationRequest(message);

            if (isInvalidRequest(request)) {
                responseFormatter.sendErrorResponse(exchange, StatusCodes.BAD_REQUEST, ERROR_INVALID_REQUEST);
                return;
            }

            submitRequestToQueue(exchange, request);

        } catch (JsonSyntaxException e) {
            responseFormatter.sendErrorResponse(exchange, StatusCodes.BAD_REQUEST, ERROR_JSON_PARSE);
        } catch (Exception e) {
            responseFormatter.sendErrorResponse(exchange, StatusCodes.INTERNAL_SERVER_ERROR,
                    ERROR_QUEUE_SUBMIT, e, true);
        }
    }

    /**
     * 방 생성 요청을 파싱합니다.
     */
    private RoomCreationRequest parseRoomCreationRequest(String message) {
        return gson.fromJson(message, RoomCreationRequest.class);
    }

    /**
     * 요청이 유효하지 않은지 확인합니다.
     */
    private boolean isInvalidRequest(RoomCreationRequest request) {
        return request == null || request.getUuid() == null || request.getUuid().trim().isEmpty();
    }

    /**
     * 요청을 큐에 제출합니다.
     */
    private void submitRequestToQueue(HttpServerExchange exchange, RoomCreationRequest request) {
        String ruid = queueManager.submitRequest(request);
        JsonObject response = createRoomCreationResponse(ruid);
        responseFormatter.sendSuccessResponse(exchange, StatusCodes.ACCEPTED, response);
    }

    /**
     * 방 생성 응답을 생성합니다.
     */
    @NotNull
    private JsonObject createRoomCreationResponse(String ruid) {
        JsonObject response = new JsonObject();
        response.addProperty(FIELD_RUID, ruid);
        response.addProperty(FIELD_STATUS, "대기중");
        response.addProperty(FIELD_MESSAGE, String.format(MSG_ROOM_CREATION_ACCEPTED, ruid, ruid));
        return response;
    }

    /**
     * 요청 읽기 오류를 처리합니다.
     */
    private void handleRequestReadError(HttpServerExchange exchange, Exception e) {
        responseFormatter.sendErrorResponse(exchange, StatusCodes.INTERNAL_SERVER_ERROR,
                ERROR_REQUEST_READ, e, false);
    }

    /**
     * 쿼리에서 RUID를 추출합니다.
     */
    private String extractRuidFromQuery(HttpServerExchange exchange) {
        return responseFormatter.getQueryParam(exchange, FIELD_RUID).orElse(null);
    }

    /**
     * 결과 조회를 처리합니다.
     */
    private void processResultQuery(HttpServerExchange exchange, String ruid) {
        Optional<JobResultStore.JobState> jobStateOptional = resultStore.getJobState(ruid);

        if (jobStateOptional.isEmpty()) {
            String errorMessage = String.format(ERROR_JOB_NOT_FOUND, ruid);
            responseFormatter.sendErrorResponse(exchange, StatusCodes.NOT_FOUND, errorMessage);
            return;
        }

        processJobState(exchange, ruid, jobStateOptional.get());
    }

    /**
     * 작업 상태를 처리합니다.
     */
    private void processJobState(HttpServerExchange exchange, String ruid, @NotNull JobResultStore.JobState jobState) {
        switch (jobState.status()) {
            case QUEUED, PROCESSING -> sendInProgressResponse(exchange, ruid, jobState);
            case COMPLETED -> sendCompletedResponse(exchange, ruid, jobState);
            case FAILED -> sendFailedResponse(exchange, ruid, jobState);
        }
    }

    /**
     * 진행 중인 작업에 대한 응답을 전송합니다.
     */
    private void sendInProgressResponse(HttpServerExchange exchange, String ruid, @NotNull JobResultStore.JobState jobState) {
        JsonObject statusResponse = createJobStatusResponse(ruid, jobState.status());
        responseFormatter.sendJsonResponse(exchange, StatusCodes.OK, statusResponse);
    }

    /**
     * 작업 상태 응답을 생성합니다.
     */
    @NotNull
    private JsonObject createJobStatusResponse(String ruid, @NotNull JobResultStore.Status status) {
        JsonObject response = new JsonObject();
        response.addProperty(FIELD_RUID, ruid);
        response.addProperty(FIELD_STATUS, status.name());
        return response;
    }

    /**
     * 완료된 작업에 대한 응답을 전송합니다.
     */
    private void sendCompletedResponse(HttpServerExchange exchange, String ruid, @NotNull JobResultStore.JobState jobState) {
        responseFormatter.sendJsonResponse(exchange, StatusCodes.OK, jobState.result());
        resultStore.deleteJob(ruid);
        log.info("ruid '{}'에 대한 결과가 전달되고 삭제되었습니다.", ruid);
    }

    /**
     * 실패한 작업에 대한 응답을 전송합니다.
     */
    private void sendFailedResponse(HttpServerExchange exchange, String ruid, @NotNull JobResultStore.JobState jobState) {
        responseFormatter.sendJsonResponse(exchange, StatusCodes.OK, jobState.result());
        resultStore.deleteJob(ruid);
        log.warn("ruid '{}'에 대한 실패 결과가 전달되고 삭제되었습니다.", ruid);
    }
}