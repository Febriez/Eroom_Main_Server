package com.febrie.eroom.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.InputStreamReader;

@Getter
public class ConfigUtil {

    private static final Logger log = LoggerFactory.getLogger(ConfigUtil.class);

    private JsonObject config;

    public ConfigUtil() {
        loadConfig();
    }

    private void loadConfig() {
        try {
            InputStream inputStream = getClass().getClassLoader().getResourceAsStream("config.json");
            if (inputStream == null) {
                throw new RuntimeException("설정 파일을 찾을 수 없습니다");
            }

            InputStreamReader reader = new InputStreamReader(inputStream);
            config = JsonParser.parseReader(reader).getAsJsonObject();
            log.info("설정 파일이 성공적으로 로드되었습니다");
        } catch (Exception e) {
            log.error("설정 파일 로드 중 오류 발생", e);
            throw new RuntimeException("설정 파일 로드에 실패했습니다", e);
        }
    }

}