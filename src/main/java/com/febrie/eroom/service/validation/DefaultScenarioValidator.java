package com.febrie.eroom.service.validation;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

public class DefaultScenarioValidator implements ScenarioValidator {
    private static final Logger log = LoggerFactory.getLogger(DefaultScenarioValidator.class);

    @Override
    public void validate(JsonObject scenario) throws RuntimeException {
        validateStructure(scenario);
        validateScenarioData(scenario);
        validateObjectInstructions(scenario);
        validateObjectDiversity(scenario);
    }

    private void validateStructure(JsonObject scenario) {
        if (!scenario.has("scenario_data") || !scenario.has("object_instructions")) {
            throw new RuntimeException("시나리오 구조가 올바르지 않습니다: scenario_data 또는 object_instructions 누락");
        }
    }

    private void validateScenarioData(JsonObject scenario) {
        JsonObject scenarioData = scenario.getAsJsonObject("scenario_data");
        if (!scenarioData.has("theme") || !scenarioData.has("description") ||
                !scenarioData.has("escape_condition") || !scenarioData.has("puzzle_flow")) {
            throw new RuntimeException("시나리오 데이터가 불완전합니다");
        }
    }

    private void validateObjectInstructions(JsonObject scenario) {
        JsonArray objectInstructions = scenario.getAsJsonArray("object_instructions");
        if (objectInstructions.isEmpty()) {
            throw new RuntimeException("오브젝트 설명이 없습니다");
        }

        JsonObject firstObject = objectInstructions.get(0).getAsJsonObject();
        if (!firstObject.has("name") || !firstObject.get("name").getAsString().equals("GameManager")) {
            throw new RuntimeException("첫 번째 오브젝트가 GameManager가 아닙니다");
        }
    }

    private void validateObjectDiversity(JsonObject scenario) {
        JsonArray objectInstructions = scenario.getAsJsonArray("object_instructions");
        Set<String> objectBaseNames = new HashSet<>();
        Set<String> duplicateWarnings = new HashSet<>();

        for (int i = 0; i < objectInstructions.size(); i++) {
            JsonObject obj = objectInstructions.get(i).getAsJsonObject();
            if (!obj.has("name")) continue;

            String objectName = obj.get("name").getAsString();
            String type = obj.has("type") ? obj.get("type").getAsString() : "";

            // GameManager와 existing_interactive_object는 검사에서 제외
            if ("game_manager".equals(type) || "existing_interactive_object".equals(type)) {
                continue;
            }

            // 오브젝트 이름에서 기본 타입 추출 (예: CrystalCandle -> Candle)
            String baseName = extractBaseName(objectName);

            if (objectBaseNames.contains(baseName)) {
                duplicateWarnings.add(baseName);
            }
            objectBaseNames.add(baseName);
        }

        if (!duplicateWarnings.isEmpty()) {
            log.warn("유사한 오브젝트 타입이 중복됨: {}. 더 다양한 오브젝트를 생성하는 것을 권장합니다.",
                    String.join(", ", duplicateWarnings));
        }

        log.info("새로 생성된 오브젝트 타입: {} 종류", objectBaseNames.size());
    }

    private String extractBaseName(String objectName) {
        // 일반적인 수식어 제거
        String[] modifiers = {"Crystal", "Modern", "Ancient", "Victorian", "Golden", "Silver",
                "Old", "New", "Large", "Small", "Big", "Tiny", "Dark", "Light", "Bright"};

        String baseName = objectName;
        for (String modifier : modifiers) {
            if (baseName.startsWith(modifier)) {
                baseName = baseName.substring(modifier.length());
            }
        }

        // 숫자 제거 (예: Candle1 -> Candle)
        baseName = baseName.replaceAll("\\d+$", "");

        return baseName;
    }
}