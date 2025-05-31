package com.febrie.config;

// 설정 파일 로더 유틸리티
// - JSON 파일을 사용하여 Properties 파일의 멀티라인 문제 해결
// - 다양한 형식(JSON, Properties, YAML) 지원 가능
// - 외부/내부 설정 파일 로드 지원

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * 다양한 형식의 설정 파일을 로드하는 유틸리티 클래스
 */
@Slf4j
public class ConfigLoader {

    private static final Gson gson = new Gson();

    /**
     * JSON 설정 파일을 로드하여 JsonObject로 반환
     *
     * @param resourcePath 클래스패스 상의 리소스 경로
     * @return 로드된 JsonObject, 실패 시 null
     */
    public static JsonObject loadJsonConfig(String resourcePath) {
        try {
            InputStream input = ConfigLoader.class.getClassLoader().getResourceAsStream(resourcePath);

            if (input == null) {
                log.error("설정 파일을 찾을 수 없습니다: {}", resourcePath);
                return null;
            }

            try (InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {

                return gson.fromJson(reader, JsonObject.class);
            }
        } catch (IOException e) {
            log.error("JSON 설정 파일 로드 중 오류 발생: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.error("JSON 설정 파일 파싱 중 오류 발생: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 외부 경로의 JSON 설정 파일을 로드
     *
     * @param filePath 파일 시스템 경로
     * @return 로드된 JsonObject, 실패 시 null
     */
    public static JsonObject loadExternalJsonConfig(String filePath) {
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) {
            log.error("외부 설정 파일이 존재하지 않습니다: {}", filePath);
            return null;
        }

        try {
            InputStream inputStream = Files.newInputStream(path);
            try (InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
                return gson.fromJson(reader, JsonObject.class);
            }
        } catch (IOException e) {
            log.error("외부 JSON 설정 파일 로드 중 오류 발생: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.error("외부 JSON 설정 파일 파싱 중 오류 발생: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Properties 파일을 로드
     *
     * @param resourcePath 클래스패스 상의 리소스 경로
     * @return 로드된 Properties 객체, 실패 시 빈 Properties
     */
    public static Properties loadProperties(String resourcePath) {
        Properties properties = new Properties();
        try (InputStream input = ConfigLoader.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (input == null) {
                log.error("Properties 파일을 찾을 수 없습니다: {}", resourcePath);
                return properties;
            }
            properties.load(input);
        } catch (IOException e) {
            log.error("Properties 파일 로드 중 오류 발생: {}", e.getMessage());
        }
        return properties;
    }

    /**
     * YAML 파일 로드 지원을 위한 준비 메서드 (실제 구현은 SnakeYAML 등의 라이브러리 필요)
     */
    public static Object loadYamlConfig(String resourcePath) {
        log.error("YAML 설정 로드는 현재 구현되지 않았습니다. SnakeYAML 라이브러리를 추가하세요.");
        return null;
    }
}
