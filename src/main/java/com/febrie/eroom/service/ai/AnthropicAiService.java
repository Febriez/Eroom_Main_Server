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

    // 정규식 패턴
    private static final Pattern ALL_CODE_BLOCKS_PATTERN = Pattern.compile(
            "```(?:[\\w#]+)?\\s*\\n([\\s\\S]*?)```",
            Pattern.MULTILINE
    );
    private static final Pattern CLASS_NAME_PATTERN = Pattern.compile(
            "public\\s+(?:partial\\s+)?class\\s+(\\w+)\\s*[:{]",
            Pattern.MULTILINE
    );
    private static final Pattern JSON_PATTERN = Pattern.compile(
            "```(?:json)?\\s*\\n([\\s\\S]*?)```",
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE
    );

    // 상수
    private static final String GAME_MANAGER_NAME = "GameManager";
    private static final String CLASS_NAME_SUFFIX = "C";
    private static final int LOG_TRUNCATE_LENGTH = 500;

    private final ApiKeyProvider apiKeyProvider;
    private final ConfigurationManager configManager;
    private volatile AnthropicClient client;

    /**
     * AnthropicAiService 생성자
     * Anthropic AI 서비스를 초기화합니다.
     */
    public AnthropicAiService(ApiKeyProvider apiKeyProvider, ConfigurationManager configManager) {
        this.apiKeyProvider = apiKeyProvider;
        this.configManager = configManager;
    }

    /**
     * AI를 통해 시나리오를 생성합니다.
     */
    @Override
    public JsonObject generateScenario(String scenarioPrompt, JsonObject requestData) {
        String theme = extractTheme(requestData);
        log.info("통합 시나리오 생성 시작: theme={}", theme);

        String response = executeAnthropicCall(scenarioPrompt, requestData, "scenarioTemperature");
        return parseJsonResponse(response);
    }

    /**
     * AI를 통해 통합 스크립트를 생성합니다.
     */
    @Override
    public Map<String, String> generateUnifiedScripts(String unifiedScriptsPrompt, JsonObject requestData) {
        log.info("마크다운 기반 통합 스크립트 생성 시작");

        String response = executeAnthropicCall(unifiedScriptsPrompt, requestData, "scriptTemperature");
        return parseAndEncodeScripts(response);
    }

    /**
     * Anthropic 클라이언트를 가져옵니다.
     */
    private synchronized AnthropicClient getClient() {
        if (client == null) {
            initializeClient();
        }
        return client;
    }

    /**
     * Anthropic 클라이언트를 초기화합니다.
     */
    private void initializeClient() {
        String apiKey = apiKeyProvider.getAnthropicKey();
        validateApiKey(apiKey);

        client = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .build();

        log.info("AnthropicClient 초기화 완료");
    }

    /**
     * API 키를 검증합니다.
     */
    private void validateApiKey(String apiKey) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            terminateWithError("Anthropic API 키가 설정되지 않았습니다.");
        }
    }

    /**
     * Anthropic API를 호출합니다.
     */
    private String executeAnthropicCall(String systemPrompt, JsonObject requestData, String temperatureKey) {
        try {
            MessageCreateParams params = createMessageParams(systemPrompt, requestData, temperatureKey);
            Message response = getClient().messages().create(params);

            String textContent = extractResponseText(response);
            validateResponseContent(textContent, temperatureKey);

            return textContent;
        } catch (Exception e) {
            String contentType = temperatureKey.replace("Temperature", "");
            terminateWithError(String.format("%s 생성 중 오류 발생: %s", contentType, e.getMessage()), e);
            return "";
        }
    }

    /**
     * 메시지 파라미터를 생성합니다.
     */
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

    /**
     * 응답에서 텍스트를 추출합니다.
     */
    private String extractResponseText(@NotNull Message response) {
        return response.content().stream()
                .findFirst()
                .flatMap(ContentBlock::text)
                .map(textBlock -> textBlock.text().trim())
                .orElse(null);
    }

    /**
     * JSON 응답을 파싱합니다.
     */
    @Nullable
    private JsonObject parseJsonResponse(String textContent) {
        try {
            String jsonContent = extractJsonFromMarkdown(textContent);
            if (jsonContent == null) {
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

    /**
     * 마크다운에서 JSON을 추출합니다.
     */
    @Nullable
    private String extractJsonFromMarkdown(String content) {
        Matcher matcher = JSON_PATTERN.matcher(content);
        if (matcher.find()) {
            String extracted = matcher.group(1).trim();
            log.debug("마크다운 코드 블록에서 JSON 추출됨: {}자", extracted.length());
            return extracted;
        }
        return null;
    }

    /**
     * 스크립트를 파싱하고 인코딩합니다.
     */
    @NotNull
    private Map<String, String> parseAndEncodeScripts(String content) {
        Map<String, String> encodedScripts = extractScriptsFromMarkdown(content);

        if (encodedScripts.isEmpty()) {
            log.error("마크다운 컨텐츠에서 스크립트를 찾을 수 없습니다. 응답 내용: {}",
                    truncateForLog(content));
            terminateWithError("파싱된 스크립트가 없습니다.");
        }

        log.info("마크다운 스크립트 Base64 인코딩 완료: {} 개의 스크립트", encodedScripts.size());
        return encodedScripts;
    }

    /**
     * 마크다운에서 스크립트를 추출합니다.
     */
    @NotNull
    private Map<String, String> extractScriptsFromMarkdown(String content) {
        Map<String, String> encodedScripts = new HashMap<>();

        extractAllCodeBlocks(content, encodedScripts);
        logExtractionResult(encodedScripts);

        return encodedScripts;
    }

    /**
     * 모든 코드 블록을 추출합니다.
     */
    private void extractAllCodeBlocks(String content, Map<String, String> encodedScripts) {
        Matcher matcher = ALL_CODE_BLOCKS_PATTERN.matcher(content);
        int codeBlockCount = 0;

        while (matcher.find()) {
            codeBlockCount++;
            processCodeBlock(matcher.group(1).trim(), codeBlockCount, encodedScripts);
        }

        log.info("전체 {} 개의 코드 블록 처리 완료", codeBlockCount);
    }

    /**
     * 개별 코드 블록을 처리합니다.
     */
    private void processCodeBlock(@NotNull String scriptCode, int blockNumber, Map<String, String> encodedScripts) {
        if (scriptCode.isEmpty()) {
            log.debug("빈 코드 블록 #{} 건너뜀", blockNumber);
            return;
        }

        String scriptName = extractClassNameFromCode(scriptCode);
        if (scriptName == null) {
            log.warn("코드 블록 #{}에서 클래스 이름을 추출할 수 없습니다. 코드 길이: {}자",
                    blockNumber, scriptCode.length());
            return;
        }

        scriptName = normalizeScriptName(scriptName);
        String uniqueName = ensureUniqueName(scriptName, encodedScripts);
        encodeAndStore(uniqueName, scriptCode, encodedScripts);

        log.debug("코드 블록 #{}: 스크립트 '{}' 추출 성공 ({}자)",
                blockNumber, uniqueName, scriptCode.length());
    }

    /**
     * 코드에서 클래스 이름을 추출합니다.
     */
    @Nullable
    private String extractClassNameFromCode(String code) {
        Matcher matcher = CLASS_NAME_PATTERN.matcher(code);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    /**
     * 스크립트 이름을 정규화합니다.
     */
    @NotNull
    private String normalizeScriptName(@NotNull String scriptName) {
        if (scriptName.endsWith(CLASS_NAME_SUFFIX) && !scriptName.equals(CLASS_NAME_SUFFIX)) {
            return scriptName.substring(0, scriptName.length() - 1);
        }
        return scriptName;
    }

    /**
     * 유니크한 이름을 보장합니다.
     */
    private String ensureUniqueName(String scriptName, @NotNull Map<String, String> existingScripts) {
        String uniqueName = scriptName;
        int counter = 1;

        while (existingScripts.containsKey(uniqueName)) {
            uniqueName = scriptName + "_" + counter++;
            log.warn("중복된 스크립트 이름 발견, 변경: {} -> {}", scriptName, uniqueName);
        }

        return uniqueName;
    }

    /**
     * 스크립트를 인코딩하고 저장합니다.
     */
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

    /**
     * 문자열을 Base64로 인코딩합니다.
     */
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

    /**
     * 추출 결과를 로깅합니다.
     */
    private void logExtractionResult(@NotNull Map<String, String> encodedScripts) {
        log.info("추출된 스크립트 총 {} 개", encodedScripts.size());
        if (!encodedScripts.isEmpty()) {
            log.debug("추출된 스크립트 목록: {}", String.join(", ", encodedScripts.keySet()));
        }
    }

    /**
     * 요청 데이터에서 테마를 추출합니다.
     */
    private String extractTheme(@NotNull JsonObject requestData) {
        return requestData.has("theme") ? requestData.get("theme").getAsString() : "unknown";
    }

    /**
     * 로그를 위해 텍스트를 잘라냅니다.
     */
    @NotNull
    private String truncateForLog(String text) {
        if (text == null) return "null";
        return text.substring(0, Math.min(LOG_TRUNCATE_LENGTH, text.length()));
    }

    /**
     * 모델 설정을 검증합니다.
     */
    private void validateModelConfig(@NotNull JsonObject modelConfig, String temperatureKey) {
        if (!modelConfig.has("maxTokens") || !modelConfig.has("name") || !modelConfig.has(temperatureKey)) {
            terminateWithError("필수 모델 설정이 누락되었습니다: " + temperatureKey);
        }
    }

    /**
     * 응답 내용을 검증합니다.
     */
    private void validateResponseContent(String textContent, String temperatureKey) {
        if (textContent == null || textContent.isEmpty()) {
            String contentType = temperatureKey.replace("Temperature", "");
            terminateWithError(contentType + " 생성 응답이 비어있습니다.");
        }
    }

    /**
     * GameManager 존재를 검증합니다.
     */
    private void validateGameManagerExists(@NotNull Map<String, String> scripts) {
        if (!scripts.containsKey(GAME_MANAGER_NAME)) {
            log.warn("GameManager 스크립트가 파싱되지 않았습니다");
        }
    }

    /**
     * 오류와 함께 프로그램을 종료합니다.
     */
    private void terminateWithError(String message) {
        log.error("{} 서버를 종료합니다.", message);
        System.exit(1);
    }

    /**
     * 예외와 함께 오류 메시지를 로깅하고 프로그램을 종료합니다.
     */
    private void terminateWithError(String message, Exception e) {
        log.error("{} 서버를 종료합니다.", message, e);
        System.exit(1);
    }
}