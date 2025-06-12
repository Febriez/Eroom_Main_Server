package com.febrie.eroom.service.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.febrie.eroom.config.ApiKeyProvider;
import com.febrie.eroom.config.ConfigurationManager;
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

public class AnthropicAiService implements AiService {
    private static final Logger log = LoggerFactory.getLogger(AnthropicAiService.class);

    // 개선된 정규식: 코드 블록의 언어 식별자를 더 정확하게 캡처
    private static final Pattern MARKDOWN_SCRIPT_PATTERN = Pattern.compile(
            "```(?:csharp|cs|c#)?\\s*\\n([\\s\\S]*?)```",
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE
    );

    // 스크립트 이름을 찾기 위한 추가 패턴
    private static final Pattern SCRIPT_NAME_PATTERN = Pattern.compile(
            "```(\\w+(?:\\.cs)?)\\s*\\n([\\s\\S]*?)```",
            Pattern.MULTILINE
    );

    private static final String GAME_MANAGER_NAME = "GameManager";

    // C# 클래스 이름 추출을 위한 패턴
    private static final Pattern CLASS_NAME_PATTERN = Pattern.compile(
            "public\\s+(?:partial\\s+)?class\\s+(\\w+)\\s*[:{]",
            Pattern.MULTILINE
    );

    private final ApiKeyProvider apiKeyProvider;
    private final ConfigurationManager configManager;
    private volatile AnthropicClient client;

    public AnthropicAiService(ApiKeyProvider apiKeyProvider, ConfigurationManager configManager) {
        this.apiKeyProvider = apiKeyProvider;
        this.configManager = configManager;
    }

    @Override
    public JsonObject generateScenario(String scenarioPrompt, JsonObject requestData) {
        String theme = extractTheme(requestData);
        log.info("통합 시나리오 생성 시작: theme={}", theme);

        String response = executeAnthropicCall(scenarioPrompt, requestData, "scenarioTemperature");
        return parseJsonResponse(response);
    }

    @Override
    public Map<String, String> generateUnifiedScripts(String unifiedScriptsPrompt, JsonObject requestData) {
        log.info("마크다운 기반 통합 스크립트 생성 시작");

        String response = executeAnthropicCall(unifiedScriptsPrompt, requestData, "scriptTemperature");
        return parseAndEncodeScripts(response);
    }

    private synchronized AnthropicClient getClient() {
        if (client == null) {
            initializeClient();
        }
        return client;
    }

    private void initializeClient() {
        String apiKey = apiKeyProvider.getAnthropicKey();
        validateApiKey(apiKey);

        client = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .build();

        log.info("AnthropicClient 초기화 완료");
    }

