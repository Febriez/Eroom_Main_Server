package com.febrie.eroom.service.impl;

import com.febrie.eroom.model.ModelGenerationResult;
import com.febrie.eroom.model.RoomCreationRequest;
import com.febrie.eroom.service.AnthropicService;
import com.febrie.eroom.service.MeshyService;
import com.febrie.eroom.service.RoomService;
import com.febrie.eroom.util.ConfigUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class RoomServiceImpl implements RoomService {

    private static final Logger log = LoggerFactory.getLogger(RoomServiceImpl.class);
    private static final int MODEL_TIMEOUT_MINUTES = 10;

    private final AnthropicService anthropicService;
    private final MeshyService meshyService;
    private final ConfigUtil configUtil;
    private final ExecutorService executorService;

    public RoomServiceImpl(AnthropicService anthropicService, MeshyService meshyService, ConfigUtil configUtil) {
        this.anthropicService = anthropicService;
        this.meshyService = meshyService;
        this.configUtil = configUtil;
        this.executorService = Executors.newFixedThreadPool(10);
    }

    @Override
    public JsonObject createRoom(@NotNull RoomCreationRequest request) {
        log.info("방 생성 시작: theme={}, keywords={}", request.getTheme(), Arrays.toString(request.getKeywords()));

        // 입력 검증
        if (request.getTheme() == null || request.getTheme().trim().isEmpty()) {
            return createErrorResponse(request, "테마가 비어있습니다");
        }

        if (request.getKeywords() == null || request.getKeywords().length == 0) {
            return createErrorResponse(request, "키워드가 비어있습니다");
        }

        try {
            String puid = generatePuid();
            JsonObject config = configUtil.getConfig();

            // 각 단계별 생성
            JsonObject scenario = createScenario(request, puid, config);
            List<CompletableFuture<ModelGenerationResult>> modelFutures = startModelGeneration(scenario);
            AnthropicService.GameManagerResult gameManagerResult = createGameManagerScript(request, scenario, config);
            Map<String, String> objectScripts = createObjectScripts(scenario, config, gameManagerResult.originalScript());
            JsonObject modelTracking = waitForModels(modelFutures);

            JsonObject response = buildFinalResponse(request, puid, scenario, gameManagerResult.base64Script(), objectScripts, modelTracking);
            log.info("방 생성 완료: puid={}, 스크립트 수={}", puid,
                    response.getAsJsonObject("object_scripts").size());
            return response;

        } catch (RuntimeException e) {
            log.error("방 생성 중 비즈니스 오류 발생: {}", e.getMessage());
            return createErrorResponse(request, e.getMessage());
        } catch (Exception e) {
            log.error("방 생성 중 시스템 오류 발생", e);
            return createErrorResponse(request, "시스템 오류가 발생했습니다");
        }
    }

    private String generatePuid() {
        return "room_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    @NotNull
    private JsonObject createScenario(RoomCreationRequest request, String puid, JsonObject config) {
        try {
            String prompt = getPrompt(config, "scenario");
            JsonObject scenarioRequest = buildScenarioRequest(request, puid);

            JsonObject scenario = anthropicService.generateScenario(prompt, scenarioRequest);
            if (scenario == null) {
                throw new RuntimeException("시나리오 생성 실패");
            }

            // 시나리오 검증
            if (!scenario.has("scenario_data") || !scenario.has("data")) {
                throw new RuntimeException("시나리오 구조가 올바르지 않습니다");
            }

            log.info("시나리오 생성 완료: {} 개의 오브젝트",
                    scenario.getAsJsonArray("data").size());
            return scenario;

        } catch (Exception e) {
            throw new RuntimeException("시나리오 생성 실패: " + e.getMessage(), e);
        }
    }

    @NotNull
    private JsonObject buildScenarioRequest(@NotNull RoomCreationRequest request, String puid) {
        JsonObject scenarioRequest = new JsonObject();
        scenarioRequest.addProperty("puid", puid);
        scenarioRequest.addProperty("theme", request.getTheme().trim());
        scenarioRequest.add("keywords", createKeywordsArray(request.getKeywords()));

        // 추가 컨텍스트
        if (request.getRoomPrefab() != null && !request.getRoomPrefab().trim().isEmpty()) {
            scenarioRequest.addProperty("room_type", request.getRoomPrefab().trim());
        }

        return scenarioRequest;
    }

    @NotNull
    private JsonArray createKeywordsArray(@NotNull String[] keywords) {
        JsonArray array = new JsonArray();
        Set<String> uniqueKeywords = new LinkedHashSet<>(); // 중복 제거 + 순서 유지

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
    private List<CompletableFuture<ModelGenerationResult>> startModelGeneration(JsonObject scenario) {
        List<CompletableFuture<ModelGenerationResult>> futures = new ArrayList<>();

        if (!hasKeywords(scenario)) {
            log.info("키워드가 없어 3D 모델 생성을 건너뜁니다");
            return futures;
        }

        JsonArray keywords = scenario.getAsJsonArray("keywords");
        log.info("3D 모델 생성 시작: {} 개의 모델", keywords.size());

        for (int i = 0; i < keywords.size(); i++) {
            JsonObject keyword = keywords.get(i).getAsJsonObject();
            if (keyword.has("name") && keyword.has("value")) {
                futures.add(createModelTask(
                        keyword.get("value").getAsString(),
                        keyword.get("name").getAsString(),
                        i
                ));
            }
        }

        return futures;
    }

    private boolean hasKeywords(@NotNull JsonObject scenario) {
        return scenario.has("keywords") &&
                scenario.get("keywords").isJsonArray() &&
                !scenario.getAsJsonArray("keywords").isEmpty();
    }

    @NotNull
    @Contract("_, _, _ -> new")
    private CompletableFuture<ModelGenerationResult> createModelTask(String prompt, String name, int index) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                log.debug("3D 모델 생성 요청: {} ({})", name, index);
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
    private AnthropicService.GameManagerResult createGameManagerScript(@NotNull RoomCreationRequest request, JsonObject scenario, JsonObject config) {
        try {
            String prompt = getPrompt(config, "gameManager");
            JsonObject gameManagerRequest = new JsonObject();
            gameManagerRequest.addProperty("room_prefab", request.getRoomPrefab());
            gameManagerRequest.add("scenario_data", scenario);

            AnthropicService.GameManagerResult result = anthropicService.generateGameManagerScript(prompt, gameManagerRequest);
            if (result == null || result.base64Script() == null || result.base64Script().isEmpty()) {
                throw new RuntimeException("게임 매니저 스크립트 생성 실패");
            }

            log.info("게임 매니저 스크립트 생성 완료");
            return result;

        } catch (Exception e) {
            throw new RuntimeException("게임 매니저 스크립트 생성 실패: " + e.getMessage(), e);
        }
    }

    @NotNull
    private Map<String, String> createObjectScripts(JsonObject scenario, JsonObject config, String gameManagerScript) {
        if (!hasObjectData(scenario)) {
            log.warn("오브젝트 데이터가 없어 스크립트 생성을 건너뜁니다");
            return new HashMap<>();
        }

        try {
            JsonArray filteredObjects = filterObjects(scenario.getAsJsonArray("data"));
            if (filteredObjects.isEmpty()) {
                log.warn("GameManager를 제외한 오브젝트가 없습니다");
                return new HashMap<>();
            }

            Map<String, String> encodedScripts = requestObjectScripts(config, filteredObjects, gameManagerScript);

            if (encodedScripts == null || encodedScripts.isEmpty()) {
                log.warn("오브젝트 스크립트 생성 결과가 비어있습니다");
                return new HashMap<>();
            }

            log.info("오브젝트 스크립트 생성 완료: {} 개", encodedScripts.size());
            return encodedScripts;

        } catch (Exception e) {
            log.error("오브젝트 스크립트 생성 실패", e);
            return new HashMap<>();
        }
    }

    private boolean hasObjectData(@NotNull JsonObject scenario) {
        return scenario.has("data") &&
                scenario.get("data").isJsonArray() &&
                !scenario.getAsJsonArray("data").isEmpty();
    }

    @NotNull
    private JsonArray filterObjects(@NotNull JsonArray data) {
        JsonArray filtered = new JsonArray();
        for (JsonElement element : data) {
            if (element.isJsonObject()) {
                JsonObject obj = element.getAsJsonObject();
                if (obj.has("name")) {
                    String name = obj.get("name").getAsString();
                    if (name != null && !"GameManager".equals(name.trim())) {
                        filtered.add(obj);
                    }
                }
            }
        }
        log.debug("필터링된 오브젝트 수: {}", filtered.size());
        return filtered;
    }

    @Nullable
    private Map<String, String> requestObjectScripts(JsonObject config, JsonArray objects, String gameManagerScript) {
        String prompt = getPrompt(config, "bulkObject");
        JsonObject request = new JsonObject();
        request.add("objects_data", objects);
        request.addProperty("game_manager_script", gameManagerScript);

        return anthropicService.generateBulkObjectScripts(prompt, request);
    }

    @NotNull
    private JsonObject waitForModels(@NotNull List<CompletableFuture<ModelGenerationResult>> futures) {
        JsonObject tracking = new JsonObject();

        if (futures.isEmpty()) {
            return createEmptyTracking();
        }

        log.info("3D 모델 생성 완료 대기 중: {} 개 (최대 {}분)", futures.size(), MODEL_TIMEOUT_MINUTES);

        try {
            // 타임아웃과 함께 대기
            CompletableFuture<Void> allFutures = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
            allFutures.get(MODEL_TIMEOUT_MINUTES, TimeUnit.MINUTES);

            // 결과 수집
            for (int i = 0; i < futures.size(); i++) {
                try {
                    ModelGenerationResult result = futures.get(i).get();
                    addTrackingResult(tracking, result);
                } catch (Exception e) {
                    log.error("모델 결과 수집 실패: index={}", i, e);
                    tracking.addProperty("error_" + i, "collection_error-" + System.currentTimeMillis());
                }
            }

        } catch (java.util.concurrent.TimeoutException e) {
            log.warn("모델 생성 타임아웃 발생, 현재까지 완료된 결과만 수집");
            // 타임아웃 시에도 완료된 것들은 수집
            for (int i = 0; i < futures.size(); i++) {
                CompletableFuture<ModelGenerationResult> future = futures.get(i);
                if (future.isDone() && !future.isCompletedExceptionally()) {
                    try {
                        addTrackingResult(tracking, future.get());
                    } catch (Exception ex) {
                        log.debug("타임아웃 후 결과 수집 실패: index={}", i);
                    }
                } else {
                    tracking.addProperty("timeout_" + i, "timeout-" + System.currentTimeMillis());
                }
            }
        } catch (Exception e) {
            log.error("모델 생성 대기 중 오류 발생", e);
        }

        return tracking.isEmpty() ? createEmptyTracking() : tracking;
    }

    private void addTrackingResult(JsonObject tracking, ModelGenerationResult result) {
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
            tracking.addProperty(objectName, trackingId.trim());
            log.debug("모델 추적 ID 추가: {} -> {}", objectName, trackingId);
        } else {
            String fallbackId = "no-tracking-" + System.currentTimeMillis();
            tracking.addProperty(objectName, fallbackId);
            log.warn("trackingId가 없어 대체 ID 사용: {} -> {}", objectName, fallbackId);
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
    private JsonObject buildFinalResponse(@NotNull RoomCreationRequest request, String puid, @NotNull JsonObject scenario,
                                          String gameManagerScript, Map<String, String> objectScripts, JsonObject tracking) {

        // 시나리오에서 keywords 제거 (최종 응답에서는 별도 필드로)
        scenario.remove("keywords");

        JsonObject response = new JsonObject();
        response.addProperty("uuid", request.getUuid());
        response.addProperty("puid", puid);
        response.addProperty("theme", request.getTheme());
        response.add("keywords", createKeywordsArray(request.getKeywords()));
        response.addProperty("room_prefab", request.getRoomPrefab());
        response.add("scenario", scenario);
        response.add("object_scripts", buildScriptsObject(gameManagerScript, objectScripts));
        response.add("model_tracking", tracking);
        response.addProperty("success", true);
        response.addProperty("timestamp", String.valueOf(System.currentTimeMillis()));

        return response;
    }

    @NotNull
    private JsonObject buildScriptsObject(String gameManagerScript, @NotNull Map<String, String> objectScripts) {
        JsonObject scripts = new JsonObject();

        // GameManager 스크립트 추가
        if (gameManagerScript != null && !gameManagerScript.isEmpty()) {
            scripts.addProperty("GameManager.cs", gameManagerScript);
        } else {
            log.warn("GameManager 스크립트가 비어있습니다");
        }

        // 오브젝트 스크립트들 추가
        for (Map.Entry<String, String> entry : objectScripts.entrySet()) {
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
    private JsonObject createErrorResponse(@NotNull RoomCreationRequest request, String errorMessage) {
        JsonObject errorResponse = new JsonObject();
        errorResponse.addProperty("uuid", request.getUuid());
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
}