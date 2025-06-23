package com.febrie.eroom.config;

import com.febrie.eroom.exception.NoAvailableKeyException;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

/**
 * 환경 변수 기반 API 키 제공자
 * Anthropic과 Meshy API 키를 환경 변수에서 읽습니다.
 */
@Slf4j
public class EnvironmentApiKeyProvider implements ApiKeyProvider {

    // 환경 변수 이름
    private static final String ENV_ANTHROPIC_KEY = "ANTHROPIC_KEY";
    private static final String ENV_MESHY_KEY_PREFIX = "MESHY_KEY_";

    // Meshy 키 개수
    private static final int MESHY_KEY_COUNT = 3;

    // 로그 메시지
    private static final String LOG_ANTHROPIC_KEY_NOT_SET = "ANTHROPIC_KEY 환경 변수가 설정되지 않았습니다.";
    private static final String LOG_ANTHROPIC_KEY_SET = "ANTHROPIC_KEY 환경 변수가 설정되었습니다.";
    private static final String LOG_MESHY_KEYS_NOT_SET = "MESHY_KEY 환경 변수가 하나도 설정되지 않았습니다.";
    private static final String LOG_MESHY_KEYS_SET = "MESHY_KEY 환경 변수가 설정되었습니다.";

    // 오류 메시지
    private static final String ERROR_NO_MESHY_KEY = "사용 가능한 MESHY_KEY가 없습니다. Index: ";

    private final String anthropicKey;
    private final String[] meshyKeys;

    /**
     * EnvironmentApiKeyProvider 생성자
     * 환경 변수에서 API 키들을 읽고 검증합니다.
     */
    public EnvironmentApiKeyProvider() {
        this.anthropicKey = System.getenv(ENV_ANTHROPIC_KEY);
        this.meshyKeys = loadMeshyKeys();
        validateKeys();
    }

    /**
     * Meshy API 키들을 로드합니다.
     */
    @NotNull
    private String[] loadMeshyKeys() {
        String[] keys = new String[MESHY_KEY_COUNT];
        for (int i = 0; i < MESHY_KEY_COUNT; i++) {
            keys[i] = System.getenv(ENV_MESHY_KEY_PREFIX + (i + 1));
        }
        return keys;
    }

    /**
     * 키들의 유효성을 검증합니다.
     */
    private void validateKeys() {
        validateAnthropicKey();
        validateMeshyKeys();
    }

    /**
     * Anthropic 키를 검증합니다.
     */
    private void validateAnthropicKey() {
        if (anthropicKey == null) {
            log.error(LOG_ANTHROPIC_KEY_NOT_SET);
        } else {
            log.info(LOG_ANTHROPIC_KEY_SET);
        }
    }

    /**
     * Meshy 키들을 검증합니다.
     */
    private void validateMeshyKeys() {
        if (!hasAnyMeshyKey()) {
            log.error(LOG_MESHY_KEYS_NOT_SET);
        } else {
            log.info(LOG_MESHY_KEYS_SET);
        }
    }

    /**
     * Meshy 키가 하나라도 있는지 확인합니다.
     */
    private boolean hasAnyMeshyKey() {
        for (String key : meshyKeys) {
            if (key != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Anthropic API 키를 반환합니다.
     */
    @Override
    public String getAnthropicKey() {
        return anthropicKey;
    }

    /**
     * 지정된 인덱스의 Meshy API 키를 반환합니다.
     * 라운드 로빈 방식으로 키를 선택합니다.
     */
    @Override
    public String getMeshyKey(int index) {
        int keyIndex = calculateKeyIndex(index);
        String key = meshyKeys[keyIndex];

        if (key == null) {
            throw new NoAvailableKeyException(ERROR_NO_MESHY_KEY + keyIndex);
        }

        return key;
    }

    /**
     * 키 인덱스를 계산합니다.
     */
    private int calculateKeyIndex(int index) {
        return index % meshyKeys.length;
    }
}