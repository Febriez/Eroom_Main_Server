package com.febrie.eroom.service.validation;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
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
        validateObjectFields(scenario);
        validateKeywordCount(scenario);  // 추가된 검증
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

        // exit_mechanism 검증
        if (!scenarioData.has("exit_mechanism")) {
            throw new RuntimeException("exit_mechanism이 누락되었습니다");
        }

        String exitMechanism = scenarioData.get("exit_mechanism").getAsString();
        if (!exitMechanism.equals("key") && !exitMechanism.equals("code") && !exitMechanism.equals("logic_unlock")) {
            throw new RuntimeException("잘못된 exit_mechanism: " + exitMechanism + ". 허용값: key, code, logic_unlock");
        }

        // keyword_count 검증
        if (!scenarioData.has("keyword_count")) {
            throw new RuntimeException("keyword_count가 누락되었습니다");
        }
    }

    private void validateObjectInstructions(JsonObject scenario) {
        JsonArray objectInstructions = scenario.getAsJsonArray("object_instructions");
        if (objectInstructions.isEmpty()) {
            throw new RuntimeException("오브젝트 설명이 없습니다");
        }

        // GameManager 검증
        JsonObject firstObject = objectInstructions.get(0).getAsJsonObject();
        if (!firstObject.has("name") || !firstObject.get("name").getAsString().equals("GameManager")) {
            throw new RuntimeException("첫 번째 오브젝트가 GameManager가 아닙니다");
        }

        // ExitDoor 검증
        boolean hasExitDoor = false;
        for (int i = 0; i < objectInstructions.size(); i++) {
            JsonObject obj = objectInstructions.get(i).getAsJsonObject();
            if (obj.has("name") && "ExitDoor".equals(obj.get("name").getAsString())) {
                hasExitDoor = true;
                // ExitDoor는 반드시 interactive_description이 있어야 함
                if (!obj.has("interactive_description")) {
                    throw new RuntimeException("ExitDoor에 interactive_description이 없습니다");
                }
                break;
            }
        }

        if (!hasExitDoor) {
            throw new RuntimeException("ExitDoor가 object_instructions에 없습니다");
        }
    }

    private void validateObjectFields(JsonObject scenario) {
        JsonArray objectInstructions = scenario.getAsJsonArray("object_instructions");


        boolean isFreeModeling = false;
        if (scenario.has("scenario_data")) {
            JsonObject scenarioData = scenario.getAsJsonObject("scenario_data");
            if (scenarioData.has("is_free_modeling")) {
                isFreeModeling = scenarioData.get("is_free_modeling").getAsBoolean();
            }
        }

        for (int i = 0; i < objectInstructions.size(); i++) {
            JsonObject obj = objectInstructions.get(i).getAsJsonObject();
            String name = obj.has("name") ? obj.get("name").getAsString() : "unknown";
            String type = obj.has("type") ? obj.get("type").getAsString() : "";

            // GameManager는 특별 처리
            if ("game_manager".equals(type)) {
                continue;
            }

            // interactive_description 또는 monologue_messages 중 하나는 있어야 함
            boolean hasInteractive = obj.has("interactive_description");
            boolean hasMonologue = obj.has("monologue_messages");

            if (!hasInteractive && !hasMonologue) {
                throw new RuntimeException(String.format(
                        "오브젝트 '%s'에 interactive_description 또는 monologue_messages가 없습니다", name));
            }

            // 둘 다 있으면 안됨
            if (hasInteractive && hasMonologue) {
                log.warn("오브젝트 '{}'에 interactive_description과 monologue_messages가 모두 있습니다. " +
                        "interactive_description만 사용됩니다.", name);
            }

            // monologue_messages 배열 검증
            if (hasMonologue) {
                if (!obj.get("monologue_messages").isJsonArray()) {
                    throw new RuntimeException(String.format(
                            "오브젝트 '%s'의 monologue_messages가 배열이 아닙니다", name));
                }
                JsonArray msgArray = obj.getAsJsonArray("monologue_messages");
                if (msgArray.size() < 15) {
                    log.warn("오브젝트 '{}'의 monologue_messages가 {}개로 15개 미만입니다",
                            name, msgArray.size());
                }
            }

            // existing_object는 id가 있어야 함
            if ("existing_interactive_object".equals(type) && !obj.has("id")) {
                throw new RuntimeException(String.format(
                        "existing_interactive_object '%s'에 id가 없습니다", name));
            }

            // 새 오브젝트는 visual_description이 있어야 함
            if ("interactive_object".equals(type) && !obj.has("visual_description")) {
                throw new RuntimeException(String.format(
                        "새 오브젝트 '%s'에 visual_description이 없습니다", name));
            }
            if ("interactive_object".equals(type)) {
                if (isFreeModeling) {
                    if (!obj.has("simple_visual_description")) {
                        throw new RuntimeException(String.format(
                                "무료 모델링 모드에서 새 오브젝트 '%s'에 simple_visual_description이 없습니다", name));
                    }
                } else {
                    if (!obj.has("visual_description")) {
                        throw new RuntimeException(String.format(
                                "유료 모델링 모드에서 새 오브젝트 '%s'에 visual_description이 없습니다", name));
                    }
                }
            }
        }
    }

    private void validateKeywordCount(@NotNull JsonObject scenario) {
        JsonObject scenarioData = scenario.getAsJsonObject("scenario_data");
        String difficulty = scenarioData.get("difficulty").getAsString();
        JsonObject keywordCount = scenarioData.getAsJsonObject("keyword_count");

        int userCount = keywordCount.get("user").getAsInt();
        int expandedCount = keywordCount.get("expanded").getAsInt();
        int total = keywordCount.get("total").getAsInt();

        // 합계 검증
        if (userCount + expandedCount != total) {
            throw new RuntimeException(String.format(
                    "키워드 수 계산 오류: user(%d) + expanded(%d) != total(%d)",
                    userCount, expandedCount, total));
        }

        // 난이도별 검증
        boolean valid = switch (difficulty.toLowerCase()) {
            case "easy" -> total >= 3 && total <= 5;
            case "normal" -> total >= 6 && total <= 7;
            case "hard" -> total >= 8 && total <= 9;
            default -> false;
        };

        if (!valid) {
            throw new RuntimeException(String.format(
                    "%s 난이도에서 키워드 수가 잘못되었습니다. 생성된: %d개 (user: %d, expanded: %d)",
                    difficulty, total, userCount, expandedCount));
        }

        // 새로 생성된 오브젝트 수 검증
        int newObjectCount = countNewObjects(scenario.getAsJsonArray("object_instructions"));
        if (newObjectCount != total) {
            throw new RuntimeException(String.format(
                    "새 오브젝트 수(%d)가 총 키워드 수(%d)와 일치하지 않습니다",
                    newObjectCount, total));
        }

        log.info("키워드 검증 완료: {} 난이도, 총 {}개 (user: {}, expanded: {})",
                difficulty, total, userCount, expandedCount);
    }

    private int countNewObjects(@NotNull JsonArray objectInstructions) {
        int count = 0;
        for (int i = 0; i < objectInstructions.size(); i++) {
            JsonObject obj = objectInstructions.get(i).getAsJsonObject();
            String type = obj.has("type") ? obj.get("type").getAsString() : "";
            if ("interactive_object".equals(type)) {
                count++;
            }
        }
        return count;
    }

    private void validateObjectDiversity(@NotNull JsonObject scenario) {
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

            // 오브젝트 이름에서 기본 타입 추출
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

    @NotNull
    private String extractBaseName(String objectName) {
        // 일반적인 수식어 제거
        String[] modifiers = {"Crystal", "Modern", "Ancient", "Victorian", "Golden", "Silver",
                "Old", "New", "Large", "Small", "Big", "Tiny", "Dark", "Light", "Bright",
                "Ornate", "Antique", "Vintage", "Royal", "Imperial", "Mystic", "Magic"};

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