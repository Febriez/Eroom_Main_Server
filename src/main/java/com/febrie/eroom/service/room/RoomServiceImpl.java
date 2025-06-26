package com.febrie.eroom.service.room;

import com.febrie.eroom.config.ConfigurationManager;
import com.febrie.eroom.model.ModelGenerationResult;
import com.febrie.eroom.model.RoomCreationRequest;
import com.febrie.eroom.model.RoomCreationResponse;
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

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

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

    private final AiService aiService;
    private final MeshService meshService;
    private final MeshService localModelService;
    private final ConfigurationManager configManager;
    private final ExecutorService executorService;
    private final RequestValidator requestValidator;
    private final ScenarioValidator scenarioValidator;

    /**
     * RoomServiceImpl 생성자
     * 방 생성 서비스를 초기화합니다.
     */
    public RoomServiceImpl(AiService aiService, MeshService meshService, MeshService localModelService, ConfigurationManager configManager) {
        this.aiService = aiService;
        this.meshService = meshService;
        this.localModelService = localModelService;
        this.configManager = configManager;
        this.executorService = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        this.requestValidator = new RoomRequestValidator();
        this.scenarioValidator = new DefaultScenarioValidator();
    }

    /**
     * 방을 생성합니다.
     * 시나리오 생성, 모델 생성, 스크립트 생성을 순차적으로 수행합니다.
     */
    @Override
    public RoomCreationResponse createRoom(@NotNull RoomCreationRequest request, String ruid) {
        logRoomCreationStart(ruid, request);

        try {
            requestValidator.validate(request);
        } catch (IllegalArgumentException e) {
            return createErrorResponse(request, ruid, e.getMessage());
        }

        try {
            return processRoomCreation(request, ruid);
        } catch (RuntimeException e) {
            log.error("통합 방 생성 중 비즈니스 오류 발생: ruid={}", ruid, e);
            return createErrorResponse(request, ruid, e.getMessage());
        } catch (Exception e) {
            log.error("통합 방 생성 중 시스템 오류 발생: ruid={}", ruid, e);
            return createErrorResponse(request, ruid, "시스템 오류가 발생했습니다");
        }
    }

    /**
     * 방 생성 시작을 로깅합니다.
     */
    private void logRoomCreationStart(String ruid, @NotNull RoomCreationRequest request) {
        log.info("방 생성 시작 - ruid: {}, uuid: {}, theme: {}, difficulty: {}, freeModeling: {}",
                ruid, request.getUuid(), request.getTheme(),
                request.getValidatedDifficulty(), request.isFreeModeling());
    }

    /**
     * 방 생성 프로세스를 처리합니다.
     */
    @NotNull
    private RoomCreationResponse processRoomCreation(RoomCreationRequest request, String ruid) {
        JsonObject scenario = createIntegratedScenario(request, ruid);
        List<CompletableFuture<ModelGenerationResult>> modelFutures = startModelGeneration(scenario, request.isFreeModeling());
        Map<String, String> allScripts = createUnifiedScripts(scenario);
        JsonObject modelTracking = waitForModels(modelFutures);

        RoomCreationResponse response = buildSuccessResponse(request, ruid, scenario, allScripts, modelTracking);
        log.info("방 생성 완료 - ruid: {}, scripts: {}", ruid, response.getObjectScripts().size());
        return response;
    }

    /**
     * 통합 시나리오를 생성합니다.
     */
    @NotNull
    private JsonObject createIntegratedScenario(RoomCreationRequest request, String ruid) {
        try {
            validateExitDoorExists(request);
            JsonObject scenario = generateScenario(request, ruid);
            validateScenario(scenario);
            logScenarioCreation(ruid, scenario);
            return scenario;
        } catch (Exception e) {
            throw new RuntimeException("통합 시나리오 생성 단계에서 오류 발생: " + e.getMessage(), e);
        }
    }

    /**
     * ExitDoor 존재를 검증합니다.
     */
    private void validateExitDoorExists(@NotNull RoomCreationRequest request) {
        boolean hasExitDoor = request.getExistingObjectsSafe().stream()
                .anyMatch(obj -> "ExitDoor".equalsIgnoreCase(obj.getName()));

        if (!hasExitDoor) {
            throw new RuntimeException("ExitDoor가 existing_objects에 포함되어야 합니다.");
        }
    }

    /**
     * LLM을 통해 시나리오를 생성합니다.
     */
    @NotNull
    private JsonObject generateScenario(RoomCreationRequest request, String ruid) {
        String prompt = configManager.getPrompt("scenario");
        JsonObject scenarioRequest = buildScenarioRequest(request, ruid);

        log.debug("시나리오 생성 요청 - ruid: {}, theme: {}, difficulty: {}",
                ruid, request.getTheme().trim(), request.getValidatedDifficulty());

        JsonObject scenario = aiService.generateScenario(prompt, scenarioRequest);
        if (scenario == null) {
            throw new RuntimeException("통합 시나리오 생성 실패: LLM 응답이 null입니다.");
        }

        return scenario;
    }

    /**
     * 시나리오를 검증합니다.
     */
    private void validateScenario(JsonObject scenario) {
        scenarioValidator.validate(scenario);
    }

    /**
     * 시나리오 생성 완료를 로깅합니다.
     */
    private void logScenarioCreation(String ruid, @NotNull JsonObject scenario) {
        log.info("시나리오 생성 완료 - ruid: {}, objects: {}",
                ruid, scenario.getAsJsonArray("object_instructions").size());
    }

    /**
     * 시나리오 요청 객체를 빌드합니다.
     */
    @NotNull
    private JsonObject buildScenarioRequest(@NotNull RoomCreationRequest request, String ruid) {
        JsonObject scenarioRequest = new JsonObject();

        addBasicInfo(scenarioRequest, request, ruid);
        addKeywordsAndExistingObjects(scenarioRequest, request);
        addMetadata(scenarioRequest, request);

        logScenarioRequestCreation(request);
        return scenarioRequest;
    }

    /**
     * 시나리오 요청에 기본 정보를 추가합니다.
     */
    private void addBasicInfo(@NotNull JsonObject scenarioRequest, @NotNull RoomCreationRequest request, String ruid) {
        scenarioRequest.addProperty("uuid", request.getUuid());
        scenarioRequest.addProperty("ruid", ruid);
        scenarioRequest.addProperty("theme", request.getTheme().trim());
        scenarioRequest.addProperty("difficulty", request.getValidatedDifficulty());
        scenarioRequest.addProperty("is_free_modeling", request.isFreeModeling());
    }

    /**
     * 시나리오 요청에 키워드와 기존 객체를 추가합니다.
     */
    private void addKeywordsAndExistingObjects(@NotNull JsonObject scenarioRequest, @NotNull RoomCreationRequest request) {
        scenarioRequest.add("keywords", createKeywordsArray(request.getKeywords()));
        JsonArray existingObjectsArray = convertExistingObjectsToJsonArray(request.getExistingObjectsSafe());
        scenarioRequest.add("existing_objects", existingObjectsArray);
    }

    /**
     * 시나리오 요청에 메타데이터를 추가합니다.
     */
    private void addMetadata(@NotNull JsonObject scenarioRequest, @NotNull RoomCreationRequest request) {
        scenarioRequest.addProperty("existing_objects_count", request.getExistingObjectsSafe().size());
    }

    /**
     * 시나리오 요청 생성을 로깅합니다.
     */
    private void logScenarioRequestCreation(@NotNull RoomCreationRequest request) {
        log.debug("시나리오 요청 생성 - keywords: {}, existingObjects: {}, freeModeling: {}",
                request.getKeywords().length, request.getExistingObjectsSafe().size(), request.isFreeModeling());
    }

    /**
     * 기존 객체 리스트를 JSON 배열로 변환합니다.
     */
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

    /**
     * 3D 모델 생성을 시작합니다.
     */
    @NotNull
    private List<CompletableFuture<ModelGenerationResult>> startModelGeneration(@NotNull JsonObject scenario, boolean isFreeModeling) {
        JsonArray objectInstructions = scenario.getAsJsonArray("object_instructions");

        if (isObjectInstructionsEmpty(objectInstructions)) {
            return new ArrayList<>();
        }

        log.info("3D 모델 생성 시작 - objects: {}, freeModeling: {}",
                objectInstructions.size(), isFreeModeling);

        List<CompletableFuture<ModelGenerationResult>> futures = createModelGenerationFutures(objectInstructions, isFreeModeling);

        log.debug("모델 생성 태스크 {} 개 추가", futures.size());
        return futures;
    }

    /**
     * 객체 지시사항이 비어있는지 확인합니다.
     */
    private boolean isObjectInstructionsEmpty(JsonArray objectInstructions) {
        if (objectInstructions == null || objectInstructions.isEmpty()) {
            log.warn("오브젝트 설명(object_instructions)이 없어 3D 모델 생성을 건너뜁니다");
            return true;
        }
        return false;
    }

    /**
     * 모델 생성 Future 리스트를 생성합니다.
     */
    @NotNull
    private List<CompletableFuture<ModelGenerationResult>> createModelGenerationFutures(@NotNull JsonArray objectInstructions, boolean isFreeModeling) {
        List<CompletableFuture<ModelGenerationResult>> futures = new ArrayList<>();

        for (int i = 0; i < objectInstructions.size(); i++) {
            JsonObject instruction = objectInstructions.get(i).getAsJsonObject();
            processObjectInstruction(instruction, i, isFreeModeling, futures);
        }

        return futures;
    }

    /**
     * 개별 객체 지시사항을 처리합니다.
     */
    private void processObjectInstruction(JsonObject instruction, int index, boolean isFreeModeling, List<CompletableFuture<ModelGenerationResult>> futures) {
        if (shouldSkipModelGeneration(instruction, isFreeModeling)) {
            return;
        }

        String objectName = instruction.get("name").getAsString();
        String visualDescription = extractVisualDescription(instruction, isFreeModeling);

        if (isValidForModelGeneration(objectName, visualDescription)) {
            futures.add(createModelTask(visualDescription, objectName, index, isFreeModeling));
        }
    }

    /**
     * 시각적 설명을 추출합니다.
     */
    private String extractVisualDescription(JsonObject instruction, boolean isFreeModeling) {
        if (isFreeModeling) {
            return instruction.has("simple_visual_description") ?
                    instruction.get("simple_visual_description").getAsString() : "";
        } else {
            return instruction.has("visual_description") ?
                    instruction.get("visual_description").getAsString() : "";
        }
    }

    /**
     * 모델 생성을 건너뛰어야 하는지 확인합니다.
     */
    private boolean shouldSkipModelGeneration(@NotNull JsonObject instruction, boolean isFreeModeling) {
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

        String descriptionField = isFreeModeling ? "simple_visual_description" : "visual_description";
        if (!instruction.has(descriptionField)) {
            log.debug("{}이 없는 오브젝트 '{}'는 모델 생성에서 건너뜁니다.",
                    descriptionField,
                    instruction.has("name") ? instruction.get("name").getAsString() : "unknown");
            return true;
        }

        return false;
    }

    /**
     * 모델 생성이 유효한지 확인합니다.
     */
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

    /**
     * 모델 생성 태스크를 생성합니다.
     */
    @NotNull
    @Contract("_, _, _, _ -> new")
    private CompletableFuture<ModelGenerationResult> createModelTask(String prompt, String name, int index, boolean isFreeModeling) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return generateModel(prompt, name, index, isFreeModeling);
            } catch (Exception e) {
                return handleModelGenerationError(name, e);
            }
        }, executorService);
    }

    /**
     * 모델을 생성합니다.
     */
    @NotNull
    @Contract("_, _, _, _ -> new")
    private ModelGenerationResult generateModel(@NotNull String prompt, String name, int index, boolean isFreeModeling) {
        log.debug("3D 모델 생성 요청 - index: {}, name: {}, promptLength: {}, free: {}",
                index, name, prompt.length(), isFreeModeling);

        MeshService modelService = isFreeModeling ? localModelService : meshService;
        String trackingId = modelService.generateModel(prompt, name, index);

        String resultId = (trackingId != null && !trackingId.trim().isEmpty()) ?
                trackingId : "pending-" + UUID.randomUUID().toString().substring(0, 8);

        return new ModelGenerationResult(name, resultId);
    }

    /**
     * 모델 생성 오류를 처리합니다.
     */
    @NotNull
    @Contract("_, _ -> new")
    private ModelGenerationResult handleModelGenerationError(String name, @NotNull Exception e) {
        log.error("모델 생성 실패: {} - {}", name, e.getMessage());
        return new ModelGenerationResult(name, "error-" + UUID.randomUUID().toString().substring(0, 8));
    }

    /**
     * 통합 스크립트를 생성합니다.
     */
    @NotNull
    private Map<String, String> createUnifiedScripts(JsonObject scenario) {
        try {
            JsonArray objectInstructions = scenario.getAsJsonArray("object_instructions");
            int totalObjects = objectInstructions != null ? objectInstructions.size() : 0;

            logScriptCreationStart(totalObjects);

            if (totalObjects < PARALLEL_THRESHOLD) {
                log.debug("단일 요청 모드 사용 - objects: {}", totalObjects);
                return createUnifiedScriptsSingleRequest(scenario);
            } else {
                log.debug("병렬 처리 모드 사용 - objects: {}", totalObjects);
                return createUnifiedScriptsParallel(scenario);
            }

        } catch (Exception e) {
            throw new RuntimeException("스크립트 생성 실패: " + e.getMessage(), e);
        }
    }

    /**
     * 스크립트 생성 시작을 로깅합니다.
     */
    private void logScriptCreationStart(int totalObjects) {
        String mode = totalObjects < PARALLEL_THRESHOLD ? "단일 요청" : "병렬 처리";
        log.info("스크립트 생성 시작 - objects: {}, mode: {}", totalObjects, mode);
    }

    /**
     * 단일 요청으로 스크립트를 생성합니다.
     */
    private Map<String, String> createUnifiedScriptsSingleRequest(JsonObject scenario) {
        String prompt = configManager.getPrompt("unified_scripts");
        JsonObject scriptRequest = buildScriptRequest(scenario);
        return aiService.generateUnifiedScripts(prompt, scriptRequest);
    }

    /**
     * 병렬로 스크립트를 생성합니다.
     */
    @NotNull
    private Map<String, String> createUnifiedScriptsParallel(@NotNull JsonObject scenario) {
        Map<String, String> allScripts = new ConcurrentHashMap<>();

        List<JsonObject> gameManagerList = new ArrayList<>();
        List<JsonObject> otherObjects = new ArrayList<>();

        separateGameManagerAndObjects(scenario.getAsJsonArray("object_instructions"), gameManagerList, otherObjects);

        log.debug("병렬 처리 시작 - gameManager: {}, others: {}",
                gameManagerList.size(), otherObjects.size());

        String gameManagerScript = processFirstBatch(scenario, gameManagerList, otherObjects, allScripts);
        String decodedGameManagerScript = new String(Base64.getDecoder().decode(gameManagerScript), StandardCharsets.UTF_8);
        processRemainingBatches(scenario, otherObjects, decodedGameManagerScript, allScripts);

        log.info("병렬 스크립트 생성 완료 - total: {}", allScripts.size());
        return allScripts;
    }

    /**
     * GameManager와 다른 객체를 분리합니다.
     */
    private void separateGameManagerAndObjects(@NotNull JsonArray objectInstructions, List<JsonObject> gameManagerList, List<JsonObject> otherObjects) {
        for (int i = 0; i < objectInstructions.size(); i++) {
            JsonObject obj = objectInstructions.get(i).getAsJsonObject();
            if (TYPE_GAME_MANAGER.equals(obj.get("type").getAsString())) {
                gameManagerList.add(obj);
            } else {
                otherObjects.add(obj);
            }
        }
    }

    /**
     * 첫 번째 배치를 처리합니다.
     */
    @NotNull
    private String processFirstBatch(JsonObject scenario, List<JsonObject> gameManagerList, List<JsonObject> otherObjects, @NotNull Map<String, String> allScripts) {
        List<JsonObject> firstBatch = buildFirstBatch(gameManagerList, otherObjects);
        logFirstBatch(gameManagerList, firstBatch);

        Map<String, String> firstBatchResult = generateFirstBatchScripts(scenario, firstBatch);
        allScripts.putAll(firstBatchResult);

        return extractAndValidateGameManagerScript(firstBatchResult);
    }

    /**
     * 첫 번째 배치를 구성합니다.
     */
    @NotNull
    private List<JsonObject> buildFirstBatch(List<JsonObject> gameManagerList, @NotNull List<JsonObject> otherObjects) {
        int firstBatchSize = Math.min(FIRST_BATCH_SIZE, otherObjects.size());
        List<JsonObject> firstBatch = new ArrayList<>(gameManagerList);
        firstBatch.addAll(otherObjects.subList(0, firstBatchSize));
        return firstBatch;
    }

    /**
     * 첫 번째 배치를 로깅합니다.
     */
    private void logFirstBatch(@NotNull List<JsonObject> gameManagerList, @NotNull List<JsonObject> firstBatch) {
        log.debug("첫 번째 배치 - gameManager: {}, objects: {}, total: {}",
                gameManagerList.size(), firstBatch.size() - gameManagerList.size(), firstBatch.size());
    }

    /**
     * 첫 번째 배치 스크립트를 생성합니다.
     */
    private Map<String, String> generateFirstBatchScripts(JsonObject scenario, List<JsonObject> firstBatch) {
        JsonObject firstBatchRequest = buildBatchRequest(scenario, firstBatch, true, null);

        return aiService.generateUnifiedScripts(configManager.getPrompt("unified_scripts"), firstBatchRequest);
    }

    /**
     * GameManager 스크립트를 추출하고 검증합니다.
     */
    @NotNull
    private String extractAndValidateGameManagerScript(@NotNull Map<String, String> firstBatchResult) {
        String gameManagerScript = firstBatchResult.get("GameManager");
        if (gameManagerScript == null || gameManagerScript.isEmpty()) {
            throw new RuntimeException("GameManager 스크립트 생성 실패");
        }

        log.debug("GameManager 생성 완료, 첫 배치 스크립트 수: {}", firstBatchResult.size());
        return gameManagerScript;
    }

    /**
     * 나머지 배치들을 처리합니다.
     */
    private void processRemainingBatches(JsonObject scenario, List<JsonObject> allObjects, String gameManagerScript, Map<String, String> allScripts) {
        List<JsonObject> remainingObjects = getRemainingObjects(allObjects);

        if (remainingObjects.isEmpty()) {
            return;
        }

        List<CompletableFuture<Map<String, String>>> futures = createBatchFutures(scenario, remainingObjects, gameManagerScript);

        waitForAllBatches(futures, allScripts);
    }

    /**
     * 나머지 객체들을 가져옵니다.
     */
    @NotNull
    private List<JsonObject> getRemainingObjects(@NotNull List<JsonObject> allObjects) {
        return allObjects.subList(Math.min(FIRST_BATCH_SIZE, allObjects.size()), allObjects.size());
    }

    /**
     * 배치 Future들을 생성합니다.
     */
    @NotNull
    private List<CompletableFuture<Map<String, String>>> createBatchFutures(JsonObject scenario, @NotNull List<JsonObject> remainingObjects, String gameManagerScript) {
        List<CompletableFuture<Map<String, String>>> futures = new ArrayList<>();

        for (int i = 0; i < remainingObjects.size(); i += BATCH_SIZE) {
            int batchEnd = Math.min(i + BATCH_SIZE, remainingObjects.size());
            List<JsonObject> batch = remainingObjects.subList(i, batchEnd);

            int absoluteBatchStart = FIRST_BATCH_SIZE + i;
            logBatchCreation(absoluteBatchStart, batchEnd, batch.size());

            CompletableFuture<Map<String, String>> future = CompletableFuture.supplyAsync(
                    () -> generateBatchScripts(batch, scenario, absoluteBatchStart, gameManagerScript),
                    executorService);

            futures.add(future);
        }

        return futures;
    }

    /**
     * 배치 생성을 로깅합니다.
     */
    private void logBatchCreation(int absoluteBatchStart, int batchEnd, int batchSize) {
        log.debug("배치 생성 - objects: {}-{} ({}개)",
                absoluteBatchStart + 1, FIRST_BATCH_SIZE + batchEnd, batchSize);
    }

    /**
     * 모든 배치가 완료될 때까지 대기합니다.
     */
    private void waitForAllBatches(List<CompletableFuture<Map<String, String>>> futures, Map<String, String> allScripts) {
        try {
            CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                    futures.toArray(new CompletableFuture[0]));

            allFutures.get(5, TimeUnit.MINUTES);
            collectBatchResults(futures, allScripts);

        } catch (TimeoutException e) {
            log.error("스크립트 생성 타임아웃");
            throw new RuntimeException("스크립트 생성 시간 초과");
        } catch (Exception e) {
            log.error("병렬 처리 중 오류", e);
            throw new RuntimeException("병렬 스크립트 생성 실패: " + e.getMessage());
        }
    }

    /**
     * 배치 결과들을 수집합니다.
     */
    private void collectBatchResults(@NotNull List<CompletableFuture<Map<String, String>>> futures, Map<String, String> allScripts) throws Exception {
        for (CompletableFuture<Map<String, String>> future : futures) {
            allScripts.putAll(future.get());
        }
    }

    /**
     * 배치 요청을 빌드합니다.
     */
    @NotNull
    private JsonObject buildBatchRequest(@NotNull JsonObject scenario, @NotNull List<JsonObject> batch, boolean isFirstBatch, String gameManagerScript) {
        JsonObject request = new JsonObject();
        request.add("scenario_data", scenario.getAsJsonObject("scenario_data"));

        JsonArray batchArray = new JsonArray();
        batch.forEach(batchArray::add);
        request.add("object_instructions", batchArray);

        logBatchObjectNames(batch);
        addBatchMetadata(request, scenario, isFirstBatch, gameManagerScript);
        addModelScalesToRequest(scenario, batch, request);

        return request;
    }

    /**
     * 배치의 객체 이름들을 로깅합니다.
     */
    private void logBatchObjectNames(@NotNull List<JsonObject> batch) {
        List<String> objectNames = new ArrayList<>();
        for (JsonObject obj : batch) {
            objectNames.add(obj.get("name").getAsString());
        }
        log.debug("배치 오브젝트 목록: {}", String.join(", ", objectNames));
    }

    /**
     * 배치 메타데이터를 추가합니다.
     */
    private void addBatchMetadata(JsonObject request, JsonObject scenario, boolean isFirstBatch, String gameManagerScript) {
        if (isFirstBatch) {
            request.addProperty("is_first_batch", true);
            request.addProperty("total_objects", scenario.getAsJsonArray("object_instructions").size());
        } else {
            request.addProperty("game_manager_script", gameManagerScript);
            request.addProperty("total_objects", scenario.getAsJsonArray("object_instructions").size());
        }
    }

    /**
     * 모델 스케일을 요청에 추가합니다.
     */
    private void addModelScalesToRequest(@NotNull JsonObject scenario, List<JsonObject> batch, JsonObject request) {
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

    /**
     * 배치 스크립트를 생성합니다.
     */
    @NotNull
    private Map<String, String> generateBatchScripts(List<JsonObject> batch, JsonObject scenario, int batchStartIndex, String gameManagerScript) {
        try {
            String prompt = configManager.getPrompt("scripts_batch");
            JsonObject request = buildBatchRequest(scenario, batch, false, gameManagerScript);

            request.addProperty("batch_index", batchStartIndex);

            log.debug("배치 {} API 호출 중 - objects: {}",
                    batchStartIndex / BATCH_SIZE + 1, batch.size());

            Map<String, String> result = aiService.generateUnifiedScripts(prompt, request);

            logBatchCompletion(batchStartIndex, result.size(), batch.size());
            validateBatchResult(batchStartIndex, result, batch);

            return result;

        } catch (Exception e) {
            log.error("배치 {} 생성 실패", batchStartIndex / BATCH_SIZE + 1, e);
            return new HashMap<>();
        }
    }

    /**
     * 배치 완료를 로깅합니다.
     */
    private void logBatchCompletion(int batchStartIndex, int resultSize, int batchSize) {
        log.debug("배치 {} 완료 - generated: {}, expected: {}",
                batchStartIndex / BATCH_SIZE + 1, resultSize, batchSize);
    }

    /**
     * 배치 결과를 검증합니다.
     */
    private void validateBatchResult(int batchStartIndex, @NotNull Map<String, String> result, @NotNull List<JsonObject> batch) {
        if (result.size() < batch.size()) {
            log.warn("배치 {}: 생성된 스크립트 수({})가 오브젝트 수({})보다 적습니다. 누락된 오브젝트를 확인하세요.",
                    batchStartIndex / BATCH_SIZE + 1, result.size(), batch.size());

            logMissingScripts(result.keySet(), batch);
        }
    }

    /**
     * 누락된 스크립트를 로깅합니다.
     */
    private void logMissingScripts(Set<String> generatedNames, @NotNull List<JsonObject> batch) {
        for (JsonObject obj : batch) {
            String objName = obj.get("name").getAsString();
            if (!generatedNames.contains(objName) && !generatedNames.contains(objName + "C")) {
                log.warn("누락된 오브젝트 스크립트: {}", objName);
            }
        }
    }

    /**
     * 스크립트 요청을 빌드합니다.
     */
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

    /**
     * 모델 생성이 완료될 때까지 대기합니다.
     */
    @NotNull
    private JsonObject waitForModels(@NotNull List<CompletableFuture<ModelGenerationResult>> futures) {
        if (futures.isEmpty()) {
            return createEmptyTracking();
        }

        JsonObject tracking = new JsonObject();
        JsonObject failedModels = new JsonObject();

        log.info("3D 모델 생성 대기 중 - count: {}, timeout: {}분", futures.size(), MODEL_TIMEOUT_MINUTES);

        try {
            waitForAllModels(futures);
            collectResults(futures, tracking, failedModels);
        } catch (TimeoutException e) {
            handleTimeout(futures, tracking, failedModels);
        } catch (Exception e) {
            log.error("모델 생성 대기 중 오류 발생", e);
        }

        return finalizeTracking(tracking, failedModels);
    }

    /**
     * 모든 모델이 완료될 때까지 대기합니다.
     */
    private void waitForAllModels(@NotNull List<CompletableFuture<ModelGenerationResult>> futures) throws Exception {
        CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                futures.toArray(new CompletableFuture[0]));
        allFutures.get(MODEL_TIMEOUT_MINUTES, TimeUnit.MINUTES);
    }

    /**
     * 모델 생성 결과를 수집합니다.
     */
    private void collectResults(@NotNull List<CompletableFuture<ModelGenerationResult>> futures, JsonObject tracking, JsonObject failedModels) {
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

    /**
     * 타임아웃을 처리합니다.
     */
    private void handleTimeout(@NotNull List<CompletableFuture<ModelGenerationResult>> futures, JsonObject tracking, JsonObject failedModels) {
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

    /**
     * 추적 결과를 추가합니다.
     */
    private void addTrackingResult(JsonObject tracking, JsonObject failedModels, ModelGenerationResult result) {
        if (!isValidResult(result)) {
            log.warn("유효하지 않은 모델 결과: {}", result);
            return;
        }

        String objectName = result.getObjectName().trim();
        String trackingId = result.getTrackingId();

        if (isValidTrackingId(trackingId)) {
            if (isErrorTrackingId(trackingId)) {
                addFailedModel(failedModels, objectName, trackingId);
            } else {
                addSuccessfulModel(tracking, objectName, trackingId);
            }
        } else {
            addNoTrackingModel(failedModels, objectName);
        }
    }

    /**
     * 결과가 유효한지 확인합니다.
     */
    private boolean isValidResult(ModelGenerationResult result) {
        return result != null && result.getObjectName() != null && !result.getObjectName().trim().isEmpty();
    }

    /**
     * 추적 ID가 유효한지 확인합니다.
     */
    private boolean isValidTrackingId(String trackingId) {
        return trackingId != null && !trackingId.trim().isEmpty();
    }

    /**
     * 오류 추적 ID인지 확인합니다.
     */
    private boolean isErrorTrackingId(@NotNull String trackingId) {
        return trackingId.startsWith("error-") || trackingId.startsWith("timeout-");
    }

    /**
     * 실패한 모델을 추가합니다.
     */
    private void addFailedModel(@NotNull JsonObject failedModels, String objectName, String trackingId) {
        failedModels.addProperty(objectName, trackingId);
        log.warn("모델 생성 실패로 표시됨: {} -> {}", objectName, trackingId);
    }

    /**
     * 성공한 모델을 추가합니다.
     */
    private void addSuccessfulModel(@NotNull JsonObject tracking, String objectName, @NotNull String trackingId) {
        tracking.addProperty(objectName, trackingId.trim());
        log.debug("모델 추적 ID 추가: {} -> {}", objectName, trackingId);
    }

    /**
     * 추적 ID가 없는 모델을 추가합니다.
     */
    private void addNoTrackingModel(@NotNull JsonObject failedModels, String objectName) {
        String fallbackId = "no-tracking-" + System.currentTimeMillis();
        failedModels.addProperty(objectName, fallbackId);
        log.warn("trackingId가 없어 실패로 표시: {} -> {}", objectName, fallbackId);
    }

    /**
     * 추적 정보를 최종화합니다.
     */
    private JsonObject finalizeTracking(JsonObject tracking, @NotNull JsonObject failedModels) {
        if (!failedModels.asMap().isEmpty()) {
            tracking.add("failed_models", failedModels);
        }

        return tracking.asMap().isEmpty() ? createEmptyTracking() : tracking;
    }

    /**
     * 빈 추적 정보를 생성합니다.
     */
    @NotNull
    private JsonObject createEmptyTracking() {
        JsonObject empty = new JsonObject();
        empty.addProperty("status", "no_models_generated");
        empty.addProperty("timestamp", String.valueOf(System.currentTimeMillis()));
        return empty;
    }

    /**
     * 성공 응답을 빌드합니다.
     */
    @NotNull
    private RoomCreationResponse buildSuccessResponse(@NotNull RoomCreationRequest request, String ruid,
                                                      JsonObject scenario, Map<String, String> allScripts,
                                                      JsonObject modelTracking) {

        String gameManagerScript = extractGameManagerScript(allScripts);
        List<String> objectScripts = extractObjectScripts(allScripts);

        RoomCreationResponse response = new RoomCreationResponse();
        response.setUuid(request.getUuid());
        response.setPuid(ruid);
        response.setTheme(request.getTheme());
        response.setKeywords(convertKeywordsToArray(request.getKeywords()));
        response.setDifficulty(request.getValidatedDifficulty());
        response.setScenario(scenario);
        response.setGameManagerScript(gameManagerScript);
        response.setObjectScripts(objectScripts);
        response.setModelTracking(modelTracking);
        response.setSuccess(true);

        logFinalStatistics(ruid, scenario, allScripts);

        return response;
    }

    /**
     * GameManager 스크립트를 추출합니다.
     */
    private String extractGameManagerScript(@NotNull Map<String, String> allScripts) {
        return allScripts.getOrDefault("GameManager", "");
    }

    /**
     * 객체 스크립트들을 추출합니다.
     */
    @NotNull
    private List<String> extractObjectScripts(@NotNull Map<String, String> allScripts) {
        return allScripts.entrySet().stream()
                .filter(entry -> !"GameManager".equals(entry.getKey()))
                .map(entry -> ensureFileExtension(entry.getKey()) + ":" + entry.getValue())
                .collect(Collectors.toList());
    }

    /**
     * 키워드를 배열로 변환합니다.
     */
    @NotNull
    private String @NotNull [] convertKeywordsToArray(String @NotNull [] keywords) {
        Set<String> uniqueKeywords = new LinkedHashSet<>();
        for (String keyword : keywords) {
            if (!keyword.trim().isEmpty()) {
                uniqueKeywords.add(keyword.trim().toLowerCase());
            }
        }
        return uniqueKeywords.toArray(new String[0]);
    }

    /**
     * 최종 통계를 로깅합니다.
     */
    private void logFinalStatistics(String ruid, @NotNull JsonObject scenario, @NotNull Map<String, String> allScripts) {
        int totalObjects = scenario.getAsJsonArray("object_instructions").size();
        int generatedScripts = allScripts.size();
        int successRate = totalObjects > 0 ? (generatedScripts * 100) / totalObjects : 0;

        log.info("방 생성 완료 - ruid: {}, totalObjects: {}, generatedScripts: {}, successRate: {}%",
                ruid, totalObjects, generatedScripts, successRate);
        log.debug("생성된 스크립트: {}", String.join(", ", allScripts.keySet()));
    }

    /**
     * 파일 확장자를 확인하고 추가합니다.
     */
    @Contract(pure = true)
    private String ensureFileExtension(@NotNull String fileName) {
        return fileName.endsWith(".cs") ? fileName : fileName + ".cs";
    }

    /**
     * 키워드 배열을 생성합니다.
     */
    @NotNull
    private JsonArray createKeywordsArray(@NotNull String @NotNull [] keywords) {
        JsonArray array = new JsonArray();
        Set<String> uniqueKeywords = new LinkedHashSet<>();

        for (String keyword : keywords) {
            if (!keyword.trim().isEmpty()) {
                uniqueKeywords.add(keyword.trim().toLowerCase());
            }
        }

        uniqueKeywords.forEach(array::add);
        return array;
    }

    /**
     * 오류 응답을 생성합니다.
     */
    @NotNull
    private RoomCreationResponse createErrorResponse(@NotNull RoomCreationRequest request, String ruid, String errorMessage) {
        RoomCreationResponse response = new RoomCreationResponse();
        response.setUuid(request.getUuid());
        response.setPuid(ruid);
        response.setTheme(request.getTheme());
        response.setKeywords(convertKeywordsToArray(request.getKeywords()));
        response.setDifficulty(request.getValidatedDifficulty());
        response.setSuccess(false);
        response.setErrorMessage(errorMessage != null ? errorMessage : "알 수 없는 오류");

        return response;
    }

    /**
     * 리소스를 정리하고 서비스를 종료합니다.
     */
    @Override
    public void close() {
        log.debug("RoomService 종료 시작");
        shutdownExecutorService();
        log.debug("RoomService 종료 완료");
    }

    /**
     * ExecutorService를 종료합니다.
     */
    private void shutdownExecutorService() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
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
        log.warn("ExecutorService가 정상적으로 종료되지 않아 강제 종료합니다");
        executorService.shutdownNow();
        try {
            if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                log.error("ExecutorService 강제 종료 실패");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 종료 중 인터럽트를 처리합니다.
     */
    private void handleShutdownInterruption() {
        log.error("ExecutorService 종료 중 인터럽트 발생");
        executorService.shutdownNow();
        Thread.currentThread().interrupt();
    }
}