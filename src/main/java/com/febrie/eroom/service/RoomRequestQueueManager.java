package com.febrie.eroom.service;

import com.febrie.eroom.model.RoomCreationRequest;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class RoomRequestQueueManager {
    private static final Logger log = LoggerFactory.getLogger(RoomRequestQueueManager.class);

    // 큐에 저장될 작업 단위
    private record QueuedRoomRequest(String ruid, RoomCreationRequest request) {
    }

    private final ExecutorService executorService;
    private final BlockingQueue<QueuedRoomRequest> requestQueue;
    private final RoomService roomService;
    private final JobResultStore resultStore;
    private final int maxConcurrentRequests;

    private final AtomicInteger activeRequests = new AtomicInteger(0);
    private final AtomicInteger completedRequests = new AtomicInteger(0);

    public RoomRequestQueueManager(RoomService roomService, JobResultStore resultStore, int maxConcurrentRequests) {
        this.roomService = roomService;
        this.resultStore = resultStore;
        this.maxConcurrentRequests = maxConcurrentRequests;
        this.executorService = Executors.newFixedThreadPool(maxConcurrentRequests);
        this.requestQueue = new LinkedBlockingQueue<>();

        for (int i = 0; i < maxConcurrentRequests; i++) {
            startQueueProcessor();
        }
        log.info("RoomRequestQueueManager 초기화 완료. 최대 동시 처리 수: {}", maxConcurrentRequests);
    }

    /**
     * 방 생성 요청을 받아 ruid를 생성하고 큐에 추가한 뒤, ruid를 즉시 반환합니다.
     *
     * @param request 방 생성 요청
     * @return 생성된 작업의 고유 ID (ruid)
     */
    public String submitRequest(@NotNull RoomCreationRequest request) {
        // 1. 서버에서 고유 ruid 생성
        String ruid = "room_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        try {
            // 2. JobResultStore에 ruid로 작업 등록
            resultStore.registerJob(ruid);

            // 3. 큐에 ruid와 요청을 함께 저장
            requestQueue.put(new QueuedRoomRequest(ruid, request));
            log.info("방 생성 요청 큐에 추가됨. ruid: {}, user_uuid: {}, 현재 큐 크기: {}",
                    ruid, request.getUuid(), requestQueue.size());

            // 4. 클라이언트에게 ruid 반환
            return ruid;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("요청을 큐에 추가하는 중 인터럽트 발생: ruid={}", ruid, e);
            throw new RuntimeException("Request processing was interrupted.", e);
        }
    }

    private void startQueueProcessor() {
        executorService.submit(() -> {
            log.info("큐 프로세서 워커 시작: {}", Thread.currentThread().getName());
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    QueuedRoomRequest queuedRequest = requestQueue.take();
                    processRequestInBackground(queuedRequest);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("큐 프로세서 워커 중단됨: {}", Thread.currentThread().getName());
                    break;
                } catch (Exception e) {
                    log.error("큐 프로세서 루프에서 복구 불가능한 오류 발생", e);
                }
            }
        });
    }

    private void processRequestInBackground(QueuedRoomRequest queuedRequest) {
        String ruid = queuedRequest.ruid();
        RoomCreationRequest request = queuedRequest.request();

        activeRequests.incrementAndGet();
        log.info("백그라운드 처리 시작. ruid: {}. 현재 활성 요청: {}", ruid, activeRequests.get());
        resultStore.updateJobStatus(ruid, JobResultStore.Status.PROCESSING);

        try {
            // 변경된 RoomService 메소드 호출
            JsonObject result = roomService.createRoom(request, ruid);
            resultStore.storeFinalResult(ruid, result, JobResultStore.Status.COMPLETED);
            int completedCount = completedRequests.incrementAndGet();
            log.info("백그라운드 처리 성공. ruid: {}. 총 완료 건수: {}", ruid, completedCount);
        } catch (Exception e) {
            log.error("백그라운드 처리 중 오류 발생. ruid: {}", ruid, e);
            JsonObject errorResponse = createErrorResponse(ruid, request.getUuid(), e.getMessage());
            resultStore.storeFinalResult(ruid, errorResponse, JobResultStore.Status.FAILED);
        } finally {
            activeRequests.decrementAndGet();
        }
    }

    @NotNull
    private JsonObject createErrorResponse(@NotNull String ruid, String userUuid, String errorMessage) {
        JsonObject errorResponse = new JsonObject();
        errorResponse.addProperty("ruid", ruid);
        errorResponse.addProperty("uuid", userUuid);
        errorResponse.addProperty("success", false);
        errorResponse.addProperty("error", errorMessage != null ? errorMessage : "An unknown error occurred during background processing.");
        errorResponse.addProperty("timestamp", String.valueOf(System.currentTimeMillis()));
        return errorResponse;
    }

    public record QueueStatus(int queued, int active, int completed, int maxConcurrent) {
    }

    public QueueStatus getQueueStatus() {
        return new QueueStatus(
                requestQueue.size(),
                activeRequests.get(),
                completedRequests.get(),
                this.maxConcurrentRequests
        );
    }

    public void shutdown() {
        log.info("RoomRequestQueueManager 종료 시작...");
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
                log.warn("ExecutorService가 정상적으로 종료되지 않아 강제 종료합니다.");
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            log.error("ExecutorService 종료 대기 중 인터럽트 발생");
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("RoomRequestQueueManager 종료 완료.");
    }
}