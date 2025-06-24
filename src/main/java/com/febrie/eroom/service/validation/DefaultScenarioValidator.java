package com.febrie.eroom.service.validation;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

public class DefaultScenarioValidator implements ScenarioValidator {
    private static final Logger log = LoggerFactory.getLogger(DefaultScenarioValidator.class);

    // 필드 이름 상수
    private static final String FIELD_SCENARIO_DATA = "scenario_data";
    private static final String FIELD_OBJECT_INSTRUCTIONS = "object_instructions";
    private static final String FIELD_THEME = "theme";
    private static final String FIELD_DESCRIPTION = "description";
    private static final String FIELD_ESCAPE_CONDITION = "escape_condition";
    private static final String FIELD_PUZZLE_FLOW = "puzzle_flow";
    private static final String FIELD_EXIT_MECHANISM = "exit_mechanism";
    private static final String FIELD_KEYWORD_COUNT = "keyword_count";
    private static final String FIELD_NAME = "name";
    private static final String FIELD_TYPE = "type";
    private static final String FIELD_INTERACTIVE_DESCRIPTION = "interactive_description";
    private static final String FIELD_MONOLOGUE_MESSAGES = "monologue_messages";
    private static final String FIELD_VISUAL_DESCRIPTION = "visual_description";
    private static final String FIELD_SIMPLE_VISUAL_DESCRIPTION = "simple_visual_description";
    private static final String FIELD_ID = "id";
    private static final String FIELD_IS_FREE_MODELING = "is_free_modeling";
    private static final String FIELD_DIFFICULTY = "difficulty";

    // 상수값
    private static final String GAME_MANAGER = "GameManager";
    private static final String EXIT_DOOR = "ExitDoor";
    private static final String TYPE_GAME_MANAGER = "game_manager";
    private static final String TYPE_EXISTING_INTERACTIVE = "existing_interactive_object";
    private static final String TYPE_INTERACTIVE = "interactive_object";

    // Exit mechanism 유효값
    private static final Set<String> VALID_EXIT_MECHANISMS = Set.of("key", "code", "logic_unlock");

    // 난이도별 키워드 개수 범위
    private static final int EASY_MIN = 3;
    private static final int EASY_MAX = 5;
    private static final int NORMAL_MIN = 6;
    private static final int NORMAL_MAX = 7;
    private static final int HARD_MIN = 8;
    private static final int HARD_MAX = 9;

    // 오브젝트 이름 수식어
    private static final String[] OBJECT_MODIFIERS = {
            "Crystal", "Modern", "Ancient", "Victorian", "Golden", "Silver",
            "Old", "New", "Large", "Small", "Big", "Tiny", "Dark", "Light",
            "Bright", "Ornate", "Antique", "Vintage", "Royal", "Imperial",
            "Mystic", "Magic"
    };

    /**
     * 시나리오를 검증합니다.
     */
    @Override
    public void validate(JsonObject scenario) throws RuntimeException {
        validateStructure(scenario);
        validateScenarioData(scenario);
        validateObjectInstructions(scenario);
        validateObjectFields(scenario);
        validateKeywordCount(scenario);
        validateObjectDiversity(scenario);
    }

    /**
     * 시나리오의 기본 구조를 검증합니다.
     */
    private void validateStructure(JsonObject scenario) {
        if (isMissingRequiredFields(scenario, FIELD_SCENARIO_DATA, FIELD_OBJECT_INSTRUCTIONS)) {
            throw new RuntimeException("시나리오 구조가 올바르지 않습니다: scenario_data 또는 object_instructions 누락");
        }
    }

    /**
     * 시나리오 데이터를 검증합니다.
     */
    private void validateScenarioData(@NotNull JsonObject scenario) {
        JsonObject scenarioData = scenario.getAsJsonObject(FIELD_SCENARIO_DATA);

        validateScenarioDataFields(scenarioData);
        validateExitMechanism(scenarioData);
        validateKeywordCountExists(scenarioData);
    }

