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

        initializeWorkers(maxConcurrentRequests);
        log.info("RoomRequestQueueManager 초기화 완료. 최대 동시 처리 수: {}", maxConcurrentRequests);
    }

    private void initializeWorkers(int workerCount) {
        for (int i = 0; i < workerCount; i++) {
            executorService.submit(this::runProcessorLoop);
        }
    }

    @Override
    public String submitRequest(RoomCreationRequest request) {
        String ruid = generateRuid();

        try {
            resultStore.registerJob(ruid);
            requestQueue.put(new QueuedRoomRequest(ruid, request));
            log.info("방 생성 요청 큐에 추가됨. ruid: {}, user_uuid: {}, 현재 큐 크기: {}",
                    ruid, request.getUuid(), requestQueue.size());
            return ruid;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("요청을 큐에 추가하는 중 인터럽트 발생: ruid={}", ruid, e);
            throw new RuntimeException("요청 처리가 중단되었습니다.", e);
        }
    }

    @Override
    public QueueStatus getQueueStatus() {
        return new QueueStatus(
                requestQueue.size(),
                activeRequests.get(),
                completedRequests.get(),
                this.maxConcurrentRequests
        );
    }

    @Override
    public void shutdown() {
        log.info("RoomRequestQueueManager 종료 시작...");
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(1, TimeUnit.SECONDS)) {
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

    @NotNull
    private String generateRuid() {
        return "room_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private void runProcessorLoop() {
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
    }

    private void processRequestInBackground(QueuedRoomRequest queuedRequest) {
        String ruid = queuedRequest.ruid();
        RoomCreationRequest request = queuedRequest.request();

        activeRequests.incrementAndGet();
        log.info("백그라운드 처리 시작. ruid: {}. 현재 활성 요청: {}", ruid, activeRequests.get());
        resultStore.updateJobStatus(ruid, JobResultStore.Status.PROCESSING);

        try {
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