package com.febrie.eroom.config;

import com.febrie.eroom.exception.NoAvailableKeyException;
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
        return switch (index % 3) {
            case 0 -> MESHY_KEY_1;
            case 1 -> MESHY_KEY_2;
            case 2 -> MESHY_KEY_3;
            default -> throw new NoAvailableKeyException("사용 가능한 MESHY_KEY가 없습니다.");
        };
    }
}