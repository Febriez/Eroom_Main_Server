package com.febrie.eroom.service;

import com.febrie.eroom.model.RoomCreationRequest;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class RoomRequestQueueManager {
    private static final Logger log = LoggerFactory.getLogger(RoomRequestQueueManager.class);

    // 동시 처리 가능한 요청 수 (나중에 서버 늘릴 때 UndertowServer에서 이 값 변경)
    private final int maxConcurrentRequests;
    private final ExecutorService executorService;
    private final BlockingQueue<QueuedRequest> requestQueue;
    private final AtomicInteger queuedRequests = new AtomicInteger(0);
    private final AtomicInteger activeRequests = new AtomicInteger(0);
    private final AtomicInteger completedRequests = new AtomicInteger(0);

    // 동시 실행 제어를 위한 Semaphore
    private final Semaphore concurrencyLimiter;

    // 실제 방 생성 로직을 처리하는 서비스
    private final RoomService roomService;

    public RoomRequestQueueManager(RoomService roomService, int maxConcurrentRequests) {
        this.roomService = roomService;
        this.maxConcurrentRequests = maxConcurrentRequests;
        this.executorService = Executors.newFixedThreadPool(maxConcurrentRequests);
        this.requestQueue = new LinkedBlockingQueue<>();
        this.concurrencyLimiter = new Semaphore(maxConcurrentRequests);

        // 큐 처리 워커 시작
        startQueueProcessor();

        log.info("RoomRequestQueueManager 초기화 완료. 최대 동시 처리 수: {}", maxConcurrentRequests);
    }

    /**
     * 방 생성 요청을 큐에 추가하고 Future를 반환
     */
    public CompletableFuture<JsonObject> submitRequest(RoomCreationRequest request) {
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        QueuedRequest queuedRequest = new QueuedRequest(request, future);

        try {
            // offer() 대신 put() 사용 (큐가 가득 차면 대기)
            // LinkedBlockingQueue는 용량 제한이 없으므로 실제로는 블로킹되지 않음
            boolean added = requestQueue.offer(queuedRequest);
            if (!added) {
                // 만약 큐가 가득 찬 경우 (LinkedBlockingQueue에서는 발생하지 않음)
                throw new RejectedExecutionException("요청 큐가 가득 참");
            }

            int queueSize = queuedRequests.incrementAndGet();
            log.info("방 생성 요청 큐에 추가됨. UUID: {}, 현재 큐 크기: {}, 활성 요청: {}",
                    request.getUuid(), queueSize, activeRequests.get());
        } catch (Exception e) {
            future.completeExceptionally(e);
            log.error("요청을 큐에 추가하는 중 오류 발생", e);
        }

        return future;
    }

    /**
     * 큐에서 요청을 가져와 처리하는 워커 스레드 시작
     */
    private void startQueueProcessor() {
        Thread processorThread = new Thread(() -> {
            log.info("큐 프로세서 시작");
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    // 큐에서 요청 가져오기 (없으면 대기)
                    QueuedRequest queuedRequest = requestQueue.take();
                    queuedRequests.decrementAndGet();

                    // Semaphore를 사용하여 동시 실행 제어 (busy-waiting 제거)
                    concurrencyLimiter.acquire();

                    // 요청 처리
                    processRequest(queuedRequest);

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.info("큐 프로세서 중단됨");
                    break;
                } catch (Exception e) {
                    log.error("큐 프로세서에서 예기치 않은 오류 발생", e);
                }
            }
        }, "RoomRequestQueueProcessor");

        processorThread.setDaemon(true);
        processorThread.start();
    }

    /**
     * 개별 요청 처리
     */
    private void processRequest(QueuedRequest queuedRequest) {
        executorService.submit(() -> {
            RoomCreationRequest request = queuedRequest.request;
            CompletableFuture<JsonObject> future = queuedRequest.future;

            int active = activeRequests.incrementAndGet();
            long startTime = System.currentTimeMillis();

            log.info("방 생성 시작. UUID: {}, 활성 요청: {}, 대기중: {}",
                    request.getUuid(), active, queuedRequests.get());

            try {
                // 실제 방 생성 로직 실행
                JsonObject result = roomService.createRoom(request);
                future.complete(result);

                long elapsed = System.currentTimeMillis() - startTime;
                int completed = completedRequests.incrementAndGet();

                log.info("방 생성 완료. UUID: {}, 소요 시간: {}ms, 총 완료: {}",
                        request.getUuid(), elapsed, completed);

            } catch (Exception e) {
                future.completeExceptionally(e);
                log.error("방 생성 실패. UUID: {}", request.getUuid(), e);
            } finally {
                activeRequests.decrementAndGet();
                // Semaphore 해제
                concurrencyLimiter.release();
            }
        });
    }

    /**
     * 현재 큐 상태 조회
     */
    public QueueStatus getQueueStatus() {
        return new QueueStatus(
                queuedRequests.get(),
                activeRequests.get(),
                completedRequests.get(),
                maxConcurrentRequests
        );
    }

    /**
     * 리소스 정리
     */
    public void shutdown() {
        log.info("RoomRequestQueueManager 종료 시작");
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("RoomRequestQueueManager 종료 완료");
    }

    /**
     * 큐에 저장되는 요청 정보
     */
    private static class QueuedRequest {
        final RoomCreationRequest request;
        final CompletableFuture<JsonObject> future;
        final long enqueuedTime;

        QueuedRequest(RoomCreationRequest request, CompletableFuture<JsonObject> future) {
            this.request = request;
            this.future = future;
            this.enqueuedTime = System.currentTimeMillis();
        }
    }

    /**
     * 큐 상태 정보
     */
    public record QueueStatus(int queued, int active, int completed, int maxConcurrent) {

        @NotNull
        @Override
        public String toString() {
            return String.format("QueueStatus{queued=%d, active=%d, completed=%d, maxConcurrent=%d}",
                    queued, active, completed, maxConcurrent);
        }
    }
}