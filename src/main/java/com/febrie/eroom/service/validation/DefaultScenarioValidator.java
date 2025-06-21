package com.febrie.eroom.service.validation;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public class DefaultScenarioValidator implements ScenarioValidator {

    @Override
    public void validate(JsonObject scenario) throws RuntimeException {
        validateStructure(scenario);
        validateScenarioData(scenario);
        validateObjectInstructions(scenario);
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
}