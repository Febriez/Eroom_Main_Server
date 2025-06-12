package com.febrie.eroom.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.InputStreamReader;

public class JsonConfigurationManager implements ConfigurationManager {
    private static final Logger log = LoggerFactory.getLogger(JsonConfigurationManager.class);

    private final JsonObject config;

    public JsonConfigurationManager() {
        this.config = loadConfig();
        validateConfiguration();
    }

    private JsonObject loadConfig() {
        try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream("config.json")) {
            if (inputStream == null) {
                throw new RuntimeException("설정 파일을 찾을 수 없습니다");
            }

            InputStreamReader reader = new InputStreamReader(inputStream);
            JsonObject loadedConfig = JsonParser.parseReader(reader).getAsJsonObject();
            log.info("설정 파일이 성공적으로 로드되었습니다");
            return loadedConfig;
        } catch (Exception e) {
            log.error("설정 파일 로드 중 오류 발생", e);
            throw new RuntimeException("설정 파일 로드에 실패했습니다", e);
        }
    }

    private void validateConfiguration() {
        if (!config.has("prompts") || !config.has("model")) {
            throw new RuntimeException("필수 설정이 누락되었습니다");
        }

        JsonObject prompts = config.getAsJsonObject("prompts");
        if (!prompts.has("scenario") || !prompts.has("unified_scripts")) {
            throw new RuntimeException("필수 프롬프트 설정이 누락되었습니다");
        }

        JsonObject model = config.getAsJsonObject("model");
        if (!model.has("maxTokens") || !model.has("name") ||
                !model.has("scenarioTemperature") || !model.has("scriptTemperature")) {
            throw new RuntimeException("필수 모델 설정이 누락되었습니다");
        }
    }

    @Override
    public JsonObject getConfig() {
        return config;
    }

    @Override
    public JsonObject getModelConfig() {
        return config.getAsJsonObject("model");
    }

    @Override
    public String getPrompt(String type) {
        try {
            return config.getAsJsonObject("prompts").get(type).getAsString();
        } catch (Exception e) {
            throw new RuntimeException("프롬프트 설정을 찾을 수 없습니다: " + type, e);
        }
    }
}