    /**
     * 시나리오 데이터의 필수 필드들을 검증합니다.
     */
    private void validateScenarioDataFields(JsonObject scenarioData) {
        if (isMissingRequiredFields(scenarioData, FIELD_THEME, FIELD_DESCRIPTION,
                FIELD_ESCAPE_CONDITION, FIELD_PUZZLE_FLOW)) {
            throw new RuntimeException("시나리오 데이터가 불완전합니다");
        }
    }

    /**
     * Exit mechanism을 검증합니다.
     */
    private void validateExitMechanism(@NotNull JsonObject scenarioData) {
        if (!scenarioData.has(FIELD_EXIT_MECHANISM)) {
            throw new RuntimeException("exit_mechanism이 누락되었습니다");
        }

        String exitMechanism = scenarioData.get(FIELD_EXIT_MECHANISM).getAsString();
        if (!VALID_EXIT_MECHANISMS.contains(exitMechanism)) {
            throw new RuntimeException("잘못된 exit_mechanism: " + exitMechanism +
                    ". 허용값: " + String.join(", ", VALID_EXIT_MECHANISMS));
        }
    }

    /**
     * Keyword count 존재를 검증합니다.
     */
    private void validateKeywordCountExists(@NotNull JsonObject scenarioData) {
        if (!scenarioData.has(FIELD_KEYWORD_COUNT)) {
            throw new RuntimeException("keyword_count가 누락되었습니다");
        }
    }

    /**
     * 오브젝트 지시사항들을 검증합니다.
     */
    private void validateObjectInstructions(@NotNull JsonObject scenario) {
        JsonArray objectInstructions = scenario.getAsJsonArray(FIELD_OBJECT_INSTRUCTIONS);

        if (objectInstructions.isEmpty()) {
            throw new RuntimeException("오브젝트 설명이 없습니다");
        }

        validateGameManager(objectInstructions);
        validateExitDoor(objectInstructions);
    }

    /**
     * GameManager 존재를 검증합니다.
     */
    private void validateGameManager(@NotNull JsonArray objectInstructions) {
        JsonObject firstObject = objectInstructions.get(0).getAsJsonObject();
        if (!isGameManager(firstObject)) {
            throw new RuntimeException("첫 번째 오브젝트가 GameManager가 아닙니다");
        }
    }

    /**
     * ExitDoor 존재를 검증합니다.
     */
    private void validateExitDoor(@NotNull JsonArray objectInstructions) {
        boolean hasExitDoor = false;

        for (int i = 0; i < objectInstructions.size(); i++) {
            JsonObject obj = objectInstructions.get(i).getAsJsonObject();
            if (isExitDoor(obj)) {
                hasExitDoor = true;
                validateExitDoorHasInteractiveDescription(obj);
                break;
            }
        }

        if (!hasExitDoor) {
            throw new RuntimeException("ExitDoor가 object_instructions에 없습니다");
        }
    }

    /**
     * ExitDoor에 interactive_description이 있는지 검증합니다.
     */
    private void validateExitDoorHasInteractiveDescription(@NotNull JsonObject exitDoor) {
        if (!exitDoor.has(FIELD_INTERACTIVE_DESCRIPTION)) {
            throw new RuntimeException("ExitDoor에 interactive_description이 없습니다");
        }
    }

    /**
     * 각 오브젝트의 필드들을 검증합니다.
     */
    private void validateObjectFields(@NotNull JsonObject scenario) {
        JsonArray objectInstructions = scenario.getAsJsonArray(FIELD_OBJECT_INSTRUCTIONS);
        boolean isFreeModeling = isFreeModeling(scenario);

        for (int i = 0; i < objectInstructions.size(); i++) {
            JsonObject obj = objectInstructions.get(i).getAsJsonObject();
            validateSingleObjectFields(obj, isFreeModeling);
        }
    }

