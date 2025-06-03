package com.febrie.service;

import com.febrie.api.MeshyAPI;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 시나리오에서 생성된 키워드로 Meshy API를 통해 3D 모델을 생성하고
 * 결과를 클라이언트에 전송하는 기능을 제공하는 서비스 클래스
 */
@Slf4j
public class MeshyModelService {

    private static volatile MeshyModelService instance;
    private final MeshyAPI meshyAPI;
    private final ExecutorService executorService;
    private final Map<String, Map<String, String>> modelUrlCache; // puid -> (modelName -> fbxUrl)

    private MeshyModelService() {
        this.meshyAPI = MeshyAPI.getInstance();
        this.executorService = Executors.newFixedThreadPool(5); // 5개 스레드 병렬 처리
        this.modelUrlCache = new ConcurrentHashMap<>();
    }

    public static MeshyModelService getInstance() {
        if (instance == null) {
            synchronized (MeshyModelService.class) {
                if (instance == null) {
                    instance = new MeshyModelService();
                }
            }
        }
        return instance;
    }

    /**
     * 시나리오에서 키워드를 추출하여 Meshy API에 3D 모델 생성 요청을 보내고
     * 생성된 모델의 FBX URL을 준비합니다.
     *
     * @param puid           사용자 ID
     * @param scenarioResult 시나리오 생성 결과
     */
    public void processScenarioKeywords(String puid, @NotNull JsonObject scenarioResult) {
        log.info("[{}] 시나리오 키워드 처리 시작", puid);

        if (!scenarioResult.has("keywords") || !scenarioResult.get("keywords").isJsonArray()) {
            log.error("[{}] 시나리오에 keywords 배열이 없습니다.", puid);
            return;
        }

        JsonArray keywords = scenarioResult.getAsJsonArray("keywords");
        if (keywords.isEmpty()) {
            log.warn("[{}] 시나리오에 키워드가 없습니다.", puid);
            return;
        }

        // 기존에 생성된 모델이 있는지 확인
        if (modelUrlCache.containsKey(puid)) {
            log.info("[{}] 기존 생성된 모델 캐시 발견, 재사용합니다.", puid);
            return;
        }

        // 키워드를 기반으로 비동기적으로 모델 생성 요청
        log.info("[{}] {} 개의 키워드에 대해 Meshy API 모델 생성 요청 시작", puid, keywords.size());
        CompletableFuture.runAsync(() -> createModelsFromKeywords(puid, keywords), executorService);

        // 요청은 백그라운드에서 계속 진행됩니다
    }

    /**
     * 키워드 목록에서 모델을 생성하는 비동기 프로세스
     *
     * @param puid     사용자 ID
     * @param keywords 키워드 배열
     */
    private void createModelsFromKeywords(String puid, @NotNull JsonArray keywords) {
        Map<String, String> modelUrls = new HashMap<>();
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (int i = 0; i < keywords.size(); i++) {
            try {
                JsonObject keyword = keywords.get(i).getAsJsonObject();
                String objectName = keyword.get("name").getAsString();
                String modelPrompt = keyword.get("value").getAsString();

                // 각 키워드에 대해 비동기적으로 모델 생성 요청
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        // 모델 생성 요청 (API 기본값 사용)
                        String fbxUrl = requestModelAndWaitForCompletion(objectName, modelPrompt);
                        if (fbxUrl != null) {
                            synchronized (modelUrls) {
                                modelUrls.put(objectName, fbxUrl);
                            }
                            // 새 모델이 생성되면 캐시 업데이트
                            updateModelCache(puid, objectName, fbxUrl);
                        }
                    } catch (Exception e) {
                        log.error("[{}] 모델 '{}' 생성 중 오류: {}", puid, objectName, e.getMessage());
                    }
                }, executorService);

