package com.febrie.eroom.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.febrie.eroom.config.ApiKeyConfig;
import com.febrie.eroom.util.ConfigUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AnthropicService {

    private static final Logger log = LoggerFactory.getLogger(AnthropicService.class);

    private final ApiKeyConfig apiKeyConfig;
    private final ConfigUtil configUtil;
    private AnthropicClient client;

    public AnthropicService(ApiKeyConfig apiKeyConfig, ConfigUtil configUtil) {
        this.apiKeyConfig = apiKeyConfig;
        this.configUtil = configUtil;
    }

    @NotNull
    private AnthropicClient getClient() {
        if (client == null) {
            client = AnthropicOkHttpClient.builder()
                    .apiKey(apiKeyConfig.getAnthropicKey())
                    .build();
        }
        return client;
    }

    @NotNull
    private MessageCreateParams createMessageParams(String systemPrompt, @NotNull JsonObject userContent, String temperatureKey) {
        JsonObject config = configUtil.getConfig();
        JsonObject modelConfig = config.getAsJsonObject("model");
        return MessageCreateParams.builder()
                .maxTokens(modelConfig.get("maxTokens").getAsLong())
                .addUserMessage(userContent.toString())
                .model(modelConfig.get("name").getAsString())
                .temperature(modelConfig.get(temperatureKey).getAsFloat())
                .system(systemPrompt)
                .build();
    }

    @Nullable
    private String extractResponseText(@NotNull Message response) {
        if (!response.content().isEmpty() && response.content().getFirst().text().isPresent()) {
            return response.content().getFirst().text().get().text();
        }
        return null;
    }

    @Nullable
    public JsonObject generateScenario(@NotNull String scenarioPrompt, @NotNull JsonObject requestData) {
        try {
            log.info("시나리오 생성 시작: {}", requestData);

            MessageCreateParams params = createMessageParams(scenarioPrompt, requestData, "scenarioTemperature");
            Message response = getClient().messages().create(params);

            String textContent = extractResponseText(response);
            if (textContent != null) {
                try {
                    if (textContent.length() > 300) {
                        log.debug("파싱 시도할 JSON 내용(일부): {}...(총 {}자)",
                                textContent.substring(0, 300), textContent.length());
                    } else {
                        log.debug("파싱 시도할 JSON 내용: {}", textContent);
                    }

                    return JsonParser.parseString(textContent).getAsJsonObject();
                } catch (Exception e) {
                    log.error("시나리오 JSON 파싱 실패: {}", e.getMessage());
                    return null;
                }
            }

            log.error("시나리오 생성 응답이 유효하지 않습니다");
            return null;
        } catch (Exception e) {
            log.error("시나리오 생성 중 오류 발생: {}", e.getMessage());
            return null;
        }
    }

    @Nullable
    public String generateGameManagerScript(@NotNull String gameManagerPrompt, @NotNull JsonObject requestData) {
        try {
            log.info("게임 매니저 스크립트 생성 시작: {}", requestData);

            MessageCreateParams params = createMessageParams(gameManagerPrompt, requestData, "scriptTemperature");
            Message response = getClient().messages().create(params);

            String textContent = extractResponseText(response);
            if (textContent != null) {
                try {
                    JsonObject jsonResponse = JsonParser.parseString(textContent).getAsJsonObject();

                    if (jsonResponse.has("data") && jsonResponse.get("data").isJsonArray()) {
                        JsonArray dataArray = jsonResponse.getAsJsonArray("data");
                        for (JsonElement element : dataArray) {
                            if (element.isJsonObject()) {
                                JsonObject dataItem = element.getAsJsonObject();
                                if (dataItem.has("name") && "GameManager".equals(dataItem.get("name").getAsString())) {
                                    if (dataItem.has("value")) {
                                        return dataItem.get("value").getAsString();
                                    }
                                }
                            }
                        }
                    }

                    log.error("스크립트 응답에 GameManager 키가 없습니다: {}", textContent);
                    return null;
                } catch (Exception e) {
                    log.error("게임 매니저 스크립트 파싱 중 오류 발생", e);
                    return null;
                }
            }

            log.error("게임 매니저 스크립트 생성 응답이 유효하지 않습니다");
            return null;
        } catch (Exception e) {
            log.error("게임 매니저 스크립트 생성 중 오류 발생", e);
            return null;
        }
    }

    @Nullable
    public String generateBulkObjectScripts(@NotNull String bulkObjectPrompt, @NotNull JsonObject requestData) {
        try {
            log.info("일괄 오브젝트 스크립트 생성 시작");

            MessageCreateParams params = createMessageParams(bulkObjectPrompt, requestData, "scriptTemperature");
            Message response = getClient().messages().create(params);

            String textContent = extractResponseText(response);
            if (textContent != null) {
                log.debug("응답 내용 전체: {}", textContent);
                try {
                    JsonArray jsonArray = JsonParser.parseString(textContent).getAsJsonArray();
                    JsonObject convertedObject = new JsonObject();

                    for (int i = 0; i < jsonArray.size(); i++) {
                        JsonObject scriptObject = jsonArray.get(i).getAsJsonObject();
                        for (java.util.Map.Entry<String, JsonElement> entry : scriptObject.entrySet()) {
                            convertedObject.add(entry.getKey(), entry.getValue());
                        }
                    }

                    log.info("JSON 배열을 객체로 변환 완료: {} 개의 스크립트", convertedObject.keySet().size());
                    return convertedObject.toString();
                } catch (Exception e) {
                    log.error("일괄 객체 스크립트 파싱 중 오류 발생: {}", e, e);
                    return null;
                }
            }

            log.error("일괄 오브젝트 스크립트 생성 응답이 유효하지 않습니다");
            return null;
        } catch (Exception e) {
            log.error("일괄 오브젝트 스크립트 생성 중 오류 발생: {}", e.getMessage());
            return null;
        }
    }
}
