package com.febrie.eroom.config;

import com.febrie.eroom.exception.NoAvailableKeyException;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class EnvironmentApiKeyProvider implements ApiKeyProvider {
    private static final String ANTHROPIC_KEY = System.getenv("ANTHROPIC_KEY");
    private static final String[] MESHY_KEYS = {
            System.getenv("MESHY_KEY_1"),
            System.getenv("MESHY_KEY_2"),
            System.getenv("MESHY_KEY_3")
    };

    public EnvironmentApiKeyProvider() {
        validateKeys();
    }

    private void validateKeys() {
        if (ANTHROPIC_KEY == null) {
            log.error("ANTHROPIC_KEY 환경 변수가 설정되지 않았습니다.");
        } else {
            log.info("ANTHROPIC_KEY 환경 변수가 설정되었습니다.");
        }

        boolean hasMeshyKey = false;
        for (String key : MESHY_KEYS) {
            if (key != null) {
                hasMeshyKey = true;
                break;
            }
        }

        if (!hasMeshyKey) {
            log.error("MESHY_KEY 환경 변수가 하나도 설정되지 않았습니다.");
        } else {
            log.info("MESHY_KEY 환경 변수가 설정되었습니다.");
        }
    }

    @Override
    public String getAnthropicKey() {
        return ANTHROPIC_KEY;
    }

    @Override
    public String getMeshyKey(int index) {
        int keyIndex = index % MESHY_KEYS.length;
        String key = MESHY_KEYS[keyIndex];

        if (key == null) {
            throw new NoAvailableKeyException("사용 가능한 MESHY_KEY가 없습니다. Index: " + keyIndex);
        }

        return key;
    }
}