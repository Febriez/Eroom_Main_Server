package com.febrie.eroom.config;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

/**
 * 환경 변수 기반 인증 제공자
 * EROOM_PRIVATE_KEY 환경 변수에서 API 키를 읽습니다.
 */
@Slf4j
public class EnvironmentAuthProvider implements AuthProvider {

    // 환경 변수 이름
    private static final String ENV_EROOM_PRIVATE_KEY = "EROOM_PRIVATE_KEY";

    // 로그 메시지
    private static final String LOG_KEY_NOT_SET = "EROOM_PRIVATE_KEY 환경 변수가 설정되지 않았습니다. 보안을 위해 랜덤 키가 생성되었습니다.";
    private static final String LOG_KEY_CHANGES_ON_RESTART = "이 키로 인증해야 API에 접근할 수 있습니다. 서버 재시작 시 키가 변경됩니다.";
    private static final String LOG_KEY_SET = "EROOM_PRIVATE_KEY 환경 변수가 설정되었습니다.";

    @Getter
    private final String apiKey;

    /**
     * EnvironmentAuthProvider 생성자
     * 환경 변수에서 API 키를 읽거나 랜덤 키를 생성합니다.
     */
    public EnvironmentAuthProvider() {
        this.apiKey = initializeApiKey();
    }

    /**
     * API 키를 초기화합니다.
     * 환경 변수가 설정되지 않은 경우 랜덤 키를 생성합니다.
     */
    private String initializeApiKey() {
        String privateKey = System.getenv(ENV_EROOM_PRIVATE_KEY);

        if (isKeyNotSet(privateKey)) {
            return generateRandomKey();
        } else {
            logKeySet();
            return privateKey;
        }
    }

    /**
     * 키가 설정되지 않았는지 확인합니다.
     */
    private boolean isKeyNotSet(String key) {
        return key == null || key.trim().isEmpty();
    }

    /**
     * 랜덤 API 키를 생성합니다.
     */
    private String generateRandomKey() {
        String generatedKey = UUID.randomUUID().toString();
        logKeyNotSet();
        return generatedKey;
    }

    /**
     * 키가 설정되지 않았음을 로깅합니다.
     */
    private void logKeyNotSet() {
        log.warn(LOG_KEY_NOT_SET);
        log.warn(LOG_KEY_CHANGES_ON_RESTART);
    }

    /**
     * 키가 설정되었음을 로깅합니다.
     */
    private void logKeySet() {
        log.info(LOG_KEY_SET);
    }
}