    /**
     * 단일 오브젝트의 필드들을 검증합니다.
     */
    private void validateSingleObjectFields(JsonObject obj, boolean isFreeModeling) {
        String name = getObjectName(obj);
        String type = getObjectType(obj);

        if (TYPE_GAME_MANAGER.equals(type)) {
            return;
        }

        validateInteractionFields(obj, name);
        validateExistingObjectId(obj, type, name);
        validateNewObjectVisualDescription(obj, type, name, isFreeModeling);
    }

    /**
     * 상호작용 필드들을 검증합니다.
     */
    private void validateInteractionFields(@NotNull JsonObject obj, String name) {
        boolean hasInteractive = obj.has(FIELD_INTERACTIVE_DESCRIPTION);
        boolean hasMonologue = obj.has(FIELD_MONOLOGUE_MESSAGES);

        if (!hasInteractive && !hasMonologue) {
            throw new RuntimeException(String.format(
                    "오브젝트 '%s'에 interactive_description 또는 monologue_messages가 없습니다", name));
        }

        if (hasInteractive && hasMonologue) {
            log.warn("오브젝트 '{}'에 interactive_description과 monologue_messages가 모두 있습니다. " +
                    "interactive_description만 사용됩니다.", name);
        }

        if (hasMonologue) {
            validateMonologueMessages(obj, name);
        }
    }

    /**
     * Monologue 메시지들을 검증합니다.
     */
    private void validateMonologueMessages(@NotNull JsonObject obj, String name) {
        if (!obj.get(FIELD_MONOLOGUE_MESSAGES).isJsonArray()) {
            throw new RuntimeException(String.format(
                    "오브젝트 '%s'의 monologue_messages가 배열이 아닙니다", name));
        }

        JsonArray msgArray = obj.getAsJsonArray(FIELD_MONOLOGUE_MESSAGES);
        if (msgArray.size() == 0) {
            throw new RuntimeException(String.format(
                    "오브젝트 '%s'의 monologue_messages가 비어있습니다", name));
        }
    }

    /**
     * 기존 오브젝트의 ID를 검증합니다.
     */
    private void validateExistingObjectId(JsonObject obj, String type, String name) {
        if (TYPE_EXISTING_INTERACTIVE.equals(type) && !obj.has(FIELD_ID)) {
            throw new RuntimeException(String.format(
                    "existing_interactive_object '%s'에 id가 없습니다", name));
        }
    }

    /**
     * 새 오브젝트의 시각적 설명을 검증합니다.
     */
    private void validateNewObjectVisualDescription(JsonObject obj, String type, String name, boolean isFreeModeling) {
        if (!TYPE_INTERACTIVE.equals(type)) {
            return;
        }

        if (isFreeModeling) {
            if (!obj.has(FIELD_SIMPLE_VISUAL_DESCRIPTION)) {
                throw new RuntimeException(String.format(
                        "무료 모델링 모드에서 새 오브젝트 '%s'에 simple_visual_description이 없습니다", name));
            }
        } else {
            if (!obj.has(FIELD_VISUAL_DESCRIPTION)) {
                throw new RuntimeException(String.format(
                        "유료 모델링 모드에서 새 오브젝트 '%s'에 visual_description이 없습니다", name));
            }
        }
    }

    /**
     * 키워드 수를 검증합니다.
     */
    private void validateKeywordCount(@NotNull JsonObject scenario) {
        JsonObject scenarioData = scenario.getAsJsonObject(FIELD_SCENARIO_DATA);
        String difficulty = scenarioData.get(FIELD_DIFFICULTY).getAsString();
        JsonObject keywordCount = scenarioData.getAsJsonObject(FIELD_KEYWORD_COUNT);

        validateKeywordCountCalculation(keywordCount);
        validateKeywordCountForDifficulty(keywordCount, difficulty);
        validateNewObjectCount(scenario, keywordCount);
    }

