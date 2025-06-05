package com.febrie.service;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;


@Slf4j
public class ApiKeyRotator {

    private static ApiKeyRotator instance;

    private final List<String> apiKeys = new ArrayList<>();
    private final AtomicInteger currentIndex = new AtomicInteger(0);

    public static synchronized ApiKeyRotator getInstance() {
        if (instance == null) {
            instance = new ApiKeyRotator();
        }
        return instance;
    }

    private ApiKeyRotator() {
        // 기본 API 키 로드
        loadApiKey("MESHY_API_KEY");

        // 추가 API 키 로드 (MESHY_API_KEY_2, MESHY_API_KEY_3, ...)
        for (int i = 2; i <= 10; i++) {
            loadApiKey("MESHY_API_KEY_" + i);
        }

        log.info("API 키 로테이터 초기화 완료: {} 개의 API 키 로드됨", apiKeys.size());
    }

    private void loadApiKey(String envVarName) {
        String apiKey = System.getenv(envVarName);
        if (apiKey != null && !apiKey.isEmpty()) {
            apiKeys.add(apiKey);
            log.info("API 키 로드됨: {}", envVarName);
        }
    }

    public String getNextApiKey() {
        if (apiKeys.isEmpty()) {
            log.error("API 키가 설정되지 않았습니다");
            return "";
        }
        int nextIndex = currentIndex.getAndUpdate(current -> (current + 1) % apiKeys.size());
        return apiKeys.get(nextIndex);
    }

    public int getKeyCount() {
        return apiKeys.size();
    }

    public boolean hasValidKeys() {
        return !apiKeys.isEmpty();
    }
}