package com.febrie.eroom.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.febrie.eroom.config.ApiKeyConfig;
import com.febrie.eroom.util.ConfigUtil;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AnthropicService {

    private static final Logger log = LoggerFactory.getLogger(AnthropicService.class);
    private static final Pattern MARKDOWN_SCRIPT_PATTERN = Pattern.compile("```(\\S+)\\s*\n" +
            "([\\s\\S]*?)```", Pattern.MULTILINE);
    private static final String GAME_MANAGER_NAME = "GameManager";

    private final ApiKeyConfig apiKeyConfig;
    private final ConfigUtil configUtil;
    private volatile AnthropicClient client;

    public AnthropicService(ApiKeyConfig apiKeyConfig, ConfigUtil configUtil) {
        this.apiKeyConfig = apiKeyConfig;
        this.configUtil = configUtil;
    }


    @NotNull
    private synchronized AnthropicClient getClient() {
        if (client == null) {
            initializeClient();
        }
        return client;
    }

    private void initializeClient() {
        String apiKey = apiKeyConfig.getAnthropicKey();
        validateApiKey(apiKey);

        client = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .build();

        log.info("AnthropicClient 초기화 완료");
    }

    private void validateApiKey(@Nullable String apiKey) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            terminateWithError("Anthropic API 키가 설정되지 않았습니다.");
        }
    }

    @Nullable
    public JsonObject generateScenario(@NotNull String scenarioPrompt, @NotNull JsonObject requestData) {
        String theme = extractTheme(requestData);
        log.info("통합 시나리오 생성 시작: theme={}", theme);

        String response = executeAnthropicCall(scenarioPrompt, requestData, "scenarioTemperature");
        return parseJsonResponse(response);
    }

    @Nullable
    public Map<String, String> generateUnifiedScripts(@NotNull String unifiedScriptsPrompt, @NotNull JsonObject requestData) {
        log.info("마크다운 기반 통합 스크립트 생성 시작");

        String response = executeAnthropicCall(unifiedScriptsPrompt, requestData, "scriptTemperature");
        return parseAndEncodeScripts(response);
    }


    @NotNull
    private String executeAnthropicCall(@NotNull String systemPrompt, @NotNull JsonObject requestData, @NotNull String temperatureKey) {
        try {
            MessageCreateParams params = createMessageParams(systemPrompt, requestData, temperatureKey);
            Message response = getClient().messages().create(params);

            String textContent = extractResponseText(response);
            validateResponse(textContent, temperatureKey.replace("Temperature", ""));

            assert textContent != null;
            return textContent;
        } catch (Exception e) {
            terminateWithError(String.format("%s 생성 중 오류 발생: %s",
                    temperatureKey.replace("Temperature", ""), e.getMessage()), e);
            return ""; // Never reached
        }
    }

    @NotNull
    private MessageCreateParams createMessageParams(@NotNull String systemPrompt, @NotNull JsonObject userContent, @NotNull String temperatureKey) {
        JsonObject modelConfig = getModelConfig();
        validateModelConfig(modelConfig, temperatureKey);

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
        return response.content().stream()
                .findFirst()
                .flatMap(ContentBlock::text)
                .map(textBlock -> textBlock.text().trim())
                .orElse(null);
    }

    @Nullable
    private JsonObject parseJsonResponse(@NotNull String textContent) {
        try {
            JsonObject result = JsonParser.parseString(textContent).getAsJsonObject();
            log.info("통합 {} 생성 완료", "시나리오");
            return result;
        } catch (JsonSyntaxException e) {
            log.error("{} JSON 파싱 실패: {}. 응답: {}",
                    "시나리오", e.getMessage(), truncateForLog(textContent));
            terminateWithError("JSON 파싱 실패");
            return null;
        }
    }


    @NotNull
    private Map<String, String> parseAndEncodeScripts(@NotNull String content) {
        Map<String, String> encodedScripts = extractScriptsFromMarkdown(content);

        if (encodedScripts.isEmpty()) {
            terminateWithError("파싱된 스크립트가 없습니다.");
        }

        validateGameManagerExists(encodedScripts);
        log.info("마크다운 스크립트 Base64 인코딩 완료: {} 개의 스크립트", encodedScripts.size());
        return encodedScripts;
    }

    @NotNull
    private Map<String, String> extractScriptsFromMarkdown(@NotNull String content) {
        Map<String, String> encodedScripts = new HashMap<>();
        Matcher matcher = MARKDOWN_SCRIPT_PATTERN.matcher(content);

        while (matcher.find()) {
            String scriptName = normalizeScriptName(matcher.group(1).trim());
            String scriptCode = matcher.group(2).trim();

            if (shouldSkipScript(scriptName)) {
                continue;
            }

            String uniqueName = ensureUniqueName(scriptName, encodedScripts);
            encodeAndStore(uniqueName, scriptCode, encodedScripts);
        }

        return encodedScripts;
    }

    @NotNull
    private String normalizeScriptName(@NotNull String scriptName) {
        if (scriptName.endsWith(".cs")) {
            return scriptName.substring(0, scriptName.length() - 3);
        }
        return scriptName;
    }

    private boolean shouldSkipScript(@NotNull String scriptName) {
        return scriptName.equalsIgnoreCase("csharp") ||
                scriptName.equalsIgnoreCase("cs") ||
                scriptName.equalsIgnoreCase("c#");
    }

    @NotNull
    private String ensureUniqueName(@NotNull String scriptName, @NotNull Map<String, String> existingScripts) {
        String uniqueName = scriptName;
        int counter = 1;

        while (existingScripts.containsKey(uniqueName)) {
            uniqueName = scriptName + "_" + counter++;
            log.warn("중복된 스크립트 이름 발견, 변경: {} -> {}", scriptName, uniqueName);
        }

        return uniqueName;
    }

    private void encodeAndStore(@NotNull String scriptName, @NotNull String scriptCode, @NotNull Map<String, String> scripts) {
        encodeToBase64(scriptCode).ifPresent(encoded -> {
            scripts.put(scriptName, encoded);
            log.debug("스크립트 파싱 완료: {} (원본: {}자, 인코딩: {}자)",
                    scriptName, scriptCode.length(), encoded.length());
        });
    }

    @NotNull
    private Optional<String> encodeToBase64(@Nullable String content) {
        if (content == null || content.isEmpty()) {
            log.warn("Base64 인코딩: 입력 내용이 비어있습니다");
            return Optional.empty();
        }

        try {
            String encoded = Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
            return Optional.of(encoded);
        } catch (Exception e) {
            terminateWithError("Base64 인코딩 실패: " + e.getMessage(), e);
            return Optional.empty();
        }
    }

    @NotNull
    private JsonObject getModelConfig() {
        return configUtil.getConfig().getAsJsonObject("model");
    }

    @NotNull
    private String extractTheme(@NotNull JsonObject requestData) {
        return requestData.has("theme") ? requestData.get("theme").getAsString() : "unknown";
    }

    @NotNull
    private String truncateForLog(@NotNull String text) {
        return text.substring(0, Math.min(500, text.length()));
    }

    private void validateModelConfig(@NotNull JsonObject modelConfig, @NotNull String temperatureKey) {
        if (!modelConfig.has("maxTokens") || !modelConfig.has("name") || !modelConfig.has(temperatureKey)) {
            terminateWithError("필수 모델 설정이 누락되었습니다: " + temperatureKey);
        }
    }

    private void validateResponse(@Nullable String textContent, @NotNull String contentType) {
        if (textContent == null || textContent.isEmpty()) {
            terminateWithError(contentType + " 생성 응답이 비어있습니다.");
        }
    }

    private void validateGameManagerExists(@NotNull Map<String, String> scripts) {
        if (!scripts.containsKey(GAME_MANAGER_NAME)) {
            log.warn("GameManager 스크립트가 파싱되지 않았습니다");
        }
    }


    private void terminateWithError(@NotNull String message) {
        log.error("{} 서버를 종료합니다.", message);
        System.exit(1);
    }

    private void terminateWithError(@NotNull String message, @NotNull Exception e) {
        log.error("{} 서버를 종료합니다.", message, e);
        System.exit(1);
    }
}