    /**
     * 키워드 수 계산을 검증합니다.
     */
    private void validateKeywordCountCalculation(@NotNull JsonObject keywordCount) {
        int userCount = keywordCount.get("user").getAsInt();
        int expandedCount = keywordCount.get("expanded").getAsInt();
        int total = keywordCount.get("total").getAsInt();

        if (userCount + expandedCount != total) {
            throw new RuntimeException(String.format(
                    "키워드 수 계산 오류: user(%d) + expanded(%d) != total(%d)",
                    userCount, expandedCount, total));
        }
    }

    /**
     * 난이도별 키워드 수를 검증합니다.
     */
    private void validateKeywordCountForDifficulty(@NotNull JsonObject keywordCount, String difficulty) {
        int total = keywordCount.get("total").getAsInt();
        int userCount = keywordCount.get("user").getAsInt();
        int expandedCount = keywordCount.get("expanded").getAsInt();

        boolean valid = isDifficultyKeywordCountValid(difficulty, total);

        if (!valid) {
            throw new RuntimeException(String.format(
                    "%s 난이도에서 키워드 수가 잘못되었습니다. 생성된: %d개 (user: %d, expanded: %d)",
                    difficulty, total, userCount, expandedCount));
        }

        log.info("키워드 검증 완료: {} 난이도, 총 {}개 (user: {}, expanded: {})",
                difficulty, total, userCount, expandedCount);
    }

    /**
     * 난이도별 키워드 수가 유효한지 확인합니다.
     */
    @Contract(pure = true)
    private boolean isDifficultyKeywordCountValid(@NotNull String difficulty, int total) {
        return switch (difficulty.toLowerCase()) {
            case "easy" -> total >= EASY_MIN && total <= EASY_MAX;
            case "normal" -> total >= NORMAL_MIN && total <= NORMAL_MAX;
            case "hard" -> total >= HARD_MIN && total <= HARD_MAX;
            default -> false;
        };
    }

    /**
     * 새 오브젝트 수를 검증합니다.
     */
    private void validateNewObjectCount(@NotNull JsonObject scenario, @NotNull JsonObject keywordCount) {
        int newObjectCount = countNewObjects(scenario.getAsJsonArray(FIELD_OBJECT_INSTRUCTIONS));
        int total = keywordCount.get("total").getAsInt();

        if (newObjectCount != total) {
            throw new RuntimeException(String.format(
                    "새 오브젝트 수(%d)가 총 키워드 수(%d)와 일치하지 않습니다",
                    newObjectCount, total));
        }
    }

