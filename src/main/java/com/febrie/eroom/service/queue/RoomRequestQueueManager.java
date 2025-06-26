package com.febrie.eroom.service.queue;

import com.febrie.eroom.model.RoomCreationRequest;
import com.febrie.eroom.model.RoomCreationResponse;
import com.febrie.eroom.service.JobResultStore;
import com.febrie.eroom.service.room.RoomService;
import com.google.gson.Gson;
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
    private final Gson gson;

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
        this.gson = new Gson();

        initializeWorkers(maxConcurrentRequests);
        log.info("RoomRequestQueueManager 초기화 - maxConcurrent: {}", maxConcurrentRequests);
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
        logRequestDetails(ruid, request);
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
    private void logRequestDetails(String ruid, @NotNull RoomCreationRequest request) {
        log.debug("요청 제출 - ruid: {}, uuid: {}, theme: {}, keywords: [{}], difficulty: {}, queueSize: {}",
                ruid, request.getUuid(), request.getTheme(),
                formatKeywords(request.getKeywords()), request.getDifficulty(),
                requestQueue.size());
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
        log.info("요청 큐 추가됨 - ruid: {}, uuid: {}, queueSize: {}, active: {}, completed: {}",
                ruid, userUuid, requestQueue.size(), activeRequests.get(), completedRequests.get());
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
        log.debug("RoomRequestQueueManager 종료 시작");
        shutdownExecutorService();
        log.debug("RoomRequestQueueManager 종료 완료");
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
        log.debug("큐 프로세서 워커 시작: {}", Thread.currentThread().getName());

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
        log.debug("큐에서 요청 추출 - ruid: {}, waitTime: {}ms",
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
            RoomCreationResponse response = executeRoomCreation(ruid, request);
            handleProcessingSuccess(ruid, response);
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
        int currentActive = activeRequests.incrementAndGet();
        log.info("처리 시작 - ruid: {}, uuid: {}, active: {}",
                ruid, request.getUuid(), currentActive);
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
    private RoomCreationResponse executeRoomCreation(String ruid, RoomCreationRequest request) {
        log.debug("RoomService.createRoom() 호출 - ruid: {}", ruid);
        long startTime = System.currentTimeMillis();

        RoomCreationResponse response = roomService.createRoom(request, ruid);

        logRoomCreationResult(ruid, response, startTime);
        return response;
    }

    /**
     * 방 생성 결과를 로깅합니다.
     */
    private void logRoomCreationResult(String ruid, @NotNull RoomCreationResponse response, long startTime) {
        long processingDuration = System.currentTimeMillis() - startTime;

        if (response.isSuccess()) {
            log.info("방 생성 완료 - ruid: {}, duration: {}ms, theme: {}, scripts: {}",
                    ruid, processingDuration, response.getTheme(),
                    response.getObjectScripts() != null ? response.getObjectScripts().size() : 0);
        } else {
            log.error("방 생성 실패 - ruid: {}, duration: {}ms, error: {}",
                    ruid, processingDuration, response.getErrorMessage());
        }
    }

    /**
     * 처리 성공을 처리합니다.
     */
    private void handleProcessingSuccess(String ruid, RoomCreationResponse response) {
        JsonObject resultJson = convertResponseToJson(response);
        resultStore.storeFinalResult(ruid, resultJson, JobResultStore.Status.COMPLETED);
        int completedCount = completedRequests.incrementAndGet();
        log.info("처리 성공 - ruid: {}, completed: {}", ruid, completedCount);
    }

    /**
     * RoomCreationResponse를 JsonObject로 변환합니다.
     */
    @NotNull
    private JsonObject convertResponseToJson(@NotNull RoomCreationResponse response) {
        JsonObject json = new JsonObject();

        // 기본 필드
        addBasicFields(json, response);

        // 키워드 배열
        addKeywordsIfPresent(json, response);

        // 시나리오
        addScenarioIfPresent(json, response);

        // 스크립트들
        if (response.isSuccess()) {
            json.add("scripts", extractScripts(response));
        }

        // 모델 추적 정보
        addModelTrackingIfPresent(json, response);

        // 오류 메시지
        addErrorMessageIfFailed(json, response);

        return json;
    }

    /**
     * JsonObject에 기본 필드들을 추가합니다.
     */
    private void addBasicFields(@NotNull JsonObject json, @NotNull RoomCreationResponse response) {
        json.addProperty("uuid", response.getUuid());
        json.addProperty("ruid", response.getPuid());
        json.addProperty("theme", response.getTheme());
        json.addProperty("difficulty", response.getDifficulty());
        json.addProperty("success", response.isSuccess());
        json.addProperty("timestamp", String.valueOf(System.currentTimeMillis()));
    }

    /**
     * 키워드가 있으면 JsonObject에 추가합니다.
     */
    private void addKeywordsIfPresent(@NotNull JsonObject json, @NotNull RoomCreationResponse response) {
        if (response.getKeywords() != null) {
            json.add("keywords", gson.toJsonTree(response.getKeywords()));
        }
    }

    /**
     * 시나리오가 있으면 JsonObject에 추가합니다.
     */
    private void addScenarioIfPresent(@NotNull JsonObject json, @NotNull RoomCreationResponse response) {
        if (response.getScenario() != null) {
            json.add("scenario", response.getScenario());
        }
    }

    /**
     * 모델 추적 정보가 있으면 JsonObject에 추가합니다.
     */
    private void addModelTrackingIfPresent(@NotNull JsonObject json, @NotNull RoomCreationResponse response) {
        if (response.getModelTracking() != null) {
            json.add("model_tracking", response.getModelTracking());
        }
    }

    /**
     * 실패한 경우 에러 메시지를 JsonObject에 추가합니다.
     */
    private void addErrorMessageIfFailed(@NotNull JsonObject json, @NotNull RoomCreationResponse response) {
        if (!response.isSuccess() && response.getErrorMessage() != null) {
            json.addProperty("error", response.getErrorMessage());
        }
    }

    /**
     * RoomCreationResponse에서 스크립트들을 추출하여 JsonObject로 반환합니다.
     */
    @NotNull
    private JsonObject extractScripts(@NotNull RoomCreationResponse response) {
        JsonObject scripts = new JsonObject();

        // GameManager 스크립트 추가
        addGameManagerScript(scripts, response);

        // 객체 스크립트들 추가
        addObjectScripts(scripts, response);

        return scripts;
    }

    /**
     * GameManager 스크립트를 JsonObject에 추가합니다.
     */
    private void addGameManagerScript(@NotNull JsonObject scripts, @NotNull RoomCreationResponse response) {
        String gameManagerScript = response.getGameManagerScript();
        if (gameManagerScript != null && !gameManagerScript.isEmpty()) {
            scripts.addProperty("GameManager.cs", gameManagerScript);
        }
    }

    /**
     * 객체 스크립트들을 JsonObject에 추가합니다.
     */
    private void addObjectScripts(@NotNull JsonObject scripts, @NotNull RoomCreationResponse response) {
        if (response.getObjectScripts() == null) {
            return;
        }

        for (String scriptEntry : response.getObjectScripts()) {
            parseAndAddScriptEntry(scripts, scriptEntry);
        }
    }

    /**
     * 스크립트 엔트리를 파싱하여 JsonObject에 추가합니다.
     */
    private void parseAndAddScriptEntry(@NotNull JsonObject scripts, @NotNull String scriptEntry) {
        int colonIndex = scriptEntry.indexOf(':');
        if (colonIndex > 0) {
            String fileName = scriptEntry.substring(0, colonIndex);
            String content = scriptEntry.substring(colonIndex + 1);
            scripts.addProperty(fileName, content);
        } else {
            log.warn("잘못된 스크립트 엔트리 형식: {}", scriptEntry);
        }
    }

    /**
     * 처리 오류를 처리합니다.
     */
    private void handleProcessingError(String ruid, String userUuid, Exception e) {
        logProcessingError(ruid, e);

        RoomCreationResponse errorResponse = createErrorResponse(ruid, userUuid, e.getMessage());
        JsonObject errorJson = convertResponseToJson(errorResponse);

        resultStore.storeFinalResult(ruid, errorJson, JobResultStore.Status.FAILED);
    }

    /**
     * 처리 오류를 로깅합니다.
     */
    private void logProcessingError(String ruid, @NotNull Exception e) {
        log.error("처리 중 오류 발생 - ruid: {}, error: {}", ruid, e.getMessage(), e);
    }

    /**
     * 처리를 마무리합니다.
     */
    private void finalizeProcessing(String ruid) {
        int remainingActive = activeRequests.decrementAndGet();
        log.debug("처리 종료 - ruid: {}, active: {}", ruid, remainingActive);
    }

    /**
     * 오류 응답을 생성합니다.
     */
    @NotNull
    private RoomCreationResponse createErrorResponse(String ruid, String userUuid, String errorMessage) {
        RoomCreationResponse errorResponse = new RoomCreationResponse();
        errorResponse.setUuid(userUuid);
        errorResponse.setPuid(ruid);
        errorResponse.setSuccess(false);
        errorResponse.setErrorMessage(errorMessage != null ? errorMessage : "An unknown error occurred during background processing.");

        return errorResponse;
    }
}