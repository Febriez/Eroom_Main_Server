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
import com.google.gson.JsonParser;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

public class RoomServiceImpl implements RoomService {

    private static final Logger log = LoggerFactory.getLogger(RoomServiceImpl.class);
    private static final Pattern CLASS_PATTERN = Pattern.compile("public class (\\w+)");

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
        log.info("방 생성 시작: {}", request.getTheme());

        String puid = generatePuid();
        JsonObject config = configUtil.getConfig();

        JsonObject scenario = createScenario(request, puid, config);
        List<CompletableFuture<ModelGenerationResult>> modelFutures = startModelGeneration(scenario);
        String gameManagerScript = createGameManagerScript(request, scenario, config);
        List<String> objectScripts = createObjectScripts(scenario, config, gameManagerScript);
        JsonObject modelTracking = waitForModels(modelFutures);

        return buildFinalResponse(request, puid, scenario, gameManagerScript, objectScripts, modelTracking);
    }

    private String generatePuid() {
        return UUID.randomUUID().toString();
    }

    @NotNull
    private JsonObject createScenario(RoomCreationRequest request, String puid, JsonObject config) {
        String prompt = getPrompt(config, "scenario");
        JsonObject scenarioRequest = buildScenarioRequest(request, puid);

        JsonObject scenario = anthropicService.generateScenario(prompt, scenarioRequest);
        if (scenario == null) {
            throw new RuntimeException("시나리오 생성 실패");
        }

        log.info("시나리오 생성 완료");
        return scenario;
    }

    @NotNull
    private JsonObject buildScenarioRequest(@NotNull RoomCreationRequest request, String puid) {
        JsonObject scenarioRequest = new JsonObject();
        scenarioRequest.addProperty("puid", puid);
        scenarioRequest.addProperty("theme", request.getTheme());
        scenarioRequest.add("keywords", createKeywordsArray(request.getKeywords()));
        return scenarioRequest;
    }

    @NotNull
    private JsonArray createKeywordsArray(@NotNull String[] keywords) {
        JsonArray array = new JsonArray();
        for (String keyword : keywords) {
            array.add(keyword);
        }
        return array;
    }

    @NotNull
    private List<CompletableFuture<ModelGenerationResult>> startModelGeneration(JsonObject scenario) {
        List<CompletableFuture<ModelGenerationResult>> futures = new ArrayList<>();

        if (!hasKeywords(scenario)) {
            return futures;
        }

        JsonArray keywords = scenario.getAsJsonArray("keywords");
        for (int i = 0; i < keywords.size(); i++) {
            JsonObject keyword = keywords.get(i).getAsJsonObject();
            futures.add(createModelTask(
                    keyword.get("value").getAsString(),
                    keyword.get("name").getAsString(),
                    i
            ));
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
                String trackingId = meshyService.generateModel(prompt, name, index);
                return new ModelGenerationResult(name, trackingId != null ? trackingId : "pending-" + UUID.randomUUID());
            } catch (Exception e) {
                log.error("모델 생성 실패: {}", name, e);
                return new ModelGenerationResult(name, "error-" + UUID.randomUUID());
            }
        }, executorService);
    }

    @NotNull
    private String createGameManagerScript(@NotNull RoomCreationRequest request, JsonObject scenario, JsonObject config) {
        String prompt = getPrompt(config, "gameManager");
        JsonObject gameManagerRequest = new JsonObject();
        gameManagerRequest.addProperty("room_prefab", request.getRoomPrefab());
        gameManagerRequest.add("data", scenario);

        String script = anthropicService.generateGameManagerScript(prompt, gameManagerRequest);
        if (script == null) {
            throw new RuntimeException("게임 매니저 스크립트 생성 실패");
        }

        log.info("게임 매니저 스크립트 생성 완료");
        return script;
    }

    @NotNull
    private List<String> createObjectScripts(JsonObject scenario, JsonObject config, String gameManagerScript) {
        if (!hasObjectData(scenario)) {
            log.warn("오브젝트 데이터 없음");
            return new ArrayList<>();
        }

        try {
            JsonArray filteredObjects = filterObjects(scenario.getAsJsonArray("data"));
            String bulkScripts = requestObjectScripts(config, filteredObjects, gameManagerScript);

            List<String> scripts = parseScripts(bulkScripts);
            log.info("오브젝트 스크립트 생성 완료: {}개", scripts.size());
            return scripts;
        } catch (Exception e) {
            log.error("오브젝트 스크립트 생성 실패", e);
            throw new RuntimeException("오브젝트 스크립트 생성 실패", e);
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
            JsonObject obj = element.getAsJsonObject();
            if (obj.has("name") && !"GameManager".equals(obj.get("name").getAsString())) {
                filtered.add(obj);
            }
        }
        return filtered;
    }

    private String requestObjectScripts(JsonObject config, JsonArray objects, String gameManagerScript) {
        String prompt = getPrompt(config, "bulkObject");
        JsonObject request = new JsonObject();
        request.add("objects_data", objects);
        request.addProperty("game_manager_script", gameManagerScript);
        return anthropicService.generateBulkObjectScripts(prompt, request);
    }

    @NotNull
    private List<String> parseScripts(String bulkScripts) {
        List<String> scripts = new ArrayList<>();

        try {
            JsonArray scriptsArray = JsonParser.parseString(bulkScripts).getAsJsonArray();

            for (JsonElement element : scriptsArray) {
                JsonObject scriptObject = element.getAsJsonObject();
                if (scriptObject.has("key") && scriptObject.has("value")) {
                    String scriptName = scriptObject.get("key").getAsString();
                    String scriptCode = scriptObject.get("value").getAsString();
                    scripts.add(scriptCode);
                    log.debug("스크립트 추가: {}", scriptName);
                } else {
                    log.warn("스크립트 객체에 key 또는 value가 없음: {}", scriptObject);
                }
            }

        } catch (Exception e) {
            log.error("스크립트 파싱 중 오류 발생: {}", e.getMessage());
            throw new RuntimeException("스크립트 파싱 실패", e);
        }

        return scripts;
    }

    @NotNull
    private JsonObject waitForModels(@NotNull List<CompletableFuture<ModelGenerationResult>> futures) {
        JsonObject tracking = new JsonObject();

        if (futures.isEmpty()) {
            return createEmptyTracking();
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        for (int i = 0; i < futures.size(); i++) {
            try {
                ModelGenerationResult result = futures.get(i).join();
                addTrackingResult(tracking, result);
            } catch (Exception e) {
                log.error("모델 결과 수집 실패", e);
                tracking.addProperty("error_" + i, "error-" + e.getMessage());
            }
        }

        return tracking.isEmpty() ? createEmptyTracking() : tracking;
    }

    private void addTrackingResult(JsonObject tracking, ModelGenerationResult result) {
        if (result == null) {
            log.warn("모델 결과가 null");
            return;
        }

        String trackingId = result.getTrackingId();
        String objectName = result.getObjectName();

        if (trackingId != null) {
            tracking.addProperty(objectName, trackingId);
        } else {
            tracking.addProperty(objectName, "no-tracking-" + UUID.randomUUID());
            log.warn("trackingId가 null: {}", objectName);
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
                                          String gameManagerScript, List<String> objectScripts, JsonObject tracking) {
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

        log.info("방 생성 완료");
        return response;
    }

    @NotNull
    private JsonObject buildScriptsObject(String gameManagerScript, @NotNull List<String> objectScripts) {
        JsonObject scripts = new JsonObject();
        scripts.addProperty("GameManager.cs", gameManagerScript);

        for (int i = 0; i < objectScripts.size(); i++) {
            String script = objectScripts.get(i);
            String className = extractClassName(script, i);
            scripts.addProperty(className + ".cs", script);
        }

        return scripts;
    }

    private String extractClassName(String script, int index) {
        return CLASS_PATTERN.matcher(script)
                .results()
                .findFirst()
                .map(match -> match.group(1))
                .orElse("Object" + index);
    }

    private String getPrompt(@NotNull JsonObject config, String type) {
        return config.getAsJsonObject("prompts").get(type).getAsString();
    }
}