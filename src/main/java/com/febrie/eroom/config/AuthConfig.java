package com.febrie.eroom.config;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

@Getter
@Slf4j
public class AuthConfig {
    private static final String EROOM_PRIVATE_KEY = System.getenv("EROOM_PRIVATE_KEY");
    private final String apiKey;

    public AuthConfig() {
        if (EROOM_PRIVATE_KEY == null || EROOM_PRIVATE_KEY.trim().isEmpty()) {
            apiKey = UUID.randomUUID().toString();
            log.warn("EROOM_PRIVATE_KEY 환경 변수가 설정되지 않았습니다. 보안을 위해 랜덤 키가 생성되었습니다.");
            log.warn("이 키로 인증해야 API에 접근할 수 있습니다. 서버 재시작 시 키가 변경됩니다.");
        } else {
            apiKey = EROOM_PRIVATE_KEY;
            log.info("EROOM_PRIVATE_KEY 환경 변수가 설정되었습니다.");
        }
    }
}
