package com.febrie.eroom.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
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
    private static final Pattern SCRIPT_PATTERN = Pattern.compile("(?:^|===)\\s*([^:]+):::([\\s\\S]*?)(?====|$)");

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
            String apiKey = apiKeyConfig.getAnthropicKey();
            if (apiKey == null || apiKey.trim().isEmpty()) {
                log.error("Anthropic API 키가 설정되지 않았습니다. 서버를 종료합니다.");
                System.exit(1);
            }

            client = AnthropicOkHttpClient.builder()
                    .apiKey(apiKey)
                    .build();

            log.info("AnthropicClient 초기화 완료");
        }
        return client;
    }

    @NotNull
    private MessageCreateParams createMessageParams(String systemPrompt, @NotNull JsonObject userContent, String temperatureKey) {
        JsonObject config = configUtil.getConfig();
        JsonObject modelConfig = config.getAsJsonObject("model");

        // 설정 검증
        validateModelConfig(modelConfig, temperatureKey);

        return MessageCreateParams.builder()
                .maxTokens(modelConfig.get("maxTokens").getAsLong())
                .addUserMessage(userContent.toString())
                .model(modelConfig.get("name").getAsString())
                .temperature(modelConfig.get(temperatureKey).getAsFloat())
                .system(systemPrompt)
                .build();
    }

    private void validateModelConfig(@NotNull JsonObject modelConfig, String temperatureKey) {
        if (!modelConfig.has("maxTokens") || !modelConfig.has("name") || !modelConfig.has(temperatureKey)) {
            log.error("필수 모델 설정이 누락되었습니다: {}. 서버를 종료합니다.", temperatureKey);
            System.exit(1);
        }
    }

    @Nullable
    private String extractResponseText(@NotNull Message response) {
        if (response.content().isEmpty()) {
            return null;
        }

        return response.content().get(0).text()
                .map(textBlock -> textBlock.text()
                        .replaceAll("```(?:csharp|json|cs)?", "")
                        .trim())
                .orElse(null);
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
            log.error("Base64 인코딩 실패: {}. 서버를 종료합니다.", e.getMessage(), e);
            System.exit(1);
            return Optional.empty();
        }
    }

    @Nullable
    public JsonObject generateScenario(@NotNull String scenarioPrompt, @NotNull JsonObject requestData) {
        try {
            log.info("통합 시나리오 생성 시작: theme={}",
                    requestData.has("theme") ? requestData.get("theme").getAsString() : "unknown");

            MessageCreateParams params = createMessageParams(scenarioPrompt, requestData, "scenarioTemperature");
            Message response = getClient().messages().create(params);

            String textContent = extractResponseText(response);
            if (textContent == null || textContent.isEmpty()) {
                log.error("시나리오 생성 응답이 비어있습니다. 서버를 종료합니다.");
                System.exit(1);
            }

            try {
                JsonObject result = JsonParser.parseString(textContent).getAsJsonObject();
                log.info("통합 시나리오 생성 완료");
                return result;
            } catch (JsonSyntaxException e) {
                log.error("시나리오 JSON 파싱 실패: {}. 응답: {}",
                        e.getMessage(),
                        textContent.substring(0, Math.min(500, textContent.length())));
                log.error("서버를 종료합니다.");
                System.exit(1);
                return null;
            }

        } catch (Exception e) {
            log.error("통합 시나리오 생성 중 치명적 오류 발생: {}. 서버를 종료합니다.", e.getMessage(), e);
            System.exit(1);
            return null;
        }
    }

    @Nullable
    public Map<String, String> generateUnifiedScripts(@NotNull String unifiedScriptsPrompt, @NotNull JsonObject requestData) {
        try {
            log.info("통합 스크립트 생성 시작");

            MessageCreateParams params = createMessageParams(unifiedScriptsPrompt, requestData, "scriptTemperature");
            Message response = getClient().messages().create(params);

            String textContent = extractResponseText(response);
            if (textContent == null || textContent.isEmpty()) {
                log.error("통합 스크립트 응답이 없습니다. 서버를 종료합니다.");
                System.exit(1);
            }

            log.debug("응답 내용 길이: {} 문자", textContent.length());

            Map<String, String> encodedScripts = parseDelimitedScripts(textContent);
            if (encodedScripts == null || encodedScripts.isEmpty()) {
                log.error("파싱된 스크립트가 없습니다. 서버를 종료합니다.");
                System.exit(1);
            }

            log.info("통합 스크립트 Base64 인코딩 완료: {} 개의 스크립트", encodedScripts.size());
            return encodedScripts;

        } catch (Exception e) {
            log.error("통합 스크립트 생성 중 치명적 오류 발생: {}. 서버를 종료합니다.", e.getMessage(), e);
            System.exit(1);
            return null;
        }
    }

    @Nullable
    private Map<String, String> parseDelimitedScripts(String delimitedContent) {
        if (delimitedContent == null || delimitedContent.trim().isEmpty()) {
            log.warn("구분자 파싱: 입력 내용이 비어있습니다");
            return null;
        }

        Map<String, String> encodedScripts = new HashMap<>();

        try {
            String content = delimitedContent.trim();

            // GameManager 스크립트 처리 (첫 번째 스크립트, 구분자 없이 시작)
            int firstSeparatorIndex = content.indexOf("===");

            if (firstSeparatorIndex == -1) {
                // 구분자가 없는 경우 전체 내용을 GameManager로 처리
                String gameManagerCode = content.trim();
                if (!gameManagerCode.isEmpty()) {
                    encodeToBase64(gameManagerCode).ifPresent(encoded -> {
                        encodedScripts.put("GameManager", encoded);
                        log.debug("GameManager 스크립트 파싱 완료 (단일 스크립트): {}자", gameManagerCode.length());
                    });
                }
            } else {
                // GameManager 스크립트 추출 (첫 번째 === 이전의 모든 내용)
                String gameManagerCode = content.substring(0, firstSeparatorIndex).trim();
                if (!gameManagerCode.isEmpty()) {
                    encodeToBase64(gameManagerCode).ifPresent(encoded -> {
                        encodedScripts.put("GameManager", encoded);
                        log.debug("GameManager 스크립트 파싱 완료: {}자", gameManagerCode.length());
                    });
                }

                // 나머지 스크립트들 처리 (=== 구분자 이후)
                String remainingContent = content.substring(firstSeparatorIndex);
                Matcher matcher = SCRIPT_PATTERN.matcher(remainingContent);

                while (matcher.find()) {
                    String scriptName = matcher.group(1).trim();
                    String scriptCode = matcher.group(2).trim();

                    if (scriptName.isEmpty()) {
                        log.warn("스크립트 이름이 비어있습니다");
                        continue;
                    }

                    if (scriptCode.isEmpty()) {
                        log.warn("스크립트 코드가 비어있습니다: {}", scriptName);
                        continue;
                    }

                    // .cs 확장자 제거 (이미 있는 경우)
                    if (scriptName.endsWith(".cs")) {
                        scriptName = scriptName.substring(0, scriptName.length() - 3).replace("===", "");
                    }

                    // 중복 스크립트명 처리
                    String finalScriptName = scriptName;
                    int counter = 1;
                    while (encodedScripts.containsKey(finalScriptName)) {
                        finalScriptName = scriptName + "_" + counter++;
                        log.warn("중복된 스크립트 이름 발견, 변경: {} -> {}", scriptName, finalScriptName);
                    }

                    // Base64 인코딩
                    String finalScriptName1 = finalScriptName;
                    encodeToBase64(scriptCode).ifPresent(encoded -> {
                        encodedScripts.put(finalScriptName1, encoded);
                        log.debug("스크립트 파싱 완료: {} (원본: {}자, 인코딩: {}자)",
                                finalScriptName1, scriptCode.length(), encoded.length());
                    });
                }
            }

            if (encodedScripts.isEmpty()) {
                log.warn("유효한 스크립트를 찾을 수 없습니다");
                return null;
            }

            // GameManager 스크립트가 포함되었는지 확인
            if (!encodedScripts.containsKey("GameManager")) {
                log.warn("GameManager 스크립트가 파싱되지 않았습니다");
            }

        } catch (Exception e) {
            log.error("구분자 파싱 중 치명적 오류 발생: {}. 서버를 종료합니다.", e.getMessage(), e);
            log.debug("파싱 실패한 내용: {}", delimitedContent.substring(0, Math.min(500, delimitedContent.length())));
            System.exit(1);
            return null;
        }

        return encodedScripts;
    }
}