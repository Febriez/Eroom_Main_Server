package com.febrie.eroom.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * JSON 기반 설정 관리자
 * config.json 파일에서 설정을 로드하고 관리합니다.
 */
public class JsonConfigurationManager implements ConfigurationManager {
    private static final Logger log = LoggerFactory.getLogger(JsonConfigurationManager.class);

    // 설정 파일 이름
    private static final String CONFIG_FILE = "config.json";

    // 최상위 설정 키
    private static final String KEY_PROMPTS = "prompts";
    private static final String KEY_MODEL = "model";

    // 프롬프트 키
    private static final String KEY_SCENARIO = "scenario";
    private static final String KEY_UNIFIED_SCRIPTS = "unified_scripts";

    // 모델 설정 키
    private static final String KEY_MAX_TOKENS = "maxTokens";
    private static final String KEY_NAME = "name";
    private static final String KEY_SCENARIO_TEMPERATURE = "scenarioTemperature";
    private static final String KEY_SCRIPT_TEMPERATURE = "scriptTemperature";

    // 오류 메시지
    private static final String ERROR_FILE_NOT_FOUND = "설정 파일을 찾을 수 없습니다";
    private static final String ERROR_FILE_LOAD_FAILED = "설정 파일 로드에 실패했습니다";
    private static final String ERROR_MISSING_REQUIRED_CONFIG = "필수 설정이 누락되었습니다";
    private static final String ERROR_MISSING_PROMPTS = "필수 프롬프트 설정이 누락되었습니다";
    private static final String ERROR_MISSING_MODEL_CONFIG = "필수 모델 설정이 누락되었습니다";
    private static final String ERROR_PROMPT_NOT_FOUND = "프롬프트 설정을 찾을 수 없습니다: ";

    // 로그 메시지
    private static final String LOG_CONFIG_LOADED = "설정 파일이 성공적으로 로드되었습니다";
    private static final String LOG_CONFIG_LOAD_ERROR = "설정 파일 로드 중 오류 발생";

    private final JsonObject config;

    /**
     * JsonConfigurationManager 생성자
     * 설정 파일을 로드하고 검증합니다.
     */
    public JsonConfigurationManager() {
        this.config = loadConfig();
        validateConfiguration();
    }

    /**
     * 설정 파일을 로드합니다.
     */
    private JsonObject loadConfig() {
        try (InputStream inputStream = getConfigInputStream()) {
            return parseConfigFile(inputStream);
        } catch (Exception e) {
            log.error(LOG_CONFIG_LOAD_ERROR, e);
            throw new RuntimeException(ERROR_FILE_LOAD_FAILED, e);
        }
    }

    /**
     * 설정 파일 입력 스트림을 가져옵니다.
     */
    @NotNull
    private InputStream getConfigInputStream() {
        InputStream inputStream = getClass().getClassLoader().getResourceAsStream(CONFIG_FILE);
        if (inputStream == null) {
            throw new RuntimeException(ERROR_FILE_NOT_FOUND);
        }
        return inputStream;
    }

    /**
     * 설정 파일을 파싱합니다.
     */
    private JsonObject parseConfigFile(InputStream inputStream) {
        InputStreamReader reader = new InputStreamReader(inputStream);
        JsonObject loadedConfig = JsonParser.parseReader(reader).getAsJsonObject();
        log.info(LOG_CONFIG_LOADED);
        return loadedConfig;
    }

    /**
     * 설정의 유효성을 검증합니다.
     */
    private void validateConfiguration() {
        validateTopLevelConfig();
        validatePromptConfig();
        validateModelConfig();
    }

    /**
     * 최상위 설정을 검증합니다.
     */
    private void validateTopLevelConfig() {
        if (isMissingRequiredKeys(config, KEY_PROMPTS, KEY_MODEL)) {
            throw new RuntimeException(ERROR_MISSING_REQUIRED_CONFIG);
        }
    }

    /**
     * 프롬프트 설정을 검증합니다.
     */
    private void validatePromptConfig() {
        JsonObject prompts = config.getAsJsonObject(KEY_PROMPTS);
        if (isMissingRequiredKeys(prompts, KEY_SCENARIO, KEY_UNIFIED_SCRIPTS)) {
            throw new RuntimeException(ERROR_MISSING_PROMPTS);
        }
    }

    /**
     * 모델 설정을 검증합니다.
     */
    private void validateModelConfig() {
        JsonObject model = config.getAsJsonObject(KEY_MODEL);
        if (isMissingRequiredKeys(model, KEY_MAX_TOKENS, KEY_NAME,
                KEY_SCENARIO_TEMPERATURE, KEY_SCRIPT_TEMPERATURE)) {
            throw new RuntimeException(ERROR_MISSING_MODEL_CONFIG);
        }
    }

    /**
     * 필수 키들이 누락되었는지 확인합니다.
     */
    private boolean isMissingRequiredKeys(JsonObject obj, @NotNull String... keys) {
        for (String key : keys) {
            if (!obj.has(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 전체 설정을 반환합니다.
     */
    @Override
    public JsonObject getConfig() {
        return config;
    }

    /**
     * 모델 설정을 반환합니다.
     */
    @Override
    public JsonObject getModelConfig() {
        return config.getAsJsonObject(KEY_MODEL);
    }

    /**
     * 특정 타입의 프롬프트를 반환합니다.
     */
    @Override
    public String getPrompt(String type) {
        try {
            return config.getAsJsonObject(KEY_PROMPTS).get(type).getAsString();
        } catch (Exception e) {
            throw new RuntimeException(ERROR_PROMPT_NOT_FOUND + type, e);
        }
    }
}