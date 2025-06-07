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
    public String generateObjectScript(@NotNull String objectPrompt, @NotNull JsonObject requestData) {
        try {
            String objectName = requestData.get("object_name").getAsString();
            log.info("{}의 스크립트 생성 시작", objectName);

            MessageCreateParams params = createMessageParams(objectPrompt, requestData, "scriptTemperature");
            Message response = getClient().messages().create(params);

            String textContent = extractResponseText(response);
            if (textContent != null) {
                try {
                    JsonObject jsonResponse = JsonParser.parseString(textContent).getAsJsonObject();

                    for (String key : jsonResponse.keySet()) {
                        if (key.equals(objectName)) {
                            return jsonResponse.get(key).getAsString();
                        }
                    }

                    log.error("스크립트 응답에 오브젝트 이름 키가 없습니다: {}", textContent);
                } catch (Exception e) {
                    log.error("객체 스크립트 파싱 중 오류 발생", e);
                    return null;
                }
            }

            log.error("객체 스크립트 생성 응답이 유효하지 않습니다");
            return null;
        } catch (Exception e) {
            log.error("객체 스크립트 생성 중 오류 발생", e);
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
                // 디버깅을 위해 응답 내용의 일부 출력
                if (textContent.length() > 500) {
                    log.debug("응답 내용(처음 500자): {}...", textContent.substring(0, 500));
                    log.debug("응답 내용(마지막 500자): {}...", textContent.substring(Math.max(0, textContent.length() - 500)));
                } else {
                    log.debug("응답 내용 전체: {}", textContent);
                }

                try {
                    // 문자열 정제 - 잘못된 이스케이프 시퀀스 등 수정 시도
                    String cleanedText = textContent.replace("\\'", "'").replace("\\\"", "\"");

                    // 응답이 유효한 JSON인지 확인
                    JsonObject jsonResponse = JsonParser.parseString(cleanedText).getAsJsonObject();
                    log.info("일괄 오브젝트 스크립트가 생성됨: {} 개의 스크립트", jsonResponse.keySet().size());
                    return cleanedText;
                } catch (Exception e) {
                    log.error("일괄 객체 스크립트 파싱 중 오류 발생: {}", e.getMessage());
                    // 오류 발생 위치 추정을 위한 로깅
                    if (e.getMessage() != null && e.getMessage().contains("line") && e.getMessage().contains("column")) {
                        String errorMsg = e.getMessage();
                        log.error("JSON 파싱 오류 세부 정보: {}", errorMsg);

                        // 오류 주변 컨텍스트 추출 시도
                        try {
                            int lineIndex = errorMsg.indexOf("line");
                            int lineNumber = Integer.parseInt(errorMsg.substring(lineIndex + 5, errorMsg.indexOf(" ", lineIndex + 5)).trim());
                            int columnIndex = errorMsg.indexOf("column");
                            int columnNumber = Integer.parseInt(errorMsg.substring(columnIndex + 7, errorMsg.indexOf(" ", columnIndex + 7)).trim());

                            String[] lines = textContent.split("\n");
                            if (lineNumber <= lines.length) {
                                String errorLine = lines[lineNumber - 1];
                                log.error("오류 발생 라인 {}: {}", lineNumber, errorLine);
                                if (columnNumber <= errorLine.length()) {
                                    log.error("오류 발생 위치 표시: {}^", "-".repeat(Math.min(columnNumber - 1, 100)));
                                }
                            }
                        } catch (Exception extractionError) {
                            log.error("오류 컨텍스트 추출 실패: {}", extractionError.getMessage());
                        }
                    }
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
