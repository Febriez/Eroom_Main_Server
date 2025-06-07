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
                throw new RuntimeException("Config file not found");
            }

            InputStreamReader reader = new InputStreamReader(inputStream);
            config = JsonParser.parseReader(reader).getAsJsonObject();
            log.info("Loaded configuration successfully");
        } catch (Exception e) {
            log.error("Error loading configuration", e);
            throw new RuntimeException("Failed to load configuration", e);
        }
    }

}
