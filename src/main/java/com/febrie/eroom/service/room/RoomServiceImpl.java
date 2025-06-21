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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class RoomServiceImpl implements RoomService, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(RoomServiceImpl.class);
    private static final int MODEL_TIMEOUT_MINUTES = 10;
    private static final int EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS = 60;

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
        this.executorService = Executors.newFixedThreadPool(10);
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
            Map<String, String> allScripts = createUnifiedScripts(scenario, request.getRoomPrefab());
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
        JsonArray initialKeywords = createKeywordsArray(request.getKeywords());

        scenarioRequest.addProperty("uuid", request.getUuid());
        scenarioRequest.addProperty("ruid", ruid);
        scenarioRequest.addProperty("theme", request.getTheme().trim());
        scenarioRequest.add("keywords", initialKeywords);
        scenarioRequest.addProperty("difficulty", request.getValidatedDifficulty());
        scenarioRequest.addProperty("room_prefab_url", request.getRoomPrefab().trim());

        return scenarioRequest;
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

            if (isGameManager(instruction)) {
                log.debug("GameManager는 모델 생성에서 건너뜁니다.");
                continue;
            }

            if (!hasRequiredFields(instruction)) {
                log.warn("object_instructions[{}]에 필수 필드가 없습니다. 건너뜁니다.", i);
                continue;
            }

            String objectName = instruction.get("name").getAsString();
            String visualDescription = instruction.get("visual_description").getAsString();

            if (isInvalidNameOrDescription(objectName, visualDescription)) {
                log.warn("object_instructions[{}]에 유효하지 않은 데이터가 있습니다. 건너뜁니다.", i);
                continue;
            }

            futures.add(createModelTask(visualDescription, objectName, i));
        }

        log.info("모델 생성 태스크 총 {}개 추가 완료.", futures.size());
        return futures;
    }

    private boolean isGameManager(@NotNull JsonObject instruction) {
        return instruction.has("type") && "game_manager".equals(instruction.get("type").getAsString());
    }

    private boolean hasRequiredFields(@NotNull JsonObject instruction) {
        return instruction.has("name") && instruction.has("visual_description");
    }

    private boolean isInvalidNameOrDescription(String name, String description) {
        return name == null || name.trim().isEmpty() || description == null || description.trim().isEmpty();
    }

    @NotNull
    @Contract("_, _, _ -> new")
    private CompletableFuture<ModelGenerationResult> createModelTask(String prompt, String name, int index) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                log.debug("3D 모델 생성 요청 [{}]: name='{}', prompt='{}자'", index, name, prompt.length());
                String trackingId = meshService.generateModel(prompt, name, index);

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
    private Map<String, String> createUnifiedScripts(JsonObject scenario, String roomPrefabUrl) {
        try {
            String prompt = configManager.getPrompt("unified_scripts");
            JsonObject scriptRequest = buildScriptRequest(scenario, roomPrefabUrl);

            Map<String, String> allScripts = aiService.generateUnifiedScripts(prompt, scriptRequest);
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
    private JsonObject buildScriptRequest(@NotNull JsonObject scenario, String roomPrefabUrl) {
        JsonObject scriptRequest = new JsonObject();
        scriptRequest.add("scenario_data", scenario.getAsJsonObject("scenario_data"));
        scriptRequest.add("object_instructions", scenario.getAsJsonArray("object_instructions"));
        scriptRequest.addProperty("room_prefab_url", roomPrefabUrl);
        return scriptRequest;
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

            collectResults(futures, tracking, failedModels);

        } catch (java.util.concurrent.TimeoutException e) {
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
    private JsonObject buildFinalResponse(@NotNull RoomCreationRequest request, String ruid, JsonObject scenario,
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

    @NotNull
    private JsonObject buildScriptsObject(@NotNull Map<String, String> allScripts) {
        JsonObject scripts = new JsonObject();

        for (Map.Entry<String, String> entry : allScripts.entrySet()) {
            String scriptName = entry.getKey();
            String base64Content = entry.getValue();

            if (isValidScriptEntry(scriptName, base64Content)) {
                String fileName = ensureFileExtension(scriptName.trim());
                scripts.addProperty(fileName, base64Content.trim());
            } else {
                log.warn("유효하지 않은 스크립트 엔트리: name={}, contentEmpty={}",
                        scriptName, base64Content == null || base64Content.isEmpty());
            }
        }

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

        for (String keyword : uniqueKeywords) {
            array.add(keyword);
        }

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