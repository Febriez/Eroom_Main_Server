package com.febrie.eroom.service.impl;

import com.febrie.eroom.model.ModelGenerationResult;
import com.febrie.eroom.model.RoomCreationRequest;
import com.febrie.eroom.service.AnthropicService;
import com.febrie.eroom.service.MeshyService;
import com.febrie.eroom.service.RoomService;
import com.febrie.eroom.util.ConfigUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class RoomServiceImpl implements RoomService, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RoomServiceImpl.class);
    private static final int MODEL_TIMEOUT_MINUTES = 10;
    private static final int EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS = 60;

    private final AnthropicService anthropicService;
    private final MeshyService meshyService;
    private final ConfigUtil configUtil;
    private final ExecutorService executorService;

    public RoomServiceImpl(AnthropicService anthropicService, MeshyService meshyService, ConfigUtil configUtil) {
        this.anthropicService = anthropicService;
        this.meshyService = meshyService;
        this.configUtil = configUtil;
        this.executorService = Executors.newFixedThreadPool(10);

        validateConfiguration();
    }

    @Override
    public JsonObject createRoom(@NotNull RoomCreationRequest request, String ruid) {
        log.info("통합 방 생성 시작: ruid={}, user_uuid={}, theme={}, difficulty={}",
                ruid, request.getUuid(), request.getTheme(), request.getValidatedDifficulty());

        try {
            validateRequest(request);
        } catch (IllegalArgumentException e) {
            return createErrorResponse(request, ruid, e.getMessage());
        }

        try {
            JsonObject config = configUtil.getConfig();

            JsonObject scenario = createIntegratedScenario(request, ruid, config);
            List<CompletableFuture<ModelGenerationResult>> modelFutures = startModelGeneration(scenario);
            Map<String, String> allScripts = createUnifiedScripts(scenario, request.getRoomPrefab(), config);
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

    private void validateRequest(@NotNull RoomCreationRequest request) {
        if (request.getUuid() == null || request.getUuid().trim().isEmpty()) {
            throw new IllegalArgumentException("UUID가 비어있습니다");
        }

        if (request.getTheme() == null || request.getTheme().trim().isEmpty()) {
            throw new IllegalArgumentException("테마가 비어있습니다");
        }

        if (request.getKeywords() == null || request.getKeywords().length == 0) {
            throw new IllegalArgumentException("키워드가 비어있습니다");
        }

        for (String keyword : request.getKeywords()) {
            if (keyword == null || keyword.trim().isEmpty()) {
                throw new IllegalArgumentException("빈 키워드가 포함되어 있습니다");
            }
        }

        if (request.getRoomPrefab() == null || request.getRoomPrefab().trim().isEmpty()) {
            throw new IllegalArgumentException("roomPrefab URL이 비어있습니다");
        }

        String url = request.getRoomPrefab().trim();
        if (!url.startsWith("https://")) {
            throw new IllegalArgumentException("유효하지 않은 roomPrefab URL 형식입니다");
        }

        if (request.getDifficulty() != null) {
            String difficulty = request.getDifficulty().trim().toLowerCase();
            if (!Arrays.asList("easy", "normal", "hard").contains(difficulty)) {
                throw new IllegalArgumentException("유효하지 않은 난이도입니다. easy, normal, hard 중 하나를 선택하세요.");
            }
        }
    }

    @NotNull
    private JsonObject createIntegratedScenario(@NotNull RoomCreationRequest request, String ruid, JsonObject config) {
        try {
            String prompt = getPrompt(config, "scenario");
            JsonObject scenarioRequest = new JsonObject();

            JsonArray initialKeywords = createKeywordsArray(request.getKeywords());

            scenarioRequest.addProperty("uuid", request.getUuid());
            scenarioRequest.addProperty("ruid", ruid);
            scenarioRequest.addProperty("theme", request.getTheme().trim());
            scenarioRequest.add("keywords", initialKeywords);
            scenarioRequest.addProperty("difficulty", request.getValidatedDifficulty());
            scenarioRequest.addProperty("room_prefab_url", request.getRoomPrefab().trim());

            log.info("LLM에 시나리오 생성 요청. ruid: '{}', Theme: '{}', Difficulty: '{}'",
                    ruid, request.getTheme().trim(), request.getValidatedDifficulty());

            JsonObject scenario = anthropicService.generateScenario(prompt, scenarioRequest);
            if (scenario == null) {
                throw new RuntimeException("통합 시나리오 생성 실패: LLM 응답이 null입니다.");
            }

            validateScenario(scenario);

            log.info("통합 시나리오 생성 완료. ruid: {}, 오브젝트 설명 {}개",
                    ruid, scenario.getAsJsonArray("object_instructions").size());

            return scenario;

        } catch (Exception e) {
            throw new RuntimeException("통합 시나리오 생성 단계에서 오류 발생: " + e.getMessage(), e);
        }
    }

    @NotNull
    private JsonObject buildFinalResponse(@NotNull RoomCreationRequest request, String ruid, @NotNull JsonObject scenario,
                                          Map<String, String> allScripts, JsonObject tracking) {

        JsonObject response = new JsonObject();
        response.addProperty("uuid", request.getUuid());
        response.addProperty("ruid", ruid);
        response.addProperty("theme", request.getTheme());
        response.addProperty("difficulty", request.getValidatedDifficulty());
        response.add("keywords", createKeywordsArray(request.getKeywords()));
        response.addProperty("room_prefab", request.getRoomPrefab());
        response.add("scenario", scenario);
        response.add("scripts", buildScriptsObject(allScripts));
        response.add("model_tracking", tracking);
        response.addProperty("success", true);
        response.addProperty("timestamp", String.valueOf(System.currentTimeMillis()));

        return response;
    }

    private void validateConfiguration() {
        try {
            JsonObject config = configUtil.getConfig();
            if (!config.has("prompts")) {
                log.error("프롬프트 설정이 없습니다. 서버를 종료합니다.");
                System.exit(1);
            }

            JsonObject prompts = config.getAsJsonObject("prompts");
            if (!prompts.has("scenario") || !prompts.has("unified_scripts")) {
                log.error("필수 프롬프트 설정(scenario, unified_scripts)이 없습니다. 서버를 종료합니다.");
                System.exit(1);
            }

            if (!config.has("model")) {
                log.error("모델 설정이 없습니다. 서버를 종료합니다.");
                System.exit(1);
            }

            JsonObject model = config.getAsJsonObject("model");
            if (!model.has("maxTokens") || !model.has("name") ||
                    !model.has("scenarioTemperature") || !model.has("scriptTemperature")) {
                log.error("필수 모델 설정이 없습니다. 서버를 종료합니다.");
                System.exit(1);
            }

            log.info("설정 검증 완료");
        } catch (Exception e) {
            log.error("설정 검증 실패: {}. 서버를 종료합니다.", e.getMessage(), e);
            System.exit(1);
        }
    }

    private void validateScenario(@NotNull JsonObject scenario) {
        if (!scenario.has("scenario_data") || !scenario.has("object_instructions")) {
            throw new RuntimeException("시나리오 구조가 올바르지 않습니다: scenario_data 또는 object_instructions 누락");
        }

        JsonObject scenarioData = scenario.getAsJsonObject("scenario_data");
        if (!scenarioData.has("theme") || !scenarioData.has("description") ||
                !scenarioData.has("escape_condition") || !scenarioData.has("puzzle_flow")) {
            throw new RuntimeException("시나리오 데이터가 불완전합니다");
        }

        JsonArray objectInstructions = scenario.getAsJsonArray("object_instructions");
        if (objectInstructions.isEmpty()) {
            throw new RuntimeException("오브젝트 설명이 없습니다");
        }

        JsonObject firstObject = objectInstructions.get(0).getAsJsonObject();
        if (!firstObject.has("name") || !firstObject.get("name").getAsString().equals("GameManager")) {
            throw new RuntimeException("첫 번째 오브젝트가 GameManager가 아닙니다");
        }
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

        for (String keyword : uniqueKeywords) {
            array.add(keyword);
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

            if (instruction.has("type") && "game_manager".equals(instruction.get("type").getAsString())) {
                log.debug("GameManager는 모델 생성에서 건너뜁니다.");
                continue;
            }

            if (!instruction.has("name") || !instruction.has("visual_description")) {
                log.warn("object_instructions[{}]에 'name' 또는 'visual_description'이 없습니다. 건너뜁니다.", i);
                continue;
            }

            String objectName = instruction.get("name").getAsString();
            String visualDescription = instruction.get("visual_description").getAsString();

            if (objectName == null || objectName.trim().isEmpty() || visualDescription == null || visualDescription.trim().isEmpty()) {
                log.warn("object_instructions[{}]에 'name' 또는 'visual_description'이 비어있습니다. 건너뜁니다.", i);
                continue;
            }

            futures.add(createModelTask(visualDescription, objectName, i));
        }

        log.info("모델 생성 태스크 총 {}개 추가 완료.", futures.size());
        return futures;
    }

    @NotNull
    @Contract("_, _, _ -> new")
    private CompletableFuture<ModelGenerationResult> createModelTask(String prompt, String name, int index) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                log.debug("3D 모델 생성 요청 [{}]: name='{}', prompt='{}자'", index, name, prompt.length());
                String trackingId = meshyService.generateModel(prompt, name, index);

                String resultId = trackingId != null && !trackingId.trim().isEmpty()
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
    private Map<String, String> createUnifiedScripts(JsonObject scenario, String roomPrefabUrl, JsonObject config) {
        try {
            String prompt = getPrompt(config, "unified_scripts");
            JsonObject scriptRequest = new JsonObject();
            scriptRequest.add("scenario_data", scenario.getAsJsonObject("scenario_data"));
            scriptRequest.add("object_instructions", scenario.getAsJsonArray("object_instructions"));
            scriptRequest.addProperty("room_prefab_url", roomPrefabUrl);

            Map<String, String> allScripts = anthropicService.generateUnifiedScripts(prompt, scriptRequest);
            if (allScripts == null || allScripts.isEmpty()) {
                throw new RuntimeException("통합 스크립트 생성 실패");
            }

            log.info("통합 스크립트 생성 완료: {} 개", allScripts.size());
            return allScripts;

        } catch (Exception e) {
            throw new RuntimeException("통합 스크립트 생성 실패: " + e.getMessage(), e);
        }
    }

    @NotNull
    private JsonObject waitForModels(@NotNull List<CompletableFuture<ModelGenerationResult>> futures) {
        JsonObject tracking = new JsonObject();
        JsonObject failedModels = new JsonObject();

        if (futures.isEmpty()) {
            return createEmptyTracking();
        }

        log.info("3D 모델 생성 완료 대기 중: {} 개 (최대 {}분)", futures.size(), MODEL_TIMEOUT_MINUTES);

        try {
            CompletableFuture<Void> allFutures = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
            allFutures.get(MODEL_TIMEOUT_MINUTES, TimeUnit.MINUTES);

            for (int i = 0; i < futures.size(); i++) {
                try {
                    ModelGenerationResult result = futures.get(i).get();
                    addTrackingResult(tracking, failedModels, result);
                } catch (Exception e) {
                    log.error("모델 결과 수집 실패: index={}", i, e);
                    failedModels.addProperty("error_" + i, "collection_error-" + System.currentTimeMillis());
                }
            }

        } catch (java.util.concurrent.TimeoutException e) {
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
        } catch (Exception e) {
            log.error("모델 생성 대기 중 오류 발생", e);
        }

        if (!failedModels.asMap().isEmpty()) {
            tracking.add("failed_models", failedModels);
        }

        return tracking.asMap().isEmpty() ? createEmptyTracking() : tracking;
    }

    private void addTrackingResult(JsonObject tracking, JsonObject failedModels, ModelGenerationResult result) {
        if (result == null) {
            log.warn("모델 결과가 null입니다");
            return;
        }

        String trackingId = result.getTrackingId();
        String objectName = result.getObjectName();

        if (objectName == null || objectName.trim().isEmpty()) {
            log.warn("오브젝트 이름이 없습니다: {}", result);
            return;
        }

        objectName = objectName.trim();

        if (trackingId != null && !trackingId.trim().isEmpty()) {
            if (trackingId.startsWith("error-")) {
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
    private JsonObject buildScriptsObject(@NotNull Map<String, String> allScripts) {
        JsonObject scripts = new JsonObject();

        for (Map.Entry<String, String> entry : allScripts.entrySet()) {
            String scriptName = entry.getKey();
            String base64Content = entry.getValue();

            if (scriptName != null && !scriptName.trim().isEmpty() &&
                    base64Content != null && !base64Content.trim().isEmpty()) {

                String fileName = scriptName.trim();
                if (!fileName.endsWith(".cs")) {
                    fileName += ".cs";
                }
                scripts.addProperty(fileName, base64Content.trim());
            } else {
                log.warn("유효하지 않은 스크립트 엔트리: name={}, contentEmpty={}",
                        scriptName, base64Content == null || base64Content.isEmpty());
            }
        }

        log.debug("스크립트 객체 생성 완료: {} 개의 스크립트", scripts.size());
        return scripts;
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

    private String getPrompt(@NotNull JsonObject config, String type) {
        try {
            return config.getAsJsonObject("prompts").get(type).getAsString();
        } catch (Exception e) {
            throw new RuntimeException("프롬프트 설정을 찾을 수 없습니다: " + type, e);
        }
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