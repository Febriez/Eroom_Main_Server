package com.febrie.eroom.config;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ApiKeyConfig {
    private static final String ANTHROPIC_KEY = System.getenv("ANTHROPIC_KEY");
    private static final String MESHY_KEY_1 = System.getenv("MESHY_KEY_1");
    private static final String MESHY_KEY_2 = System.getenv("MESHY_KEY_2");
    private static final String MESHY_KEY_3 = System.getenv("MESHY_KEY_3");

    public ApiKeyConfig() {
        if (ANTHROPIC_KEY == null) {
            log.error("ANTHROPIC_KEY 환경 변수가 설정되지 않았습니다.");
        } else {
            log.info("ANTHROPIC_KEY 환경 변수가 설정되었습니다.");
        }

        if (MESHY_KEY_1 == null && MESHY_KEY_2 == null && MESHY_KEY_3 == null) {
            log.error("MESHY_KEY 환경 변수가 하나도 설정되지 않았습니다.");
        } else {
            log.info("MESHY_KEY 환경 변수가 설정되었습니다.");
        }
    }

    public String getAnthropicKey() {
        return ANTHROPIC_KEY;
    }

    public String getMeshyKey(int index) {
        // 나머지 연산자를 사용하여 어떤 인덱스든 0, 1, 2로 매핑되도록 함
        int mappedIndex = index % 3;

        if (MESHY_KEY_1 != null && mappedIndex == 0) {
            return MESHY_KEY_1;
        } else if (MESHY_KEY_2 != null && mappedIndex == 1) {
            return MESHY_KEY_2;
        } else if (MESHY_KEY_3 != null && mappedIndex == 2) {
            return MESHY_KEY_3;
        }

        // 순환적으로 사용 가능한 첫 번째 키 반환
        if (MESHY_KEY_1 != null) return MESHY_KEY_1;
        if (MESHY_KEY_2 != null) return MESHY_KEY_2;
        if (MESHY_KEY_3 != null) return MESHY_KEY_3;

        log.error("사용 가능한 MESHY_KEY가 없습니다.");
        throw new IllegalStateException("사용 가능한 MESHY_KEY가 없습니다.");
    }
}