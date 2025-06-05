package com.febrie.service;

import com.febrie.api.MeshyAPI;
import com.febrie.model.MeshyTask;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Meshy 3D 모델 생성 서비스
 */
@Slf4j
public class MeshyModelService {
    private static MeshyModelService instance;
    private final MeshyAPI meshyApi;
    private final ExecutorService executor;

    // 태스크 진행 상황 추적을 위한 맵
    private final ConcurrentHashMap<String, Map<String, MeshyTask>> tasksByPuid = new ConcurrentHashMap<>();

    // 태스크 처리 시간 추적
    private final ConcurrentHashMap<String, Long> processingTimeByPuid = new ConcurrentHashMap<>();

    // 완료된 모델 URL 캐시
    private final ConcurrentHashMap<String, Map<String, String>> completedModelUrlsByPuid = new ConcurrentHashMap<>();

    /**
     * 싱글톤 인스턴스 반환
     */
    public static synchronized MeshyModelService getInstance() {
        if (instance == null) {
            instance = new MeshyModelService();
        }
        return instance;
    }

    /**
     * 생성자
     */
    private MeshyModelService() {
        this.meshyApi = MeshyAPI.getInstance();
        this.executor = Executors.newFixedThreadPool(10);  // 최대 10개 스레드로 동시 처리
        log.info("Meshy 모델 서비스 초기화 완료");
    }

    /**
     * 서비스 종료
     */
    public void shutdown() {
        log.info("Meshy 모델 서비스 종료 중...");
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("Meshy 모델 서비스 종료 완료");
    }

    /**
     * 시나리오 키워드에서 3D 모델 생성 태스크 처리
     */
    public void processScenarioKeywords(String puid, @NotNull JsonObject scenarioResult) {
        log.info("PUID {}의 시나리오 키워드 처리 시작", puid);

        LocalDateTime startTime = LocalDateTime.now();
        processingTimeByPuid.put(puid, 0L);

        // 이미 진행 중인 처리가 있는지 확인
        if (tasksByPuid.containsKey(puid) && !tasksByPuid.get(puid).isEmpty()) {
            log.warn("PUID {}에 대한 모델 생성 태스크가 이미 진행 중입니다. 기존 태스크를 유지합니다.", puid);
            return;
        }

        // 이미 존재하는 태스크 맵이 있으면 가져오고, 없으면 새로 생성
        Map<String, MeshyTask> tasks = tasksByPuid.computeIfAbsent(puid, k -> new ConcurrentHashMap<>());

        // 모델 URL 캐시 초기화
        completedModelUrlsByPuid.put(puid, new ConcurrentHashMap<>());

        try {
            // 키워드 배열 추출
            if (!scenarioResult.has("keywords") || !scenarioResult.get("keywords").isJsonArray()) {
                log.error("시나리오 결과에 keywords 배열이 없습니다: {}", scenarioResult);
                return;
            }

            JsonArray keywordsArray = scenarioResult.getAsJsonArray("keywords");
            int keywordCount = keywordsArray.size();
            log.info("처리할 키워드 수: {}", keywordCount);

            CountDownLatch latch = new CountDownLatch(keywordCount);

            // 각 키워드에 대해 비동기 처리
            for (JsonElement keywordElement : keywordsArray) {
                JsonObject keywordObj = keywordElement.getAsJsonObject();
                if (!keywordObj.has("name") || !keywordObj.has("value")) continue;

                String modelName = keywordObj.get("name").getAsString();
                String prompt = keywordObj.get("value").getAsString();

                // CompletableFuture로 비동기 처리 개선
                CompletableFuture.runAsync(() -> {
                    try {
                        log.info("모델 {} 생성 태스크 시작 (PUID: {})", modelName, puid);
                        processModelGeneration(puid, modelName, prompt, tasks);
                        log.info("모델 {} 생성 태스크 완료 (PUID: {})", modelName, puid);
                    } catch (Exception e) {
                        log.error("모델 {} 생성 중 오류 발생: {}", modelName, e.getMessage(), e);
                    } finally {
                        latch.countDown();
                        log.debug("모델 생성 카운트다운: {} (남은 태스크: {})", modelName, latch.getCount());
                    }
                }, executor);
            }

            // 주기적으로 진행 상황 로깅하기 위한 스케줄러 설정
            ScheduledExecutorService progressLogger = Executors.newSingleThreadScheduledExecutor();
            progressLogger.scheduleAtFixedRate(() -> {
                long remaining = latch.getCount();
                long completed = keywordCount - remaining;
                if (remaining > 0) {
                    log.info("PUID {}의 모델 생성 진행 상황: {}/{}개 완료 ({}%)", 
                            puid, completed, keywordCount, Math.round((double)completed/keywordCount*100));
                }
            }, 30, 60, TimeUnit.SECONDS); // 30초 후 시작, 60초마다 로깅

            // 모든 태스크가 완료될 때까지 대기 (최대 30분)
            boolean allCompleted = latch.await(30, TimeUnit.MINUTES);
            progressLogger.shutdown(); // 진행 상황 로깅 종료

            // 처리 시간 계산 및 저장
            long elapsedTime = Duration.between(startTime, LocalDateTime.now()).toMillis();
            processingTimeByPuid.put(puid, elapsedTime);

            if (allCompleted) {
                log.info("PUID {}의 모든 모델 생성 태스크 완료. 총 소요 시간: {}ms, 생성된 모델 수: {}", 
                        puid, elapsedTime, completedModelUrlsByPuid.getOrDefault(puid, new ConcurrentHashMap<>()).size());
            } else {
                long completed = keywordCount - latch.getCount();
                log.warn("PUID {}의 일부 모델 생성 태스크가 제한 시간 내에 완료되지 않았습니다. 완료된 태스크: {}/{}, 생성된 모델 수: {}", 
                        puid, completed, keywordCount, 
                        completedModelUrlsByPuid.getOrDefault(puid, new ConcurrentHashMap<>()).size());
            }

        } catch (Exception e) {
            log.error("시나리오 키워드 처리 중 오류 발생: {}", e.getMessage(), e);
        }
    }

