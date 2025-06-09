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
        if (!response.content().isEmpty() && response.content().get(0).text().isPresent()) {
            return response.content().get(0).text().get().text()
                    .replace("```csharp", "")
                    .replace("```json", "")
                    .replace("```cs", "")
                    .replace("```", "")
                    .trim();
        }
        return null;
    }

    private String encodeToBase64(String content) {
        if (content == null || content.isEmpty()) {
            log.warn("Base64 인코딩: 입력 내용이 비어있습니다");
            return "";
        }
        return Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
    }

    @Nullable
    public JsonObject generateScenario(@NotNull String scenarioPrompt, @NotNull JsonObject requestData) {
        try {
            log.info("시나리오 생성 시작");

            MessageCreateParams params = createMessageParams(scenarioPrompt, requestData, "scenarioTemperature");
            Message response = getClient().messages().create(params);

            String textContent = extractResponseText(response);
            if (textContent == null || textContent.isEmpty()) {
                log.error("시나리오 생성 응답이 비어있습니다");
                return null;
            }

            try {
                JsonObject result = JsonParser.parseString(textContent).getAsJsonObject();
                log.info("시나리오 생성 완료");
                return result;
            } catch (JsonSyntaxException e) {
                log.error("시나리오 JSON 파싱 실패: {}", e.getMessage());
                log.debug("응답 내용: {}", textContent);
                return null;
            }

        } catch (Exception e) {
            log.error("시나리오 생성 중 오류 발생: {}", e.getMessage(), e);
            return null;
        }
    }

    // GameManager 스크립트 생성 - 원본 텍스트와 Base64 인코딩된 버전 모두 반환하도록 수정
    @Nullable
    public GameManagerResult generateGameManagerScript(@NotNull String gameManagerPrompt, @NotNull JsonObject requestData) {
        try {
            log.info("게임 매니저 스크립트 생성 시작");

            MessageCreateParams params = createMessageParams(gameManagerPrompt, requestData, "scriptTemperature");
            Message response = getClient().messages().create(params);

            String originalScript = extractResponseText(response);
            if (originalScript == null || originalScript.isEmpty()) {
                log.error("게임 매니저 스크립트 응답이 없습니다");
                return null;
            }

            String base64Encoded = encodeToBase64(originalScript);
            if (base64Encoded.isEmpty()) {
                log.error("게임 매니저 스크립트 Base64 인코딩 실패");
                return null;
            }

            log.info("게임 매니저 스크립트 Base64 인코딩 완료: {}자 -> {}자",
                    originalScript.length(), base64Encoded.length());

            // 원본 텍스트와 Base64 인코딩된 버전 모두 반환
            return new GameManagerResult(originalScript, base64Encoded);

        } catch (Exception e) {
            log.error("게임 매니저 스크립트 생성 중 오류 발생", e);
            return null;
        }
    }

    @Nullable
    public Map<String, String> generateBulkObjectScripts(@NotNull String bulkObjectPrompt, @NotNull JsonObject requestData) {
        try {
            log.info("일괄 오브젝트 스크립트 생성 시작");

            MessageCreateParams params = createMessageParams(bulkObjectPrompt, requestData, "scriptTemperature");
            Message response = getClient().messages().create(params);

            String textContent = extractResponseText(response);
            if (textContent == null || textContent.isEmpty()) {
                log.error("일괄 오브젝트 스크립트 응답이 없습니다");
                return null;
            }
            Map<String, String> encodedScripts = parseCustomDelimitedScripts(textContent);
            if (encodedScripts == null || encodedScripts.isEmpty()) {
                log.warn("파싱된 스크립트가 없습니다");
                return null;
            }

            log.info("일괄 오브젝트 스크립트 Base64 인코딩 완료: {} 개의 스크립트", encodedScripts.size());
            return encodedScripts;

        } catch (Exception e) {
            log.error("일괄 오브젝트 스크립트 생성 중 오류 발생: {}", e.getMessage(), e);
            return null;
        }
    }

    @Nullable
    private Map<String, String> parseCustomDelimitedScripts(String delimitedContent) {
        if (delimitedContent == null || delimitedContent.trim().isEmpty()) {
            log.warn("구분자 파싱: 입력 내용이 비어있습니다");
            return null;
        }

        Map<String, String> encodedScripts = new HashMap<>();

        try {
            // "===" 구분자로 각 스크립트 분리
            String[] scriptPairs = delimitedContent.split("===");

            for (String scriptPair : scriptPairs) {
                scriptPair = scriptPair.trim();

                if (scriptPair.isEmpty()) {
                    continue;
                }

                // ":::" 구분자로 이름과 코드 분리
                int separatorIndex = scriptPair.indexOf(":::");
                if (separatorIndex == -1) {
                    log.warn("구분자 ':::'가 없는 스크립트 페어: {}", scriptPair.substring(0, Math.min(100, scriptPair.length())));
                    continue;
                }

                String scriptName = scriptPair.substring(0, separatorIndex).trim();
                String compressedCode = scriptPair.substring(separatorIndex + 3).trim();

                if (scriptName.isEmpty()) {
                    log.warn("스크립트 이름이 비어있습니다");
                    continue;
                }

                if (compressedCode.isEmpty()) {
                    log.warn("스크립트 코드가 비어있습니다: {}", scriptName);
                    continue;
                }

                // 중복 스크립트명 체크
                if (encodedScripts.containsKey(scriptName)) {
                    log.warn("중복된 스크립트 이름: {}", scriptName);
                    scriptName = scriptName + "_" + System.currentTimeMillis();
                }

                String base64Encoded = encodeToBase64(compressedCode);
                if (!base64Encoded.isEmpty()) {
                    encodedScripts.put(scriptName, base64Encoded);
                    log.debug("스크립트 파싱 완료: {} (원본: {}자, 인코딩: {}자)",
                            scriptName, compressedCode.length(), base64Encoded.length());
                } else {
                    log.warn("스크립트 Base64 인코딩 실패: {}", scriptName);
                }
            }

        } catch (Exception e) {
            log.error("구분자 파싱 중 오류 발생: {}", e.getMessage(), e);
            log.debug("파싱 실패한 내용: {}", delimitedContent.substring(0, Math.min(500, delimitedContent.length())));
            return null;
        }

        return encodedScripts.isEmpty() ? null : encodedScripts;
    }

    public record GameManagerResult(String originalScript, String base64Script) {

    }
}