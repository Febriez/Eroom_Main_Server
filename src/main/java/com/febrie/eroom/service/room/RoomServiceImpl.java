package com.febrie.eroom.service.room;

import com.febrie.eroom.config.ConfigurationManager;
import com.febrie.eroom.model.ModelGenerationResult;
import com.febrie.eroom.model.RoomCreationRequest;
import com.febrie.eroom.service.ai.AiService;
import com.febrie.eroom.service.mesh.MeshService;
import com.febrie.eroom.service.validation.DefaultScenarioValidator;
import com.febrie.eroom.service.validation.RequestValidator;
import com.febrie.eroom.service.validation.RoomRequestValidator;
import com.febrie.eroom.service.validation.ScenarioValidator;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;

public class RoomServiceImpl implements RoomService, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(RoomServiceImpl.class);

    // 타임아웃 및 병렬 처리 상수
    private static final int MODEL_TIMEOUT_MINUTES = 10;
    private static final int EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS = 60;
    private static final int PARALLEL_THRESHOLD = 10;
    private static final int BATCH_SIZE = 5;
    private static final int FIRST_BATCH_SIZE = 5;
    private static final int THREAD_POOL_SIZE = 10;

    // 오브젝트 타입 상수
    private static final String TYPE_GAME_MANAGER = "game_manager";
    private static final String TYPE_EXISTING_INTERACTIVE = "existing_interactive_object";
    private static final String TYPE_INTERACTIVE = "interactive_object";

    private final AiService aiService;
    private final MeshService meshService;
    private final ConfigurationManager configManager;
    private final ExecutorService executorService;
    private final RequestValidator requestValidator;
    private final ScenarioValidator scenarioValidator;

    public RoomServiceImpl(AiService aiService, MeshService meshService, ConfigurationManager configManager) {
        this.aiService = aiService;
        this.meshService = meshService;
        this.configManager = configManager;
        this.executorService = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        this.requestValidator = new RoomRequestValidator();
        this.scenarioValidator = new DefaultScenarioValidator();
    }

    @Override
    public JsonObject createRoom(@NotNull RoomCreationRequest request, String ruid) {
        log.info("통합 방 생성 시작: ruid={}, user_uuid={}, theme={}, difficulty={}",
                ruid, request.getUuid(), request.getTheme(), request.getValidatedDifficulty());

        try {
            requestValidator.validate(request);
        } catch (IllegalArgumentException e) {
            return createErrorResponse(request, ruid, e.getMessage());
        }

        try {
            JsonObject scenario = createIntegratedScenario(request, ruid);
            List<CompletableFuture<ModelGenerationResult>> modelFutures = startModelGeneration(scenario);
            Map<String, String> allScripts = createUnifiedScripts(scenario);
            JsonObject modelTracking = waitForModels(modelFutures);

            JsonObject response = buildFinalResponse(request, ruid, scenario, allScripts, modelTracking);
            log.info("통합 방 생성 완료: ruid={}, 스크립트 수={}", ruid, allScripts.size());
            return response;

        } catch (RuntimeException e) {
            log.error("통합 방 생성 중 비즈니스 오류 발생: ruid={}", ruid, e);
            return createErrorResponse(request, ruid, e.getMessage());
        } catch (Exception e) {
            log.error("통합 방 생성 중 시스템 오류 발생: ruid={}", ruid, e);
            return createErrorResponse(request, ruid, "시스템 오류가 발생했습니다");
        }
    }

    @NotNull
    private JsonObject createIntegratedScenario(RoomCreationRequest request, String ruid) {
        try {
            // ExitDoor 존재 검증
            boolean hasExitDoor = request.getExistingObjectsSafe().stream()
                    .anyMatch(obj -> "ExitDoor".equalsIgnoreCase(obj.getName()));

            if (!hasExitDoor) {
                throw new RuntimeException("ExitDoor가 existing_objects에 포함되어야 합니다.");
            }

            String prompt = configManager.getPrompt("scenario");
            JsonObject scenarioRequest = buildScenarioRequest(request, ruid);

            log.info("LLM에 시나리오 생성 요청. ruid: '{}', Theme: '{}', Difficulty: '{}'",
                    ruid, request.getTheme().trim(), request.getValidatedDifficulty());

            JsonObject scenario = aiService.generateScenario(prompt, scenarioRequest);
            if (scenario == null) {
                throw new RuntimeException("통합 시나리오 생성 실패: LLM 응답이 null입니다.");
            }

            scenarioValidator.validate(scenario);

            log.info("통합 시나리오 생성 완료. ruid: {}, 오브젝트 설명 {}개",
                    ruid, scenario.getAsJsonArray("object_instructions").size());

            return scenario;

        } catch (Exception e) {
            throw new RuntimeException("통합 시나리오 생성 단계에서 오류 발생: " + e.getMessage(), e);
        }
    }

    @NotNull
    private JsonObject buildScenarioRequest(@NotNull RoomCreationRequest request, String ruid) {
        JsonObject scenarioRequest = new JsonObject();

        // 기본 정보 설정
        scenarioRequest.addProperty("uuid", request.getUuid());
        scenarioRequest.addProperty("ruid", ruid);
        scenarioRequest.addProperty("theme", request.getTheme().trim());
        scenarioRequest.addProperty("difficulty", request.getValidatedDifficulty());

        // Keywords 배열 추가
        scenarioRequest.add("keywords", createKeywordsArray(request.getKeywords()));

        // Existing objects 배열 추가
        JsonArray existingObjectsArray = convertExistingObjectsToJsonArray(request.getExistingObjectsSafe());
        scenarioRequest.add("existing_objects", existingObjectsArray);

        // 추가 메타데이터
        scenarioRequest.addProperty("existing_objects_count", existingObjectsArray.size());

        log.info("시나리오 요청 생성 완료 - keywords: {}, existing_objects: {}",
                request.getKeywords().length, existingObjectsArray.size());

        return scenarioRequest;
    }

    @NotNull
    private JsonArray convertExistingObjectsToJsonArray(@NotNull List<RoomCreationRequest.ExistingObject> existingObjects) {
        JsonArray array = new JsonArray();
        for (RoomCreationRequest.ExistingObject obj : existingObjects) {
            JsonObject jsonObj = new JsonObject();
            jsonObj.addProperty("name", obj.getName());
            jsonObj.addProperty("id", obj.getId());
            array.add(jsonObj);
        }
        return array;
    }

    @NotNull
    private List<CompletableFuture<ModelGenerationResult>> startModelGeneration(@NotNull JsonObject scenario) {
        List<CompletableFuture<ModelGenerationResult>> futures = new ArrayList<>();
        JsonArray objectInstructions = scenario.getAsJsonArray("object_instructions");

        if (objectInstructions == null || objectInstructions.isEmpty()) {
            log.warn("오브젝트 설명(object_instructions)이 없어 3D 모델 생성을 건너뜁니다");
            return futures;
        }

        log.info("3D 모델 생성 시작: {} 개의 오브젝트 인스트럭션", objectInstructions.size());

        for (int i = 0; i < objectInstructions.size(); i++) {
            JsonObject instruction = objectInstructions.get(i).getAsJsonObject();

            if (shouldSkipModelGeneration(instruction)) {
                continue;
            }

            String objectName = instruction.get("name").getAsString();
            String visualDescription = instruction.get("visual_description").getAsString();

            if (isValidForModelGeneration(objectName, visualDescription)) {
                futures.add(createModelTask(visualDescription, objectName, i));
            }
        }

        log.info("모델 생성 태스크 총 {}개 추가 완료 (키워드 기반 새 오브젝트만)", futures.size());
        return futures;
    }

    private boolean shouldSkipModelGeneration(@NotNull JsonObject instruction) {
        String type = instruction.has("type") ? instruction.get("type").getAsString() : "";

        if (TYPE_GAME_MANAGER.equals(type)) {
            log.debug("GameManager는 모델 생성에서 건너뜁니다.");
            return true;
        }

        if (TYPE_EXISTING_INTERACTIVE.equals(type)) {
            log.debug("기존 오브젝트 '{}'는 모델 생성에서 건너뜁니다.",
                    instruction.get("name").getAsString());
            return true;
        }

        // visual_description이 있는 새 오브젝트만 모델 생성
        if (!instruction.has("visual_description")) {
            log.debug("visual_description이 없는 오브젝트 '{}'는 모델 생성에서 건너뜁니다.",
                    instruction.has("name") ? instruction.get("name").getAsString() : "unknown");
            return true;
        }

        return false;
    }

    private boolean isValidForModelGeneration(String name, String description) {
        if (name == null || name.trim().isEmpty()) {
            log.warn("오브젝트 이름이 비어있습니다.");
            return false;
        }
        if (description == null || description.trim().isEmpty()) {
            log.warn("오브젝트 설명이 비어있습니다: {}", name);
            return false;
        }
        return true;
    }

    @NotNull
    @Contract("_, _, _ -> new")
    private CompletableFuture<ModelGenerationResult> createModelTask(String prompt, String name, int index) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                log.debug("3D 모델 생성 요청 [{}]: name='{}', prompt='{}자'", index, name, prompt.length());
                String trackingId = meshService.generateModel(prompt, name, index);

                String resultId = (trackingId != null && !trackingId.trim().isEmpty())
                        ? trackingId
                        : "pending-" + UUID.randomUUID().toString().substring(0, 8);

                return new ModelGenerationResult(name, resultId);
            } catch (Exception e) {
                log.error("모델 생성 실패: {} - {}", name, e.getMessage());
                return new ModelGenerationResult(name, "error-" + UUID.randomUUID().toString().substring(0, 8));
            }
        }, executorService);
    }

    @NotNull
    private Map<String, String> createUnifiedScripts(JsonObject scenario) {
        try {
            JsonArray objectInstructions = scenario.getAsJsonArray("object_instructions");
            int totalObjects = objectInstructions != null ? objectInstructions.size() : 0;

            log.info("=== 스크립트 생성 시작 ===");
            log.info("총 오브젝트 수: {}", totalObjects);
            log.info("병렬 처리 임계값: {}", PARALLEL_THRESHOLD);
            log.info("모드: {}", totalObjects < PARALLEL_THRESHOLD ? "단일 요청" : "병렬 처리");

            if (totalObjects < PARALLEL_THRESHOLD) {
                log.info("단일 요청 모드 사용 (오브젝트 {}개)", totalObjects);
                return createUnifiedScriptsSingleRequest(scenario);
            } else {
                log.info("병렬 처리 모드 사용 (오브젝트 {}개)", totalObjects);
                return createUnifiedScriptsParallel(scenario);
            }

        } catch (Exception e) {
            throw new RuntimeException("스크립트 생성 실패: " + e.getMessage(), e);
        }
    }

    private Map<String, String> createUnifiedScriptsSingleRequest(JsonObject scenario) {
        String prompt = configManager.getPrompt("unified_scripts");
        JsonObject scriptRequest = buildScriptRequest(scenario);
        return aiService.generateUnifiedScripts(prompt, scriptRequest);
    }

    private Map<String, String> createUnifiedScriptsParallel(JsonObject scenario) {
        Map<String, String> allScripts = new ConcurrentHashMap<>();

        // GameManager와 다른 오브젝트 분리
        List<JsonObject> gameManagerList = new ArrayList<>();
        List<JsonObject> otherObjects = new ArrayList<>();

        separateGameManagerAndObjects(scenario.getAsJsonArray("object_instructions"),
                gameManagerList, otherObjects);

        log.info("병렬 처리 시작: GameManager {} 개 + 기타 오브젝트 {} 개",
                gameManagerList.size(), otherObjects.size());

        // 첫 번째 배치 처리 (GameManager 포함)
        String gameManagerScript = processFirstBatch(scenario,
                gameManagerList, otherObjects, allScripts);

        // 나머지 배치 병렬 처리
        processRemainingBatches(scenario, otherObjects,
                gameManagerScript, allScripts);

        log.info("병렬 스크립트 생성 완료: 총 {} 개", allScripts.size());
        return allScripts;
    }

    private void separateGameManagerAndObjects(JsonArray objectInstructions,
                                               List<JsonObject> gameManagerList,
                                               List<JsonObject> otherObjects) {
        for (int i = 0; i < objectInstructions.size(); i++) {
            JsonObject obj = objectInstructions.get(i).getAsJsonObject();
            if (TYPE_GAME_MANAGER.equals(obj.get("type").getAsString())) {
                gameManagerList.add(obj);
            } else {
                otherObjects.add(obj);
            }
        }
    }

    @NotNull
    private String processFirstBatch(JsonObject scenario,
                                     List<JsonObject> gameManagerList,
                                     List<JsonObject> otherObjects,
                                     Map<String, String> allScripts) {
        // 첫 배치 구성
        int firstBatchSize = Math.min(FIRST_BATCH_SIZE, otherObjects.size());
        List<JsonObject> firstBatch = new ArrayList<>(gameManagerList);
        firstBatch.addAll(otherObjects.subList(0, firstBatchSize));

        log.info("첫 번째 배치: GameManager {} 개 + 오브젝트 {} 개 = 총 {} 개",
                gameManagerList.size(), firstBatchSize, firstBatch.size());

        // 첫 배치 동기 실행
        JsonObject firstBatchRequest = buildBatchRequest(scenario,
                firstBatch, true, null);

        Map<String, String> firstBatchResult = aiService.generateUnifiedScripts(
                configManager.getPrompt("unified_scripts"), firstBatchRequest);

        allScripts.putAll(firstBatchResult);

        // GameManager 스크립트 추출 및 검증
        String gameManagerScript = firstBatchResult.get("GameManager");
        if (gameManagerScript == null || gameManagerScript.isEmpty()) {
            throw new RuntimeException("GameManager 스크립트 생성 실패");
        }

        log.info("GameManager 생성 완료, 첫 배치에서 {} 개 스크립트 생성", firstBatchResult.size());
        return gameManagerScript;
    }

    private void processRemainingBatches(JsonObject scenario,
                                         List<JsonObject> allObjects,
                                         String gameManagerScript,
                                         Map<String, String> allScripts) {
        List<JsonObject> remainingObjects = allObjects.subList(
                Math.min(FIRST_BATCH_SIZE, allObjects.size()), allObjects.size());

        if (remainingObjects.isEmpty()) {
            return;
        }

        List<CompletableFuture<Map<String, String>>> futures = new ArrayList<>();

        // 배치별로 나누어 처리
        for (int i = 0; i < remainingObjects.size(); i += BATCH_SIZE) {
            int batchEnd = Math.min(i + BATCH_SIZE, remainingObjects.size());
            List<JsonObject> batch = remainingObjects.subList(i, batchEnd);

            int absoluteBatchStart = FIRST_BATCH_SIZE + i;
            log.info("배치 생성: 오브젝트 {}-{} ({}개)",
                    absoluteBatchStart + 1, FIRST_BATCH_SIZE + batchEnd, batch.size());

            CompletableFuture<Map<String, String>> future = CompletableFuture.supplyAsync(() ->
                            generateBatchScripts(batch, scenario,
                                    absoluteBatchStart, gameManagerScript),
                    executorService
            );

            futures.add(future);
        }

        // 모든 배치 완료 대기
        waitForAllBatches(futures, allScripts);
    }

    private void waitForAllBatches(List<CompletableFuture<Map<String, String>>> futures,
                                   Map<String, String> allScripts) {
        try {
            CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                    futures.toArray(new CompletableFuture[0])
            );

            allFutures.get(5, TimeUnit.MINUTES);

            // 결과 수집
            for (CompletableFuture<Map<String, String>> future : futures) {
                allScripts.putAll(future.get());
            }

        } catch (TimeoutException e) {
            log.error("스크립트 생성 타임아웃");
            throw new RuntimeException("스크립트 생성 시간 초과");
        } catch (Exception e) {
            log.error("병렬 처리 중 오류", e);
            throw new RuntimeException("병렬 스크립트 생성 실패: " + e.getMessage());
        }
    }

    @NotNull
    private JsonObject buildBatchRequest(JsonObject scenario,
                                         List<JsonObject> batch, boolean isFirstBatch,
                                         String gameManagerScript) {
        JsonObject request = new JsonObject();
        request.add("scenario_data", scenario.getAsJsonObject("scenario_data"));

        JsonArray batchArray = new JsonArray();
        batch.forEach(batchArray::add);
        request.add("object_instructions", batchArray);

        // 배치에 포함된 오브젝트 이름 로깅
        List<String> objectNames = new ArrayList<>();
        for (JsonObject obj : batch) {
            objectNames.add(obj.get("name").getAsString());
        }
        log.debug("배치 오브젝트 목록: {}", String.join(", ", objectNames));

        if (isFirstBatch) {
            request.addProperty("is_first_batch", true);
            request.addProperty("total_objects", scenario.getAsJsonArray("object_instructions").size());
        } else {
            request.addProperty("game_manager_script", gameManagerScript);
            request.addProperty("total_objects", scenario.getAsJsonArray("object_instructions").size());
        }

        // model_scales 처리
        addModelScalesToRequest(scenario, batch, request);

        return request;
    }

    private void addModelScalesToRequest(JsonObject scenario, List<JsonObject> batch, JsonObject request) {
        if (!scenario.has("model_scales")) {
            return;
        }

        JsonObject allScales = scenario.getAsJsonObject("model_scales");
        JsonObject batchScales = new JsonObject();

        batch.forEach(obj -> {
            String name = obj.get("name").getAsString();
            if (allScales.has(name)) {
                batchScales.add(name, allScales.get(name));
            }
        });

        if (!batchScales.isEmpty()) {
            request.add("model_scales", batchScales);
        }
    }

    private Map<String, String> generateBatchScripts(List<JsonObject> batch,
                                                     JsonObject scenario,
                                                     int batchStartIndex,
                                                     String gameManagerScript) {
        try {
            String prompt = configManager.getPrompt("scripts_batch");
            JsonObject request = buildBatchRequest(scenario,
                    batch, false, gameManagerScript);

            request.addProperty("batch_index", batchStartIndex);

            log.info("배치 {} API 호출 중... (오브젝트 {}개)",
                    batchStartIndex / BATCH_SIZE + 1, batch.size());

            Map<String, String> result = aiService.generateUnifiedScripts(prompt, request);

            log.info("배치 {} 완료: {} 개 스크립트 생성됨 (예상: {} 개)",
                    batchStartIndex / BATCH_SIZE + 1, result.size(), batch.size());

            // 스크립트 수 검증
            if (result.size() < batch.size()) {
                log.warn("배치 {}: 생성된 스크립트 수({})가 오브젝트 수({})보다 적습니다. 누락된 오브젝트를 확인하세요.",
                        batchStartIndex / BATCH_SIZE + 1, result.size(), batch.size());

                // 누락된 오브젝트 이름 출력
                Set<String> generatedNames = result.keySet();
                for (JsonObject obj : batch) {
                    String objName = obj.get("name").getAsString();
                    if (!generatedNames.contains(objName) && !generatedNames.contains(objName + "C")) {
                        log.warn("누락된 오브젝트 스크립트: {}", objName);
                    }
                }
            }

            return result;

        } catch (Exception e) {
            log.error("배치 {} 생성 실패", batchStartIndex / BATCH_SIZE + 1, e);
            return new HashMap<>();
        }
    }

    @NotNull
    private JsonObject buildScriptRequest(@NotNull JsonObject scenario) {
        JsonObject scriptRequest = new JsonObject();
        scriptRequest.add("scenario_data", scenario.getAsJsonObject("scenario_data"));
        scriptRequest.add("object_instructions", scenario.getAsJsonArray("object_instructions"));

        if (scenario.has("model_scales")) {
            scriptRequest.add("model_scales", scenario.getAsJsonObject("model_scales"));
        }

        return scriptRequest;
    }

    @NotNull
    private JsonObject waitForModels(@NotNull List<CompletableFuture<ModelGenerationResult>> futures) {
        if (futures.isEmpty()) {
            return createEmptyTracking();
        }

        JsonObject tracking = new JsonObject();
        JsonObject failedModels = new JsonObject();

        log.info("3D 모델 생성 완료 대기 중: {} 개 (최대 {}분)", futures.size(), MODEL_TIMEOUT_MINUTES);

        try {
            CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                    futures.toArray(new CompletableFuture[0])
            );
            allFutures.get(MODEL_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            collectResults(futures, tracking, failedModels);
        } catch (TimeoutException e) {
            handleTimeout(futures, tracking, failedModels);
        } catch (Exception e) {
            log.error("모델 생성 대기 중 오류 발생", e);
        }

        if (!failedModels.asMap().isEmpty()) {
            tracking.add("failed_models", failedModels);
        }

        return tracking.asMap().isEmpty() ? createEmptyTracking() : tracking;
    }

    private void collectResults(@NotNull List<CompletableFuture<ModelGenerationResult>> futures,
                                JsonObject tracking, JsonObject failedModels) {
        for (int i = 0; i < futures.size(); i++) {
            try {
                ModelGenerationResult result = futures.get(i).get();
                addTrackingResult(tracking, failedModels, result);
            } catch (Exception e) {
                log.error("모델 결과 수집 실패: index={}", i, e);
                failedModels.addProperty("error_" + i, "collection_error-" + System.currentTimeMillis());
            }
        }
    }

    private void handleTimeout(@NotNull List<CompletableFuture<ModelGenerationResult>> futures,
                               JsonObject tracking, JsonObject failedModels) {
        log.warn("모델 생성 타임아웃 발생, 현재까지 완료된 결과만 수집");

        for (int i = 0; i < futures.size(); i++) {
            CompletableFuture<ModelGenerationResult> future = futures.get(i);
            if (future.isDone() && !future.isCompletedExceptionally()) {
                try {
                    addTrackingResult(tracking, failedModels, future.get());
                } catch (Exception ex) {
                    log.debug("타임아웃 후 결과 수집 실패: index={}", i);
                }
            } else {
                failedModels.addProperty("timeout_" + i, "timeout-" + System.currentTimeMillis());
            }
        }
    }

    private void addTrackingResult(JsonObject tracking, JsonObject failedModels, ModelGenerationResult result) {
        if (result == null || result.getObjectName() == null || result.getObjectName().trim().isEmpty()) {
            log.warn("유효하지 않은 모델 결과: {}", result);
            return;
        }

        String objectName = result.getObjectName().trim();
        String trackingId = result.getTrackingId();

        if (trackingId != null && !trackingId.trim().isEmpty()) {
            if (trackingId.startsWith("error-") || trackingId.startsWith("timeout-")) {
                failedModels.addProperty(objectName, trackingId);
                log.warn("모델 생성 실패로 표시됨: {} -> {}", objectName, trackingId);
            } else {
                tracking.addProperty(objectName, trackingId.trim());
                log.debug("모델 추적 ID 추가: {} -> {}", objectName, trackingId);
            }
        } else {
            String fallbackId = "no-tracking-" + System.currentTimeMillis();
            failedModels.addProperty(objectName, fallbackId);
            log.warn("trackingId가 없어 실패로 표시: {} -> {}", objectName, fallbackId);
        }
    }

    @NotNull
    private JsonObject createEmptyTracking() {
        JsonObject empty = new JsonObject();
        empty.addProperty("status", "no_models_generated");
        empty.addProperty("timestamp", String.valueOf(System.currentTimeMillis()));
        return empty;
    }

    @NotNull
    private JsonObject buildFinalResponse(@NotNull RoomCreationRequest request, String ruid,
                                          JsonObject scenario, Map<String, String> allScripts,
                                          JsonObject tracking) {
        JsonObject response = new JsonObject();
        response.addProperty("uuid", request.getUuid());
        response.addProperty("ruid", ruid);
        response.addProperty("theme", request.getTheme());
        response.addProperty("difficulty", request.getValidatedDifficulty());
        response.add("keywords", createKeywordsArray(request.getKeywords()));
        response.add("scenario", scenario);
        response.add("scripts", buildScriptsObject(allScripts));
        response.add("model_tracking", tracking);
        response.addProperty("success", true);
        response.addProperty("timestamp", String.valueOf(System.currentTimeMillis()));

        // 최종 결과 통계 로깅
        int totalObjects = scenario.getAsJsonArray("object_instructions").size();
        int generatedScripts = allScripts.size();
        log.info("=== 방 생성 완료 통계 ===");
        log.info("RUID: {}", ruid);
        log.info("총 오브젝트 수: {}", totalObjects);
        log.info("생성된 스크립트 수: {}", generatedScripts);
        if (totalObjects > 0) {
            log.info("스크립트 생성률: {}%", (generatedScripts * 100) / totalObjects);
        }
        log.info("생성된 스크립트 목록: {}", String.join(", ", allScripts.keySet()));
        log.info("========================");

        return response;
    }

    @NotNull
    private JsonObject buildScriptsObject(@NotNull Map<String, String> allScripts) {
        JsonObject scripts = new JsonObject();

        allScripts.forEach((scriptName, base64Content) -> {
            if (isValidScriptEntry(scriptName, base64Content)) {
                String fileName = ensureFileExtension(scriptName.trim());
                scripts.addProperty(fileName, base64Content.trim());
            } else {
                log.warn("유효하지 않은 스크립트 엔트리: name={}, contentEmpty={}",
                        scriptName, base64Content == null || base64Content.isEmpty());
            }
        });

        log.debug("스크립트 객체 생성 완료: {} 개의 스크립트", scripts.size());
        return scripts;
    }

    private boolean isValidScriptEntry(String name, String content) {
        return name != null && !name.trim().isEmpty() &&
                content != null && !content.trim().isEmpty();
    }

    @Contract(pure = true)
    private String ensureFileExtension(@NotNull String fileName) {
        return fileName.endsWith(".cs") ? fileName : fileName + ".cs";
    }

    @NotNull
    private JsonArray createKeywordsArray(@NotNull String[] keywords) {
        JsonArray array = new JsonArray();
        Set<String> uniqueKeywords = new LinkedHashSet<>();

        for (String keyword : keywords) {
            if (keyword != null && !keyword.trim().isEmpty()) {
                uniqueKeywords.add(keyword.trim().toLowerCase());
            }
        }

        uniqueKeywords.forEach(array::add);
        return array;
    }

    @NotNull
    private JsonObject createErrorResponse(@NotNull RoomCreationRequest request, String ruid, String errorMessage) {
        JsonObject errorResponse = new JsonObject();
        errorResponse.addProperty("uuid", request.getUuid());
        errorResponse.addProperty("ruid", ruid);
        errorResponse.addProperty("success", false);
        errorResponse.addProperty("error", errorMessage != null ? errorMessage : "알 수 없는 오류");
        errorResponse.addProperty("timestamp", String.valueOf(System.currentTimeMillis()));
        return errorResponse;
    }

    @Override
    public void close() {
        log.info("RoomService 종료 시작");
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("ExecutorService가 정상적으로 종료되지 않아 강제 종료합니다");
                executorService.shutdownNow();
                if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                    log.error("ExecutorService 강제 종료 실패");
                }
            }
        } catch (InterruptedException e) {
            log.error("ExecutorService 종료 중 인터럽트 발생");
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("RoomService 종료 완료");
    }
}