    /**
     * 새 오브젝트 수를 계산합니다.
     */
    private int countNewObjects(@NotNull JsonArray objectInstructions) {
        int count = 0;
        for (int i = 0; i < objectInstructions.size(); i++) {
            JsonObject obj = objectInstructions.get(i).getAsJsonObject();
            if (isNewInteractiveObject(obj)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 오브젝트 다양성을 검증합니다.
     */
    private void validateObjectDiversity(@NotNull JsonObject scenario) {
        JsonArray objectInstructions = scenario.getAsJsonArray(FIELD_OBJECT_INSTRUCTIONS);
        Set<String> objectBaseNames = new HashSet<>();
        Set<String> duplicateWarnings = new HashSet<>();

        processObjectsForDiversity(objectInstructions, objectBaseNames, duplicateWarnings);
        logDiversityWarnings(duplicateWarnings, objectBaseNames.size());
    }

    /**
     * 다양성 검증을 위해 오브젝트들을 처리합니다.
     */
    private void processObjectsForDiversity(@NotNull JsonArray objectInstructions,
                                            Set<String> objectBaseNames,
                                            Set<String> duplicateWarnings) {
        for (int i = 0; i < objectInstructions.size(); i++) {
            JsonObject obj = objectInstructions.get(i).getAsJsonObject();

            if (shouldSkipForDiversity(obj)) {
                continue;
            }

            String objectName = obj.get(FIELD_NAME).getAsString();
            String baseName = extractBaseName(objectName);

            if (objectBaseNames.contains(baseName)) {
                duplicateWarnings.add(baseName);
            }
            objectBaseNames.add(baseName);
        }
    }

    /**
     * 다양성 검증을 위해 건너뛰어야 하는지 확인합니다.
     */
    private boolean shouldSkipForDiversity(@NotNull JsonObject obj) {
        if (!obj.has(FIELD_NAME)) {
            return true;
        }

        String type = getObjectType(obj);
        return TYPE_GAME_MANAGER.equals(type) || TYPE_EXISTING_INTERACTIVE.equals(type);
    }

    /**
     * 다양성 경고를 로깅합니다.
     */
    private void logDiversityWarnings(@NotNull Set<String> duplicateWarnings, int uniqueTypeCount) {
        if (!duplicateWarnings.isEmpty()) {
            log.warn("유사한 오브젝트 타입이 중복됨: {}. 더 다양한 오브젝트를 생성하는 것을 권장합니다.",
                    String.join(", ", duplicateWarnings));
        }

        log.info("새로 생성된 오브젝트 타입: {} 종류", uniqueTypeCount);
    }

    /**
     * 오브젝트 이름에서 기본 이름을 추출합니다.
     */
    @NotNull
    private String extractBaseName(String objectName) {
        String baseName = objectName;

        baseName = removeModifiers(baseName);
        baseName = removeTrailingNumbers(baseName);

        return baseName;
    }

    /**
     * 수식어를 제거합니다.
     */
    private String removeModifiers(String name) {
        for (String modifier : OBJECT_MODIFIERS) {
            if (name.startsWith(modifier)) {
                return name.substring(modifier.length());
            }
        }
        return name;
    }

    /**
     * 끝의 숫자를 제거합니다.
     */
    @NotNull
    @Contract(pure = true)
    private String removeTrailingNumbers(@NotNull String name) {
        return name.replaceAll("\\d+$", "");
    }

    /**
     * 필수 필드들이 누락되었는지 확인합니다.
     */
    private boolean isMissingRequiredFields(JsonObject obj, @NotNull String... fields) {
        for (String field : fields) {
            if (!obj.has(field)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 오브젝트가 GameManager인지 확인합니다.
     */
    private boolean isGameManager(@NotNull JsonObject obj) {
        return obj.has(FIELD_NAME) && GAME_MANAGER.equals(obj.get(FIELD_NAME).getAsString());
    }

    /**
     * 오브젝트가 ExitDoor인지 확인합니다.
     */
    private boolean isExitDoor(@NotNull JsonObject obj) {
        return obj.has(FIELD_NAME) && EXIT_DOOR.equals(obj.get(FIELD_NAME).getAsString());
    }

    /**
     * 무료 모델링 모드인지 확인합니다.
     */
    private boolean isFreeModeling(@NotNull JsonObject scenario) {
        if (scenario.has(FIELD_SCENARIO_DATA)) {
            JsonObject scenarioData = scenario.getAsJsonObject(FIELD_SCENARIO_DATA);
            if (scenarioData.has(FIELD_IS_FREE_MODELING)) {
                return scenarioData.get(FIELD_IS_FREE_MODELING).getAsBoolean();
            }
        }
        return false;
    }

    /**
     * 오브젝트 이름을 가져옵니다.
     */
    private String getObjectName(@NotNull JsonObject obj) {
        return obj.has(FIELD_NAME) ? obj.get(FIELD_NAME).getAsString() : "unknown";
    }

    /**
     * 오브젝트 타입을 가져옵니다.
     */
    private String getObjectType(@NotNull JsonObject obj) {
        return obj.has(FIELD_TYPE) ? obj.get(FIELD_TYPE).getAsString() : "";
    }

    /**
     * 새로운 인터랙티브 오브젝트인지 확인합니다.
     */
    private boolean isNewInteractiveObject(JsonObject obj) {
        String type = getObjectType(obj);
        return TYPE_INTERACTIVE.equals(type);
    }
}