package com.febrie.eroom.config;

import com.google.gson.JsonObject;

public interface ConfigurationManager {
    JsonObject getConfig();

    JsonObject getModelConfig();

    String getPrompt(String type);
}