    /**
     * 단일 모델 생성 처리
     */
    private void processModelGeneration(String puid, String modelName, String prompt, Map<String, MeshyTask> tasks) {
        try {
            log.info("모델 생성 시작: {} (PUID: {})", modelName, puid);

            // 1. Preview 태스크 생성
            String previewTaskId = meshyApi.createPreviewTask(prompt);
            MeshyTask previewTask = createAndTrackTask(puid, modelName + "_preview", previewTaskId, 
                    MeshyTask.TaskType.PREVIEW, prompt, tasks);

            // 2. Preview 완료 대기
            MeshyAPI.TaskStatus previewStatus = waitForTaskCompletion(previewTaskId);
            updateTaskStatus(previewTask, previewStatus);

            if (previewTask.getStatus() != MeshyTask.TaskStatus.SUCCEEDED) {
                log.error("Preview 태스크 실패: {}", previewTask.getErrorMessage());
                return;
            }

            // 3. Refine 태스크 생성
            String texturePrompt = "High quality PBR textures for " + prompt;
            String refineTaskId = meshyApi.createRefineTask(previewTaskId, true, texturePrompt);
            MeshyTask refineTask = createAndTrackTask(puid, modelName, refineTaskId, 
                    MeshyTask.TaskType.REFINE, texturePrompt, tasks);
            refineTask.setParentTaskId(previewTaskId);

            // 4. Refine 완료 대기
            MeshyAPI.TaskStatus refineStatus = waitForTaskCompletion(refineTaskId);
            updateTaskStatus(refineTask, refineStatus);

            if (refineTask.getStatus() == MeshyTask.TaskStatus.SUCCEEDED) {
                // 성공한 모델 URL 저장
                saveCompletedModelUrls(puid, modelName, refineStatus.modelUrls);
                log.info("모델 생성 완료: {} (PUID: {})", modelName, puid);
            } else {
                log.error("Refine 태스크 실패: {}", refineTask.getErrorMessage());
            }

        } catch (Exception e) {
            log.error("모델 생성 처리 중 오류: {}", e.getMessage(), e);
        }
    }

    /**
     * 태스크 생성 및 추적
     */
    private MeshyTask createAndTrackTask(String puid, String modelName, String taskId, 
                                        MeshyTask.TaskType taskType, String prompt,
                                        Map<String, MeshyTask> tasks) throws IOException {
        MeshyAPI.TaskStatus initialStatus = meshyApi.getTaskStatus(taskId);
        MeshyTask task = meshyApi.createMeshyTask(initialStatus, taskType, prompt);
        tasks.put(modelName, task);
        return task;
    }