                futures.add(future);
            } catch (Exception e) {
                log.error("[{}] 키워드 처리 중 오류: {}", puid, e.getMessage());
            }
        }

        // 모든 요청이 완료될 때까지 기다리지 않고 백그라운드에서 진행
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenAccept(v -> log.info("[{}] 모든 모델 생성 요청 완료. 생성된 모델: {}", puid, modelUrls.size()))
                .exceptionally(e -> {
                    log.error("[{}] 일부 모델 생성 요청 실패: {}", puid, e.getMessage());
                    return null;
                });
    }

    /**
     * Meshy API를 통해 모델을 생성하고 완료될 때까지 대기한 후 FBX URL을 반환
     *
     * @param objectName  객체 이름
     * @param modelPrompt 모델 생성 프롬프트
     * @return FBX URL 또는 null (실패 시)
     */
    private String requestModelAndWaitForCompletion(String objectName, String modelPrompt) {
        try {
            log.info("[{}] 모델 생성 요청 시작", objectName);

            // Preview 모델 생성 요청
            String taskId = meshyAPI.createPreviewTask(modelPrompt);
            log.info("[{}] Preview 태스크 ID: {}", objectName, taskId);

            // 모델 생성 완료 대기
            MeshyAPI.TaskStatus status = waitForTaskCompletion(taskId, objectName);

            // 성공 여부 확인 및 URL 반환
            if (status != null && "SUCCEEDED".equals(status.status) && status.modelUrls != null) {
                log.info("[{}] 모델 생성 성공: {}", objectName, status.modelUrls.fbx);
                return status.modelUrls.fbx;
            } else {
                String errorMsg = (status != null && status.error != null) ? status.error : "알 수 없는 오류";
                log.error("[{}] 모델 생성 실패: {}", objectName, errorMsg);
                return null;
            }

        } catch (Exception e) {
            log.error("[{}] 모델 요청 중 예외 발생: {}", objectName, e.getMessage());
            return null;
        }
    }

    /**
     * 태스크가 완료될 때까지 주기적으로 상태를 확인
     *
     * @param taskId     태스크 ID
     * @param objectName 객체 이름
     * @return 최종 태스크 상태 또는 null (실패 시)
     */
    @Nullable
    private MeshyAPI.TaskStatus waitForTaskCompletion(String taskId, String objectName) throws IOException, InterruptedException {
        MeshyAPI.TaskStatus status = null;
        boolean isCompleted = false;
        int maxRetries = 60; // 최대 60번 재시도 (10초 간격으로 총 10분)
        int retryCount = 0;

        while (!isCompleted && retryCount < maxRetries) {
            status = meshyAPI.getTaskStatus(taskId);

            if (status == null) {
                log.error("[{}] 태스크 상태 조회 실패: {}", objectName, taskId);
                return null;
            }

            log.debug("[{}] 태스크 상태: {} - 진행률: {}%", objectName, status.status, status.progress);

            // 완료 상태 확인
            if ("SUCCEEDED".equals(status.status) || "FAILED".equals(status.status) || "CANCELED".equals(status.status)) {
                isCompleted = true;
            } else {
                // 10초 대기 후 다시 확인
                Thread.sleep(10000);
                retryCount++;
            }
        }

        if (!isCompleted) {
            log.error("[{}] 태스크 완료 대기 시간 초과: {}", objectName, taskId);
        }

        return status;
    }

    /**
     * 빈 응답 JSON 객체 생성
     *
     * @return 빈 모델 목록을 포함하는 JSON 객체
     */
    private JsonObject createEmptyResponse() {
        JsonObject response = new JsonObject();
        response.add("models", new JsonObject());
        return response;
    }

    // createResponseFromCache 메소드는 제거되었습니다 - 불필요한 코드 정리

    /**
     * 생성된 모델 URL을 캐시에 업데이트
     *
     * @param puid      사용자 ID
     * @param modelName 모델 이름
     * @param fbxUrl    FBX URL
     */
    private void updateModelCache(String puid, String modelName, String fbxUrl) {
        modelUrlCache.computeIfAbsent(puid, k -> new ConcurrentHashMap<>())
                .put(modelName, fbxUrl);
        log.info("[{}] 모델 캐시 업데이트: {} -> {}", puid, modelName, fbxUrl);
    }

    /**
     * 사용자의 모델 캐시를 초기화합니다.
     *
     * @param puid 사용자 ID
     */
    public void clearModelCache(String puid) {
        modelUrlCache.remove(puid);
        log.info("[{}] 모델 캐시 초기화 완료", puid);
    }

    /**
     * 서비스를 종료합니다.
     * 애플리케이션 종료 시 호출해야 합니다.
     */
    public void shutdown() {
        executorService.shutdown();
        log.info("MeshyModelService 종료됨");
    }
}
