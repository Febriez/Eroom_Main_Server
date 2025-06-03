package com.febrie.service;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 여러 API 키를 번갈아가며 사용하는 로테이션 관리 클래스
 */
@Slf4j
public class ApiKeyRotator {

    // 싱글톤 인스턴스
    private static ApiKeyRotator instance;

    // API 키 목록
    private final List<String> apiKeys = new ArrayList<>();

    // 현재 키 인덱스를 원자적으로 관리 (스레드 안전)
    private final AtomicInteger currentIndex = new AtomicInteger(0);

    /**
     * 싱글톤 인스턴스 얻기
     */
    public static synchronized ApiKeyRotator getInstance() {
        if (instance == null) {
            instance = new ApiKeyRotator();
        }
        return instance;
    }

    /**
     * 생성자 - 환경 변수에서 API 키 로드
     */
    private ApiKeyRotator() {
        // 기본 API 키 로드
        loadApiKey("MESHY_API_KEY");

        // 추가 API 키 로드 (MESHY_API_KEY_2, MESHY_API_KEY_3, ...)
        for (int i = 2; i <= 10; i++) {
            loadApiKey("MESHY_API_KEY_" + i);
        }

        log.info("API 키 로테이터 초기화 완료: {} 개의 API 키 로드됨", apiKeys.size());
    }

    /**
     * 환경 변수에서 API 키 로드
     */
    private void loadApiKey(String envVarName) {
        String apiKey = System.getenv(envVarName);
        if (apiKey != null && !apiKey.isEmpty()) {
            apiKeys.add(apiKey);
            log.info("API 키 로드됨: {}", envVarName);
        }
    }

    /**
     * 다음 API 키 반환
     */
    public String getNextApiKey() {
        if (apiKeys.isEmpty()) {
            log.error("API 키가 설정되지 않았습니다");
            return "";
        }

        // 다음 인덱스 계산 (원형 순환)
        int nextIndex = currentIndex.getAndUpdate(current -> (current + 1) % apiKeys.size());
        String key = apiKeys.get(nextIndex);

        log.debug("API 키 로테이션: 키 #{}({}) 사용", nextIndex + 1, maskApiKey(key));
        return key;
    }

    /**
     * API 키 마스킹 (로그용)
     */
    private String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.length() < 8) {
            return "[잘못된 키 형식]";
        }

        // 처음 4자와 마지막 4자만 표시
        return apiKey.substring(0, 4) + "..." + apiKey.substring(apiKey.length() - 4);
    }

    /**
     * API 키 개수 반환
     */
    public int getKeyCount() {
        return apiKeys.size();
    }

    /**
     * API 키가 하나라도 설정되어 있는지 확인
     */
    public boolean hasValidKeys() {
        return !apiKeys.isEmpty();
    }
}