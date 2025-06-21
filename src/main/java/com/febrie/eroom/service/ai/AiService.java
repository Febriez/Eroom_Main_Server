package com.febrie.eroom.service.ai;

import com.google.gson.JsonObject;
import java.util.Map;

public interface AiService {
    JsonObject generateScenario(String scenarioPrompt, JsonObject requestData);
    Map<String, String> generateUnifiedScripts(String unifiedScriptsPrompt, JsonObject requestData);
}