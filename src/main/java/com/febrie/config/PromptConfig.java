package com.febrie.config;

// JSON 기반 설정 시스템
// - Properties 파일 대신 JSON을 사용하여 멀티라인 텍스트와 계층적 구조 처리
// - 줄바꿈 문제 해결: \n 이스케이프로 텍스트 저장
// - 사용법: PromptConfig.getInstance().getXxx()

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

@Getter
public class PromptConfig {
    private static final Logger log = LoggerFactory.getLogger(PromptConfig.class);

    private static volatile PromptConfig instance;

    private String modelName;
    private int maxTokens;
    private double scenarioTemperature;
    private double scriptTemperature;

    private String scenarioSystemPrompt;
    private String scriptSystemPrompt;

    private String meshyApiKey;
    private String roomPrefabUrl;

    private PromptConfig() {
        log.info("프롬프트 설정을 로드합니다.");
        checkEnvironmentVariables();
        loadConfig();
    }

    private void checkEnvironmentVariables() {
        String[] envVars = {
            "MESHY_API_KEY", "AI_MODEL_NAME", "AI_MAX_TOKENS", 
            "SCENARIO_TEMPERATURE", "SCRIPT_TEMPERATURE"
        };

        for (String var : envVars) {
            String value = System.getenv(var);
            if (value != null && !value.isEmpty()) {
                log.info("환경 변수 {}가 설정되어 있습니다.", var);
            } else {
                log.info("환경 변수 {}가 설정되지 않았습니다. 기본값을 사용합니다.", var);
            }
        }
    }

    private void loadConfig() {
        try {
            InputStream input = getClass().getClassLoader().getResourceAsStream("config.json");

            if (input == null) {
                log.error("config.json 파일을 찾을 수 없습니다.");
                loadDefaultSettings();
                return;
            }

            try (InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {

                Gson gson = new Gson();
                JsonObject config = gson.fromJson(reader, JsonObject.class);

                // AI 모델 설정 로드
                JsonObject modelConfig = config.getAsJsonObject("model");
                modelName = modelConfig.get("name").getAsString();
                maxTokens = modelConfig.get("maxTokens").getAsInt();
                scenarioTemperature = modelConfig.get("scenarioTemperature").getAsDouble();
                scriptTemperature = modelConfig.get("scriptTemperature").getAsDouble();

                // 시스템 프롬프트 로드
                scenarioSystemPrompt = config.getAsJsonObject("prompts").get("scenario").getAsString();
                scriptSystemPrompt = config.getAsJsonObject("prompts").get("script").getAsString();

                // 기타 설정
                JsonObject miscConfig = config.getAsJsonObject("misc");

                // API 키 (환경 변수 우선)
                meshyApiKey = System.getenv("MESHY_API_KEY");
                if (meshyApiKey == null || meshyApiKey.isEmpty()) {
                    meshyApiKey = miscConfig.has("meshyApiKey") ?
                            miscConfig.get("meshyApiKey").getAsString() :
                            "";
                }

                log.info("설정 파일 로드 완료");
            } // InputStreamReader 닫기
        } catch (IOException e) {
            log.error("설정 파일 로드 중 오류 발생: {}", e.getMessage());
            loadDefaultSettings();
        } catch (Exception e) {
            log.error("설정 파일 파싱 중 오류 발생: {}", e.getMessage());
            loadDefaultSettings();
        }
    }

    private void loadDefaultSettings() {
        log.warn("기본 설정을 사용합니다.");

        // 기본 모델 설정 (환경 변수 확인)
        modelName = getEnvOrDefault("AI_MODEL_NAME", "claude-sonnet-4-20250514");
        maxTokens = Integer.parseInt(getEnvOrDefault("AI_MAX_TOKENS", "8000"));
        scenarioTemperature = Double.parseDouble(getEnvOrDefault("SCENARIO_TEMPERATURE", "0.9"));
        scriptTemperature = Double.parseDouble(getEnvOrDefault("SCRIPT_TEMPERATURE", "0.1"));

        // 기본 시스템 프롬프트
        scenarioSystemPrompt = "Unity 3D escape room scenario creator. Single room, 1-hour difficulty puzzles.\n**Requirements:**\n- Pure logic implementation only (no particles/animations/sound/effects)\n- Complete puzzles within one room, interconnected objects\n**Allowed features:** Click→inventory, conditional interactions, text hints, object move/rotate/activate, time/combination/sequence puzzles\n**Input:** uid, theme, keywords\n**Output JSON format:**\nremove -> ```json```\nonly below:\n{\n  \"scenario_data\": \"Room theme, puzzle concepts, object interactions, story background\",\n  \"keywords\": [\n    {\"name\": \"ObjectName\", \"value\": \"3D model generation prompt\"}\n  ],\n  \"datas\": [\n    {\"name\": \"ObjectName\", \"value\": \"Unity C# implementation details\"}\n  ]\n}";
        scriptSystemPrompt = "Unity 3D C# script generator. Clean, optimized MonoBehaviour scripts.\n**Requirements:**\n- Pure logic implementation only (no UI/rendering/audio systems)\n- Complete functionality within single script, minimal dependencies\n**Allowed features:** Component caching, event systems, coroutines, collision detection, trigger interactions, state management, data serialization\n**Input format:**\npuzzle data: [JSON array], Room Prefab: [GitHub .txt URL]\n**Output format:**\nremove -> ```json```\nonly below:\n\"description\": [\n  {\n    \"name\": \"ObjectName\",\n    \"des\": \"obj description...\"\n  }\n],\n\"scripts\": [\n  {\n    \"name\": \"ScriptName.cs\",\n    \"value\": \"using UnityEngine;public class ScriptName:MonoBehaviour{void Start(){...}}\"\n  }\n]";

        // 기타 기본 설정
        meshyApiKey = System.getenv("MESHY_API_KEY");
        if (meshyApiKey == null || meshyApiKey.isEmpty()) {
            meshyApiKey = "";
            log.warn("MESHY_API_KEY 환경 변수가 설정되지 않았습니다.");
        }
    }

    private String getEnvOrDefault(String envName, String defaultValue) {
        String value = System.getenv(envName);
        if (value != null && !value.isEmpty()) {
            log.info("환경 변수 {} 값을 사용합니다: {}", envName, value);
            return value;
        }
        return defaultValue;
    }

    public static PromptConfig getInstance() {
        if (instance == null) {
            synchronized (PromptConfig.class) {
                if (instance == null) {
                    instance = new PromptConfig();
                }
            }
        }
        return instance;
    }

    public void reload() {
        loadConfig();
        log.info("설정이 다시 로드되었습니다.");
    }

    public String getMeshyApiKey() {
        String envKey = System.getenv("MESHY_API_KEY");
        return (envKey != null && !envKey.isEmpty()) ? envKey : meshyApiKey;
    }

}