package com.febrie.eroom.service.queue;

import com.febrie.eroom.model.RoomCreationRequest;
import com.febrie.eroom.service.JobResultStore;
import com.febrie.eroom.service.room.RoomService;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class RoomRequestQueueManager implements QueueManager {
    private static final Logger log = LoggerFactory.getLogger(RoomRequestQueueManager.class);

    private record QueuedRoomRequest(String ruid, RoomCreationRequest request, long queuedTimestamp) {
    }

    private final ExecutorService executorService;
    private final BlockingQueue<QueuedRoomRequest> requestQueue;
    private final RoomService roomService;
    private final JobResultStore resultStore;
    private final int maxConcurrentRequests;

    private final AtomicInteger activeRequests = new AtomicInteger(0);
    private final AtomicInteger completedRequests = new AtomicInteger(0);

    /**
     * RoomRequestQueueManager 생성자
     * 큐 관리자를 초기화하고 워커 스레드를 시작합니다.
     */
    public RoomRequestQueueManager(RoomService roomService, JobResultStore resultStore, int maxConcurrentRequests) {
        this.roomService = roomService;
        this.resultStore = resultStore;
        this.maxConcurrentRequests = maxConcurrentRequests;
        this.executorService = Executors.newFixedThreadPool(maxConcurrentRequests);
        this.requestQueue = new LinkedBlockingQueue<>();

        initializeWorkers(maxConcurrentRequests);
        log.info("RoomRequestQueueManager 초기화 완료. 최대 동시 처리 수: {}", maxConcurrentRequests);
    }

    /**
     * 워커 스레드들을 초기화합니다.
     */
    private void initializeWorkers(int workerCount) {
        for (int i = 0; i < workerCount; i++) {
            executorService.submit(this::runProcessorLoop);
        }
    }

    /**
     * 방 생성 요청을 큐에 추가합니다.
     */
    @Override
    public String submitRequest(@NotNull RoomCreationRequest request) {
        String ruid = generateRuid();
        long queuedTime = System.currentTimeMillis();

        logRequestDetails(ruid, request, queuedTime);

        try {
            return enqueueRequest(ruid, request, queuedTime);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("요청을 큐에 추가하는 중 인터럽트 발생: ruid={}", ruid, e);
            throw new RuntimeException("요청 처리가 중단되었습니다.", e);
        }
    }

    /**
     * 요청을 큐에 추가하고 RUID를 반환합니다.
     */
    private String enqueueRequest(String ruid, RoomCreationRequest request, long queuedTime) throws InterruptedException {
        resultStore.registerJob(ruid);
        QueuedRoomRequest queuedRequest = new QueuedRoomRequest(ruid, request, queuedTime);
        requestQueue.put(queuedRequest);

        logQueueStatus(ruid, request.getUuid());
        return ruid;
    }

    /**
     * 요청 상세 정보를 로깅합니다.
     */
    private void logRequestDetails(String ruid, @NotNull RoomCreationRequest request, long queuedTime) {
        log.info("=== 요청 제출 상세 정보 ===");
        log.info("RUID: {}", ruid);
        log.info("User UUID: {}", request.getUuid());
        log.info("Theme: '{}'", request.getTheme());
        log.info("Keywords: {}", formatKeywords(request.getKeywords()));
        log.info("Difficulty: '{}'", request.getDifficulty());
        log.info("Queue Time: {}", queuedTime);
        log.info("Current Queue Size BEFORE: {}", requestQueue.size());
        log.info("========================");
    }

    /**
     * 키워드 배열을 포맷팅합니다.
     */
    @NotNull
    private String formatKeywords(String[] keywords) {
        return keywords != null ? String.join(", ", keywords) : "null";
    }

    /**
     * 큐 상태를 로깅합니다.
     */
    private void logQueueStatus(String ruid, String userUuid) {
        log.info("방 생성 요청 큐에 추가됨. ruid: {}, user_uuid: {}, 현재 큐 크기: {}",
                ruid, userUuid, requestQueue.size());
        log.info("큐 추가 후 상태 - Active: {}, Completed: {}", activeRequests.get(), completedRequests.get());
    }

    /**
     * 현재 큐 상태를 반환합니다.
     */
    @Override
    public QueueStatus getQueueStatus() {
        return new QueueStatus(
                requestQueue.size(),
                activeRequests.get(),
                completedRequests.get(),
                this.maxConcurrentRequests
        );
    }

    /**
     * 큐 매니저를 안전하게 종료합니다.
     */
    @Override
    public void shutdown() {
        log.info("RoomRequestQueueManager 종료 시작...");
        shutdownExecutorService();
        log.info("RoomRequestQueueManager 종료 완료.");
    }

    /**
     * ExecutorService를 종료합니다.
     */
    private void shutdownExecutorService() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(1, TimeUnit.SECONDS)) {
                forceShutdownExecutorService();
            }
        } catch (InterruptedException e) {
            handleShutdownInterruption();
        }
    }

    /**
     * ExecutorService를 강제 종료합니다.
     */
    private void forceShutdownExecutorService() {
        log.warn("ExecutorService가 정상적으로 종료되지 않아 강제 종료합니다.");
        executorService.shutdownNow();
    }

    /**
     * 종료 중 인터럽트를 처리합니다.
     */
    private void handleShutdownInterruption() {
        log.error("ExecutorService 종료 대기 중 인터럽트 발생");
        executorService.shutdownNow();
        Thread.currentThread().interrupt();
    }

    /**
     * 고유한 RUID를 생성합니다.
     */
    @NotNull
    private String generateRuid() {
        return "room_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /**
     * 큐 프로세서 워커의 메인 루프입니다.
     */
    private void runProcessorLoop() {
        log.info("큐 프로세서 워커 시작: {}", Thread.currentThread().getName());

        while (!Thread.currentThread().isInterrupted()) {
            try {
                processNextRequest();
            } catch (InterruptedException e) {
                handleWorkerInterruption();
                break;
            } catch (Exception e) {
                log.error("큐 프로세서 루프에서 복구 불가능한 오류 발생", e);
            }
        }
    }

    /**
     * 큐에서 다음 요청을 처리합니다.
     */
    private void processNextRequest() throws InterruptedException {
        log.debug("큐에서 요청 대기 중... 현재 큐 크기: {}", requestQueue.size());
        QueuedRoomRequest queuedRequest = requestQueue.take();

        logRequestExtraction(queuedRequest);
        processRequestInBackground(queuedRequest);
    }

    /**
     * 큐에서 요청 추출을 로깅합니다.
     */
    private void logRequestExtraction(@NotNull QueuedRoomRequest queuedRequest) {
        long waitTime = System.currentTimeMillis() - queuedRequest.queuedTimestamp();
        log.info("큐에서 요청 추출됨. ruid: {}, 큐 대기 시간: {}ms",
                queuedRequest.ruid(), waitTime);
    }

    /**
     * 워커 스레드 인터럽트를 처리합니다.
     */
    private void handleWorkerInterruption() {
        Thread.currentThread().interrupt();
        log.warn("큐 프로세서 워커 중단됨: {}", Thread.currentThread().getName());
    }

    /**
     * 백그라운드에서 요청을 처리합니다.
     */
    private void processRequestInBackground(@NotNull QueuedRoomRequest queuedRequest) {
        String ruid = queuedRequest.ruid();
        RoomCreationRequest request = queuedRequest.request();

        logProcessingStart(ruid, request);
        updateJobStatusToProcessing(ruid);

        try {
            JsonObject result = executeRoomCreation(ruid, request);
            handleProcessingSuccess(ruid, result);
        } catch (Exception e) {
            handleProcessingError(ruid, request.getUuid(), e);
        } finally {
            finalizeProcessing(ruid);
        }
    }

    /**
     * 처리 시작을 로깅합니다.
     */
    private void logProcessingStart(String ruid, @NotNull RoomCreationRequest request) {
        long processingStartTime = System.currentTimeMillis();
        int currentActive = activeRequests.incrementAndGet();

        log.info("=== 백그라운드 처리 시작 ===");
        log.info("RUID: {}", ruid);
        log.info("User UUID: {}", request.getUuid());
        log.info("현재 활성 요청: {}", currentActive);
        log.info("처리 시작 시간: {}", processingStartTime);
        log.info("===========================");
    }

    /**
     * 작업 상태를 처리중으로 업데이트합니다.
     */
    private void updateJobStatusToProcessing(String ruid) {
        resultStore.updateJobStatus(ruid, JobResultStore.Status.PROCESSING);
    }

    /**
     * 방 생성을 실행합니다.
     */
    private JsonObject executeRoomCreation(String ruid, RoomCreationRequest request) {
        log.info("RoomService.createRoom() 호출 시작. ruid: {}", ruid);
        long startTime = System.currentTimeMillis();

        JsonObject result = roomService.createRoom(request, ruid);

        logRoomCreationResult(ruid, result, startTime);
        return result;
    }

    /**
     * 방 생성 결과를 로깅합니다.
     */
    private void logRoomCreationResult(String ruid, JsonObject result, long startTime) {
        long processingDuration = System.currentTimeMillis() - startTime;

        log.info("=== RoomService.createRoom() 완료 ===");
        log.info("RUID: {}", ruid);
        log.info("처리 시간: {}ms", processingDuration);
        log.info("결과 성공 여부: {}", extractSuccessStatus(result));
        log.info("결과 객체 크기: {} 필드", result != null ? result.size() : 0);
        if (result != null) {
            log.info("결과 필드들: {}", result.keySet());
        }
        log.info("===============================");
    }

    /**
     * 결과에서 성공 여부를 추출합니다.
     */
    @NotNull
    private String extractSuccessStatus(JsonObject result) {
        if (result != null && result.has("success")) {
            return String.valueOf(result.get("success").getAsBoolean());
        }
        return "unknown";
    }

    /**
     * 처리 성공을 처리합니다.
     */
    private void handleProcessingSuccess(String ruid, JsonObject result) {
        resultStore.storeFinalResult(ruid, result, JobResultStore.Status.COMPLETED);
        int completedCount = completedRequests.incrementAndGet();
        log.info("백그라운드 처리 성공. ruid: {}. 총 완료 건수: {}", ruid, completedCount);
    }

    /**
     * 처리 오류를 처리합니다.
     */
    private void handleProcessingError(String ruid, String userUuid, Exception e) {
        logProcessingError(ruid, e);
        JsonObject errorResponse = createErrorResponse(ruid, userUuid, e.getMessage());
        resultStore.storeFinalResult(ruid, errorResponse, JobResultStore.Status.FAILED);
    }

    /**
     * 처리 오류를 로깅합니다.
     */
    private void logProcessingError(String ruid, @NotNull Exception e) {
        long processingEndTime = System.currentTimeMillis();

        log.error("=== 백그라운드 처리 중 오류 발생 ===");
        log.error("RUID: {}", ruid);
        log.error("오류 메시지: {}", e.getMessage());
        log.error("오류 타입: {}", e.getClass().getName());
        log.error("==========================", e);
    }

    /**
     * 처리를 마무리합니다.
     */
    private void finalizeProcessing(String ruid) {
        int remainingActive = activeRequests.decrementAndGet();
        log.info("백그라운드 처리 종료. ruid: {}, 남은 활성 요청: {}", ruid, remainingActive);
    }

    /**
     * 오류 응답을 생성합니다.
     */
    @NotNull
    private JsonObject createErrorResponse(String ruid, String userUuid, String errorMessage) {
        JsonObject errorResponse = new JsonObject();
        errorResponse.addProperty("ruid", ruid);
        errorResponse.addProperty("uuid", userUuid);
        errorResponse.addProperty("success", false);
        errorResponse.addProperty("error", errorMessage != null ? errorMessage : "An unknown error occurred during background processing.");
        errorResponse.addProperty("timestamp", String.valueOf(System.currentTimeMillis()));
        return errorResponse;
    }
}