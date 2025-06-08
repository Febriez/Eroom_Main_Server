package com.febrie.eroom.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.febrie.eroom.config.ApiKeyConfig;
import com.febrie.eroom.util.ConfigUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
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
            client = AnthropicOkHttpClient.builder().apiKey(apiKeyConfig.getAnthropicKey()).build();
        }
        return client;
    }

    @NotNull
    private MessageCreateParams createMessageParams(String systemPrompt, @NotNull JsonObject userContent, String temperatureKey) {
        JsonObject config = configUtil.getConfig();
        JsonObject modelConfig = config.getAsJsonObject("model");
        return MessageCreateParams.builder().maxTokens(modelConfig.get("maxTokens").getAsLong()).addUserMessage(userContent.toString()).model(modelConfig.get("name").getAsString()).temperature(modelConfig.get(temperatureKey).getAsFloat()).system(systemPrompt).build();
    }

    @Nullable
    private String extractResponseText(@NotNull Message response) {
        if (!response.content().isEmpty() && response.content().get(0).text().isPresent())
            return response.content().get(0).text().get().text().replace("```csharp", "").replace("```json", "").replace("```", "");
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
            if (textContent == null) {
                log.error("스크립트 응답이 없습니다");
                return null;
            }
            return textContent;
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
                    log.info("일괄 오브젝트 스크립트 파싱 완료: {} 개의 스크립트", jsonArray.size());
                    return jsonArray.toString();

                } catch (JsonSyntaxException e) {
                    log.error("JSON 파싱 오류: {}\n응답 내용: {}", e.getMessage(), textContent);
                    return null;
                }
            }
            log.error("일괄 오브젝트 스크립트 생성 응답이 유효하지 않습니다");
            return null;
        } catch (Exception e) {
            log.error("일괄 오브젝트 스크립트 생성 중 오류 발생: {}", e.getMessage(), e);
            return null;
        }
    }
}