    /**
     * 태스크 완료 대기
     */
    private MeshyAPI.TaskStatus waitForTaskCompletion(String taskId) throws IOException, InterruptedException {
        MeshyAPI.TaskStatus status;
        int attempts = 0;
        final int maxAttempts = 300;  // 최대 5분 (1초 간격으로 300회)

        do {
            Thread.sleep(1000);  // 1초 대기
            status = meshyApi.getTaskStatus(taskId);
            attempts++;

            if (attempts % 10 == 0) {
                log.info("태스크 {} 진행 상황: {}% (상태: {})", taskId, status.progress, status.status);
            }

            // 성공 또는 실패 상태면 바로 반환
            if ("SUCCEEDED".equals(status.status) || "FAILED".equals(status.status) || "CANCELED".equals(status.status)) {
                break;
            }

        } while (attempts < maxAttempts);

        if (attempts >= maxAttempts) {
            log.warn("태스크 {} 시간 초과 (마지막 상태: {})", taskId, status.status);
        }

        return status;
    }

    /**
     * 태스크 상태 업데이트
     */
    private void updateTaskStatus(MeshyTask task, MeshyAPI.TaskStatus apiStatus) {
        task.setStatus(MeshyTask.TaskStatus.fromApiStatus(apiStatus.status));
        task.setProgress(apiStatus.progress);
        task.setLastUpdatedAt(LocalDateTime.now());
        task.setThumbnailUrl(apiStatus.thumbnailUrl);
        task.setModelUrls(apiStatus.modelUrls);
        task.setErrorMessage(apiStatus.error);
    }

    /**
     * 완료된 모델 URL 저장
     */
    private void saveCompletedModelUrls(String puid, String modelName, MeshyAPI.TaskStatus.ModelUrls modelUrls) {
        Map<String, String> urlMap = completedModelUrlsByPuid.get(puid);
        if (urlMap == null) return;

        if (modelUrls.glb != null) urlMap.put(modelName + "_glb", modelUrls.glb);
        if (modelUrls.fbx != null) urlMap.put(modelName + "_fbx", modelUrls.fbx);
        if (modelUrls.obj != null) urlMap.put(modelName + "_obj", modelUrls.obj);
        if (modelUrls.mtl != null) urlMap.put(modelName + "_mtl", modelUrls.mtl);
        if (modelUrls.usdz != null) urlMap.put(modelName + "_usdz", modelUrls.usdz);

        // 기본 형식 (FBX)도 등록
        if (modelUrls.fbx != null) urlMap.put(modelName, modelUrls.fbx);
    }

    /**
     * 완료된 모델 URL 조회
     */
    public Map<String, String> getCompletedModelUrls(String puid) {
        return completedModelUrlsByPuid.getOrDefault(puid, new HashMap<>());
    }

    /**
     * 모델 URL 갱신
     */
    public Map<String, String> refreshModelUrls(String puid) {
        log.info("PUID {}의 모델 URL 갱신 시작", puid);
        Map<String, MeshyTask> tasks = tasksByPuid.get(puid);
        if (tasks == null || tasks.isEmpty()) {
            log.warn("PUID {}의 태스크 정보가 없습니다.", puid);
            return new HashMap<>();
        }

        Map<String, String> refreshedUrls = new HashMap<>();
        for (Map.Entry<String, MeshyTask> entry : tasks.entrySet()) {
            String modelName = entry.getKey();
            MeshyTask task = entry.getValue();

            // Refine 태스크만 처리 (Preview 제외)
            if (task.getTaskType() != MeshyTask.TaskType.REFINE) continue;

            try {
                log.info("모델 {} URL 갱신 시도", modelName);
                MeshyAPI.TaskStatus status = meshyApi.getTaskStatus(task.getTaskId());
                updateTaskStatus(task, status);

                if (task.getStatus() == MeshyTask.TaskStatus.SUCCEEDED && task.getModelUrls() != null) {
                    saveCompletedModelUrls(puid, modelName, task.getModelUrls());
                    log.info("모델 {} URL 갱신 완료", modelName);
                } else {
                    log.warn("모델 {} URL 갱신 실패: {}", modelName, task.getStatus());
                }
            } catch (Exception e) {
                log.error("모델 {} URL 갱신 중 오류: {}", modelName, e.getMessage());
            }
        }

        // 갱신된 URL 맵 반환
        refreshedUrls = getCompletedModelUrls(puid);
        log.info("PUID {}의 모델 URL 갱신 완료: {} 개의 URL", puid, refreshedUrls.size());
        return refreshedUrls;
    }

    /**
     * 모델 처리 시간 조회
     */
    public Long getModelProcessingTime(String puid) {
        return processingTimeByPuid.get(puid);
    }
}