    private void validateApiKey(String apiKey) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            terminateWithError("Anthropic API 키가 설정되지 않았습니다.");
        }
    }

    private String executeAnthropicCall(String systemPrompt, JsonObject requestData, String temperatureKey) {
        try {
            MessageCreateParams params = createMessageParams(systemPrompt, requestData, temperatureKey);
            Message response = getClient().messages().create(params);

            String textContent = extractResponseText(response);
            validateResponse(textContent, temperatureKey.replace("Temperature", ""));

            return textContent;
        } catch (Exception e) {
            terminateWithError(String.format("%s 생성 중 오류 발생: %s",
                    temperatureKey.replace("Temperature", ""), e.getMessage()), e);
            return ""; // Never reached
        }
    }

    @NotNull
    private MessageCreateParams createMessageParams(String systemPrompt, @NotNull JsonObject userContent, String temperatureKey) {
        JsonObject modelConfig = configManager.getModelConfig();
        validateModelConfig(modelConfig, temperatureKey);

        return MessageCreateParams.builder()
                .maxTokens(modelConfig.get("maxTokens").getAsLong())
                .addUserMessage(userContent.toString())
                .model(modelConfig.get("name").getAsString())
                .temperature(modelConfig.get(temperatureKey).getAsFloat())
                .system(systemPrompt)
                .build();
    }

    private String extractResponseText(@NotNull Message response) {
        return response.content().stream()
                .findFirst()
                .flatMap(ContentBlock::text)
                .map(textBlock -> textBlock.text().trim())
                .orElse(null);
    }

    @Nullable
    private JsonObject parseJsonResponse(String textContent) {
        try {
            // 먼저 마크다운 코드 블록 안의 JSON을 추출 시도
            String jsonContent = extractJsonFromMarkdown(textContent);
            if (jsonContent == null) {
                // 마크다운 블록이 없으면 전체 텍스트를 JSON으로 파싱
                jsonContent = textContent;
            }

            JsonObject result = JsonParser.parseString(jsonContent).getAsJsonObject();
            log.info("통합 시나리오 생성 완료");
            return result;
        } catch (JsonSyntaxException e) {
            log.error("시나리오 JSON 파싱 실패: {}. 응답: {}",
                    e.getMessage(), truncateForLog(textContent));
            terminateWithError("JSON 파싱 실패");
            return null;
        }
    }

    @Nullable
    private String extractJsonFromMarkdown(String content) {
        // JSON 마크다운 코드 블록 패턴
        Pattern jsonPattern = Pattern.compile(
                "```(?:json)?\\s*\\n([\\s\\S]*?)```",
                Pattern.MULTILINE | Pattern.CASE_INSENSITIVE
        );

        Matcher matcher = jsonPattern.matcher(content);
        if (matcher.find()) {
            String extracted = matcher.group(1).trim();
            log.debug("마크다운 코드 블록에서 JSON 추출됨: {}자", extracted.length());
            return extracted;
        }

        return null;
    }

    @NotNull
    private Map<String, String> parseAndEncodeScripts(String content) {
        Map<String, String> encodedScripts = extractScriptsFromMarkdown(content);

        if (encodedScripts.isEmpty()) {
            log.error("마크다운 컨텐츠에서 스크립트를 찾을 수 없습니다. 응답 내용: {}",
                    truncateForLog(content));
            terminateWithError("파싱된 스크립트가 없습니다.");
        }

        validateGameManagerExists(encodedScripts);
        log.info("마크다운 스크립트 Base64 인코딩 완료: {} 개의 스크립트", encodedScripts.size());
        return encodedScripts;
    }

    @NotNull
    private Map<String, String> extractScriptsFromMarkdown(String content) {
        Map<String, String> encodedScripts = new HashMap<>();

        // 먼저 스크립트 이름이 명시된 코드 블록을 찾음
        Matcher namedMatcher = SCRIPT_NAME_PATTERN.matcher(content);
        while (namedMatcher.find()) {
            String scriptName = normalizeScriptName(namedMatcher.group(1).trim());
            String scriptCode = namedMatcher.group(2).trim();

            if (shouldSkipScript(scriptName)) {
                // C# 언어 표시자인 경우, 코드에서 클래스 이름을 추출 시도
                scriptName = extractClassNameFromCode(scriptCode);
                if (scriptName == null) {
                    log.warn("클래스 이름을 추출할 수 없는 C# 코드 블록을 건너뜁니다.");
                    continue;
                }
            }

            String uniqueName = ensureUniqueName(scriptName, encodedScripts);
            encodeAndStore(uniqueName, scriptCode, encodedScripts);
        }

        // 이름이 없는 C# 코드 블록도 처리
        if (encodedScripts.isEmpty()) {
            log.debug("이름이 명시된 코드 블록을 찾지 못했습니다. 일반 C# 코드 블록을 검색합니다.");
            Matcher genericMatcher = MARKDOWN_SCRIPT_PATTERN.matcher(content);

            while (genericMatcher.find()) {
                String scriptCode = genericMatcher.group(1).trim();
                String scriptName = extractClassNameFromCode(scriptCode);

                if (scriptName != null) {
                    String uniqueName = ensureUniqueName(scriptName, encodedScripts);
                    encodeAndStore(uniqueName, scriptCode, encodedScripts);
                } else {
                    log.warn("클래스 이름을 추출할 수 없는 코드 블록을 발견했습니다.");
                }
            }
        }

        log.debug("총 {} 개의 스크립트를 추출했습니다.", encodedScripts.size());
        return encodedScripts;
    }

    @Nullable
    private String extractClassNameFromCode(String code) {
        Matcher matcher = CLASS_NAME_PATTERN.matcher(code);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    @NotNull
    private String normalizeScriptName(@NotNull String scriptName) {
        // .cs 확장자 제거
        if (scriptName.endsWith(".cs")) {
            return scriptName.substring(0, scriptName.length() - 3);
        }
        return scriptName;
    }

    private boolean shouldSkipScript(@NotNull String scriptName) {
        // C# 언어 표시자들
        return scriptName.equalsIgnoreCase("csharp") ||
                scriptName.equalsIgnoreCase("cs") ||
                scriptName.equalsIgnoreCase("c#");
    }

    private String ensureUniqueName(String scriptName, @NotNull Map<String, String> existingScripts) {
        String uniqueName = scriptName;
        int counter = 1;

        while (existingScripts.containsKey(uniqueName)) {
            uniqueName = scriptName + "_" + counter++;
            log.warn("중복된 스크립트 이름 발견, 변경: {} -> {}", scriptName, uniqueName);
        }

        return uniqueName;
    }

    private void encodeAndStore(String scriptName, String scriptCode, Map<String, String> scripts) {
        if (scriptCode == null || scriptCode.trim().isEmpty()) {
            log.warn("빈 스크립트 코드: {}", scriptName);
            return;
        }

        encodeToBase64(scriptCode).ifPresent(encoded -> {
            scripts.put(scriptName, encoded);
            log.debug("스크립트 파싱 완료: {} (원본: {}자, 인코딩: {}자)",
                    scriptName, scriptCode.length(), encoded.length());
        });
    }

    private Optional<String> encodeToBase64(String content) {
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

    private String extractTheme(@NotNull JsonObject requestData) {
        return requestData.has("theme") ? requestData.get("theme").getAsString() : "unknown";
    }

    @NotNull
    private String truncateForLog(String text) {
        if (text == null) return "null";
        return text.substring(0, Math.min(500, text.length()));
    }

    private void validateModelConfig(@NotNull JsonObject modelConfig, String temperatureKey) {
        if (!modelConfig.has("maxTokens") || !modelConfig.has("name") || !modelConfig.has(temperatureKey)) {
            terminateWithError("필수 모델 설정이 누락되었습니다: " + temperatureKey);
        }
    }

    private void validateResponse(String textContent, String contentType) {
        if (textContent == null || textContent.isEmpty()) {
            terminateWithError(contentType + " 생성 응답이 비어있습니다.");
        }
    }

    private void validateGameManagerExists(@NotNull Map<String, String> scripts) {
        if (!scripts.containsKey(GAME_MANAGER_NAME)) {
            log.warn("GameManager 스크립트가 파싱되지 않았습니다");
        }
    }

    private void terminateWithError(String message) {
        log.error("{} 서버를 종료합니다.", message);
        System.exit(1);
    }

    private void terminateWithError(String message, Exception e) {
        log.error("{} 서버를 종료합니다.", message, e);
        System.exit(1);
    }
}