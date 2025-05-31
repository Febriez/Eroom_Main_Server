package com.febrie.config;

import lombok.Getter;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * AI 관련 설정을 관리하는 설정 클래스
 * 기존에 하드코딩된 AI 설정 값들을 properties 파일에서 로드하여 사용
 */
@Getter
public class AIConfig {

    private static volatile AIConfig instance;
    private final Properties properties;

    // Claude API 모델 설정
    private String modelName;
    private int maxTokens;
    private double scenarioTemperature;
    private double scriptTemperature;

    // 시스템 프롬프트
    private String scenarioSystemPrompt;
    private String scriptSystemPrompt;

    // 기타 설정
    private String roomPrefabUrl;

    private AIConfig() {
        properties = new Properties();
        loadProperties();
        initializeSettings();
    }

    /**
     * properties 파일에서 설정 로드
     */
    private void loadProperties() {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("application.properties")) {
            if (input == null) {
                System.err.println("[ERROR] application.properties 파일을 찾을 수 없습니다.");
                return;
            }
            properties.load(input);
        } catch (IOException e) {
            System.err.println("[ERROR] 설정 파일 로드 중 오류 발생: " + e.getMessage());
        }
    }

    /**
     * 로드된 properties에서 설정 값 초기화
     */
    private void initializeSettings() {
        // Claude API 모델 설정
        modelName = properties.getProperty("ai.model.claude", "claude-sonnet-4-20250514");
        maxTokens = Integer.parseInt(properties.getProperty("ai.model.max-tokens", "8000"));
        scenarioTemperature = Double.parseDouble(properties.getProperty("ai.model.temperature.scenario", "0.9"));
        scriptTemperature = Double.parseDouble(properties.getProperty("ai.model.temperature.script", "0.1"));

        // 시스템 프롬프트 (멀티라인)
        scenarioSystemPrompt = loadMultilineProperty("ai.system-prompt.scenario");
        scriptSystemPrompt = loadMultilineProperty("ai.system-prompt.script");

        // 기타 설정
        roomPrefabUrl = properties.getProperty("ai.room-prefab-url", "");
    }

    /**
     * 여러 줄로 나누어진 프로퍼티를 단일 문자열로 로드합니다.
     * 
     * @param baseKey 프로퍼티의 기본 키
     * @return 모든 줄이 연결된 문자열
     */
    private String loadMultilineProperty(String baseKey) {
        // 줄 수 확인
        String lineCountStr = properties.getProperty(baseKey + ".lines");
        if (lineCountStr == null) {
            // 기존 단일 라인 프로퍼티 시도
            return properties.getProperty(baseKey, "");
        }

        try {
            int lineCount = Integer.parseInt(lineCountStr);
            StringBuilder result = new StringBuilder();

            for (int i = 1; i <= lineCount; i++) {
                String lineKey = baseKey + ".line." + i;
                String line = properties.getProperty(lineKey, "");
                result.append(line);

                if (i < lineCount) {
                    result.append("\n");
                }
            }

            return result.toString();
        } catch (NumberFormatException e) {
            System.err.println("[ERROR] 잘못된 라인 수 형식: " + lineCountStr);
            return properties.getProperty(baseKey, "");
        }
    }

    /**
     * 싱글톤 인스턴스 반환
     */
    public static AIConfig getInstance() {
        if (instance == null) {
            synchronized (AIConfig.class) {
                if (instance == null) {
                    instance = new AIConfig();
                }
            }
        }
        return instance;
    }

    /**
     * 설정 다시 로드
     */
    public void reload() {
        loadProperties();
        initializeSettings();
        System.out.println("[INFO] AI 설정이 다시 로드되었습니다.");
    }
}
