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
    public String submitRequest(@NotNull RoomCreationRequest request) {
        String ruid = generateRuid();
        long queuedTime = System.currentTimeMillis();

        // 요청 데이터 상세 로깅
        log.info("=== 요청 제출 상세 정보 ===");
        log.info("RUID: {}", ruid);
        log.info("User UUID: {}", request.getUuid());
        log.info("Theme: '{}'", request.getTheme());
        log.info("Keywords: {}", request.getKeywords() != null ? String.join(", ", request.getKeywords()) : "null");
        log.info("Difficulty: '{}'", request.getDifficulty());
        log.info("Queue Time: {}", queuedTime);
        log.info("Current Queue Size BEFORE: {}", requestQueue.size());
        log.info("========================");

        try {
            resultStore.registerJob(ruid);
            QueuedRoomRequest queuedRequest = new QueuedRoomRequest(ruid, request, queuedTime);
            requestQueue.put(queuedRequest);

            log.info("방 생성 요청 큐에 추가됨. ruid: {}, user_uuid: {}, 현재 큐 크기: {}",
                    ruid, request.getUuid(), requestQueue.size());
            log.info("큐 추가 후 상태 - Active: {}, Completed: {}", activeRequests.get(), completedRequests.get());

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
                log.debug("큐에서 요청 대기 중... 현재 큐 크기: {}", requestQueue.size());
                QueuedRoomRequest queuedRequest = requestQueue.take();

                long waitTime = System.currentTimeMillis() - queuedRequest.queuedTimestamp();
                log.info("큐에서 요청 추출됨. ruid: {}, 큐 대기 시간: {}ms",
                        queuedRequest.ruid(), waitTime);

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

    private void processRequestInBackground(@NotNull QueuedRoomRequest queuedRequest) {
        String ruid = queuedRequest.ruid();
        RoomCreationRequest request = queuedRequest.request();
        long processingStartTime = System.currentTimeMillis();

        int currentActive = activeRequests.incrementAndGet();
        log.info("=== 백그라운드 처리 시작 ===");
        log.info("RUID: {}", ruid);
        log.info("User UUID: {}", request.getUuid());
        log.info("현재 활성 요청: {}", currentActive);
        log.info("처리 시작 시간: {}", processingStartTime);
        log.info("===========================");

        resultStore.updateJobStatus(ruid, JobResultStore.Status.PROCESSING);

        try {
            log.info("RoomService.createRoom() 호출 시작. ruid: {}", ruid);
            JsonObject result = roomService.createRoom(request, ruid);
            long processingEndTime = System.currentTimeMillis();
            long processingDuration = processingEndTime - processingStartTime;

            log.info("=== RoomService.createRoom() 완료 ===");
            log.info("RUID: {}", ruid);
            log.info("처리 시간: {}ms", processingDuration);
            log.info("결과 성공 여부: {}", result != null && result.has("success") ? result.get("success").getAsBoolean() : "unknown");
            log.info("결과 객체 크기: {} 필드", result != null ? result.size() : 0);
            if (result != null) {
                log.info("결과 필드들: {}", result.keySet());
            }
            log.info("===============================");

            resultStore.storeFinalResult(ruid, result, JobResultStore.Status.COMPLETED);
            int completedCount = completedRequests.incrementAndGet();
            log.info("백그라운드 처리 성공. ruid: {}. 총 완료 건수: {}", ruid, completedCount);

        } catch (Exception e) {
            long processingEndTime = System.currentTimeMillis();
            long processingDuration = processingEndTime - processingStartTime;

            log.error("=== 백그라운드 처리 중 오류 발생 ===");
            log.error("RUID: {}", ruid);
            log.error("처리 시간: {}ms", processingDuration);
            log.error("오류 메시지: {}", e.getMessage());
            log.error("오류 타입: {}", e.getClass().getName());
            log.error("==========================", e);

            JsonObject errorResponse = createErrorResponse(ruid, request.getUuid(), e.getMessage());
            resultStore.storeFinalResult(ruid, errorResponse, JobResultStore.Status.FAILED);
        } finally {
            int remainingActive = activeRequests.decrementAndGet();
            log.info("백그라운드 처리 종료. ruid: {}, 남은 활성 요청: {}", ruid, remainingActive);
        }
    }

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