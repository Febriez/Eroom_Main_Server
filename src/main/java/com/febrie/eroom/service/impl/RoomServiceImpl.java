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
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RoomServiceImpl implements RoomService {

    private static final Logger log = LoggerFactory.getLogger(RoomServiceImpl.class);

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
        log.info("테마: {}에 대한 방 생성 프로세스 시작", request.getTheme());

        String puid = UUID.randomUUID().toString();

        JsonObject configJson = configUtil.getConfig();
        String scenarioPrompt = configJson.getAsJsonObject("prompts").get("scenario").getAsString();

        JsonObject scenarioRequest = new JsonObject();
        scenarioRequest.addProperty("puid", puid);
        scenarioRequest.addProperty("theme", request.getTheme());
        JsonArray keywordsArray = new JsonArray();
        for (String keyword : request.getKeywords()) {
            keywordsArray.add(keyword);
        }
        scenarioRequest.add("keywords", keywordsArray);

        JsonObject scenario = anthropicService.generateScenario(scenarioPrompt, scenarioRequest);

        if (scenario == null) {
            throw new RuntimeException("시나리오 생성 실패");
        }

        log.info("시나리오가 성공적으로 생성됨");

        List<CompletableFuture<ModelGenerationResult>> modelFutures = new ArrayList<>();
        JsonObject modelTrackingInfo = new JsonObject();

        if (scenario.has("keywords") && scenario.get("keywords").isJsonArray()) {
            JsonArray keywords = scenario.getAsJsonArray("keywords");

            for (int i = 0; i < keywords.size(); i++) {
                JsonObject keyword = keywords.get(i).getAsJsonObject();
                String modelPrompt = keyword.get("value").getAsString();
                String objectName = keyword.get("name").getAsString();

                final int keyIndex = i;
                CompletableFuture<ModelGenerationResult> modelFuture = CompletableFuture.supplyAsync(() -> {
                    try {
                        String trackingId = meshyService.generateModel(modelPrompt, objectName, keyIndex);
                        if (trackingId == null) {
                            log.warn("{}의 모델 생성에 실패했거나 trackingId가 null입니다. 더미 ID 할당", objectName);
                            // 모델 생성에 실패한 경우 더미 ID 할당
                            trackingId = "pending-" + UUID.randomUUID().toString();
                        }
                        return new ModelGenerationResult(objectName, trackingId);
                    } catch (Exception e) {
                        log.error("{}의 모델 생성 중 오류 발생", objectName, e);
                        // 오류 발생시에도 더미 ID 할당하여 추적 가능하게 함
                        String fallbackId = "error-" + UUID.randomUUID().toString();
                        return new ModelGenerationResult(objectName, fallbackId);
                    }
                }, executorService);

                modelFutures.add(modelFuture);
            }
        }

        String gameManagerPrompt = configJson.getAsJsonObject("prompts").get("gameManager").getAsString();
        JsonObject gameManagerRequest = new JsonObject();
        gameManagerRequest.addProperty("room_prefab", request.getRoomPrefab());
        gameManagerRequest.add("data", scenario);

        String gameManagerScript = anthropicService.generateGameManagerScript(gameManagerPrompt, gameManagerRequest);

        if (gameManagerScript == null) {
            throw new RuntimeException("게임 매니저 스크립트 생성 실패");
        }

        log.info("게임 매니저 스크립트가 성공적으로 생성됨");

        List<String> objectScripts = new ArrayList<>();

        // 모든 오브젝트 스크립트를 한 번에 생성 (GameManager 제외)
        if (scenario.has("data") && scenario.get("data").isJsonArray()) {
            String bulkObjectPrompt = configJson.getAsJsonObject("prompts").get("bulkObject").getAsString();

            // GameManager를 제외한 오브젝트 데이터만 추가
            JsonArray allData = scenario.getAsJsonArray("data");
            JsonArray filteredData = new JsonArray();

            for (int i = 0; i < allData.size(); i++) {
                JsonObject obj = allData.get(i).getAsJsonObject();
                if (obj.has("name") && !"GameManager".equals(obj.get("name").getAsString())) {
                    filteredData.add(obj);
                }
            }

            JsonObject bulkRequest = new JsonObject();
            bulkRequest.add("objects_data", filteredData);
            bulkRequest.addProperty("game_manager_script", gameManagerScript);
            if (scenario.has("scenario_data")) {
                bulkRequest.add("scenario_context", scenario.get("scenario_data"));
            }

            try {
                String bulkObjectScripts = anthropicService.generateBulkObjectScripts(bulkObjectPrompt, bulkRequest);

                if (bulkObjectScripts != null) {
                    // 응답이 배열 또는 객체 형태로 올 수 있으므로 유연하게 처리
                    JsonElement parsedElement = JsonParser.parseString(bulkObjectScripts);

                    if (parsedElement.isJsonObject()) {
                        JsonObject scriptsJson = parsedElement.getAsJsonObject();
                        for (java.util.Map.Entry<String, JsonElement> entry : scriptsJson.entrySet()) {
                            objectScripts.add(entry.getValue().getAsString());
                        }
                    } else if (parsedElement.isJsonArray()) {
                        JsonArray scriptsArray = parsedElement.getAsJsonArray();
                        for (int i = 0; i < scriptsArray.size(); i++) {
                            JsonObject scriptObj = scriptsArray.get(i).getAsJsonObject();
                            for (java.util.Map.Entry<String, JsonElement> entry : scriptObj.entrySet()) {
                                objectScripts.add(entry.getValue().getAsString());
                            }
                        }
                    }

                    log.info("일괄 오브젝트 스크립트가 성공적으로 생성됨: {} 개의 스크립트", objectScripts.size());
                } else {
                    throw new RuntimeException("일괄 오브젝트 스크립트 생성 실패");
                }
            } catch (Exception e) {
                log.error("일괄 오브젝트 스크립트 생성 중 오류 발생", e);
                throw new RuntimeException("오브젝트 스크립트 생성 실패", e);
            }
        }

        CompletableFuture.allOf(modelFutures.toArray(new CompletableFuture[0])).join();

        for (CompletableFuture<ModelGenerationResult> future : modelFutures) {
            try {
                ModelGenerationResult result = future.join();
                if (result != null) {
                    String trackingId = result.getTrackingId();
                    String objectName = result.getObjectName();

                    if (trackingId != null) {
                        modelTrackingInfo.addProperty(objectName, trackingId);
                        log.info("모델 생성 결과 추적 정보 추가: {} -> {}", objectName, trackingId);
                    } else {
                        // trackingId가 null인 경우에도 더미 값을 추가
                        String fallbackId = "no-tracking-" + UUID.randomUUID().toString();
                        modelTrackingInfo.addProperty(objectName, fallbackId);
                        log.warn("모델 trackingId가 null입니다. 더미 ID 할당: {} -> {}", objectName, fallbackId);
                    }
                } else {
                    log.warn("모델 생성 결과가 null입니다");
                }
            } catch (Exception e) {
                log.error("모델 결과 수집 중 오류 발생", e);
                // 오류 발생해도 빈 모델 추적 객체가 아닌 오류 정보를 포함한 객체 반환
                modelTrackingInfo.addProperty("error_" + modelFutures.indexOf(future), "error-" + e.getMessage());
            }
        }

        // 모델 추적 정보가 비어있는 경우 기본값 추가
        if (modelTrackingInfo.isEmpty()) {
            log.warn("모델 추적 정보가 비어있습니다. 기본 상태 정보 추가");
            modelTrackingInfo.addProperty("status", "no_models_generated");
            modelTrackingInfo.addProperty("timestamp", String.valueOf(System.currentTimeMillis()));
        }

        // 1. scenario에서 keywords 필드 제거
        if (scenario.has("keywords")) {
            scenario.remove("keywords");
        }

        // 응답 객체 생성
        JsonObject response = new JsonObject();
        response.addProperty("uuid", request.getUuid());
        response.addProperty("puid", puid);
        response.addProperty("theme", request.getTheme());
        response.add("keywords", keywordsArray);
        response.addProperty("room_prefab", request.getRoomPrefab());
        response.add("scenario", scenario);

        // 2. game_manager_script 필드 제거 (최상위 필드로 추가하지 않음)
        // 대신 object_scripts 객체에 포함시킴

        // 3. object_scripts 구조 변경 (배열 → 객체)
        JsonObject scriptsObject = new JsonObject();

        // GameManager 스크립트 추가
        scriptsObject.addProperty("GameManager.cs", gameManagerScript);

        // 일반 오브젝트 스크립트 추가
        // 파일명 추출을 위한 간단한 정규식 사용
        java.util.regex.Pattern classPattern = java.util.regex.Pattern.compile("public class (\\w+)");

        for (String script : objectScripts) {
            // 클래스 이름 추출 시도
            java.util.regex.Matcher matcher = classPattern.matcher(script);
            String className = "Object" + objectScripts.indexOf(script);

            if (matcher.find()) {
                className = matcher.group(1);
            }

            // 파일명.cs 형태로 저장
            scriptsObject.addProperty(className + ".cs", script);
        }

        response.add("object_scripts", scriptsObject);

        // 모델 URL 배열 대신 모델 추적 정보 객체 추가
        response.add("model_tracking", modelTrackingInfo);

        response.addProperty("success", true);

        log.info("방 생성이 성공적으로 완료됨");
        return response;
    }
}
