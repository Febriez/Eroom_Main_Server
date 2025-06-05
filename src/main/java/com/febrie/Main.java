package com.febrie;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlock;
import com.febrie.config.PromptConfig;
import com.febrie.http.HttpServer;
import com.febrie.manager.firebase.FirebaseManager;
import com.febrie.logging.LogHelper;
import com.febrie.logging.LogLevel;
import com.febrie.logging.LogProcessType;
import com.febrie.logging.LogUtility;
import com.febrie.logging.PrettyLogger;
import com.febrie.log.LogEntry;
import com.febrie.result.ProcessResult;
import com.google.firebase.FirebaseApp;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

public class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    private final static AnthropicClient client = AnthropicOkHttpClient.builder().apiKey(System.getenv("ANTHROPIC_API_KEY")).build();

    private static HttpServer server;
    public static FirebaseApp app;

    static {
        try {
            app = FirebaseManager.getInstance().getFirebaseApp();
        } catch (Exception e) {
            log.warn("Firebase 앱을 초기화하는 중 오류가 발생했습니다: {}", e.getMessage(), e);
        }
    }

    public static void main(String[] args) {
        // 환경 변수 확인
        checkEnvironmentVariables();

        Runtime.getRuntime().addShutdownHook(new Thread(Main::shutdown));
        server = new HttpServer(7568);
        server.start();
    }

    private static void checkEnvironmentVariables() {
        log.info("API 키 확인 중...");

        // ANTHROPIC_API_KEY 확인
        String anthropicApiKey = System.getenv("ANTHROPIC_API_KEY");
        if (anthropicApiKey == null || anthropicApiKey.isEmpty()) {
            log.warn("ANTHROPIC_API_KEY 환경 변수가 설정되지 않았습니다. API 호출이 실패할 수 있습니다.");
        } else {
            log.info("ANTHROPIC_API_KEY 환경 변수가 설정되었습니다.");
        }

        // Meshy API 키 확인 (여러 개 지원)
        int meshyKeyCount = 0;

        // 기본 API 키 확인
        String meshyApiKey = System.getenv("MESHY_API_KEY");
        if (meshyApiKey != null && !meshyApiKey.isEmpty()) {
            meshyKeyCount++;
            log.info("MESHY_API_KEY 환경 변수가 설정되었습니다.");
        }

        // 추가 API 키 확인 (MESHY_API_KEY_2, MESHY_API_KEY_3, ...)
        for (int i = 2; i <= 10; i++) {
            String additionalKey = System.getenv("MESHY_API_KEY_" + i);
            if (additionalKey != null && !additionalKey.isEmpty()) {
                meshyKeyCount++;
                log.info("MESHY_API_KEY_{} 환경 변수가 설정되었습니다.", i);
            }
        }

        if (meshyKeyCount == 0) {
            log.warn("MESHY_API_KEY 환경 변수가 설정되지 않았습니다. 3D 모델 생성이 실패할 수 있습니다.");
        } else {
            log.info("총 {}개의 Meshy API 키가 설정되었습니다. API 키 로테이션이 활성화됩니다.", meshyKeyCount);
        }
    }

    private static void shutdown() {
        log.info("애플리케이션 종료 중...");
        try {
            com.febrie.service.MeshyModelService.getInstance().shutdown();
            server.stop();
        } catch (Exception e) {
            log.error("종료 중 오류 발생: {}", e.getMessage(), e);
        }
        log.info("애플리케이션 종료 완료");
    }

    public static JsonObject scenario(String puid, String theme, String[] keywords) {
        long startTime = System.currentTimeMillis();
        String uuid = "asdfasdfsdaf5"; // TODO: 실제 UUID 가져오기

        // 구조화된 로그 생성
        PrettyLogger.LogEntry logEntry = LogHelper.createLogEntry(uuid, puid, theme);

        StringBuilder logBuilder = new StringBuilder();
        LogHelper.processLogBuilder(logBuilder, LogProcessType.SCENARIO_GENERATION, LogLevel.INFO);
        logBuilder.append("PUID: ").append(puid).append(", 테마: ").append(theme).append("\n");
        logBuilder.append("키워드: ").append(String.join(", ", keywords)).append("\n");

        PromptConfig config = PromptConfig.getInstance();
        String requestContent = "uid: " + uuid + ", Theme: " + theme + ", " + "keywords: [" + String.join(", ", keywords) + "]";

        MessageCreateParams params = MessageCreateParams.builder()
                .model(config.getModelName())
                .maxTokens(config.getMaxTokens())
                .temperature(config.getScenarioTemperature())
                .system(config.getScenarioSystemPrompt())
                .addUserMessage(requestContent)
                .build();

        LogHelper.processLogBuilder(logBuilder, LogProcessType.SCENARIO_API_REQUEST, LogLevel.INFO);

        long apiStartTime = System.currentTimeMillis();
        Message message;
        try {
            message = client.messages().create(params);
            long apiTime = System.currentTimeMillis() - apiStartTime;
            LogHelper.processLogBuilder(logBuilder, LogProcessType.SCENARIO_API_RESPONSE, LogLevel.INFO, 
                    "소요시간: " + apiTime + "ms");
        } catch (Exception e) {
            LogHelper.logFailure(logEntry, logBuilder, e, "시나리오 생성 API 호출", requestContent);
            throw e;
        }

        Optional<TextBlock> firstTextBlock = message.content().getFirst().text();
        if (firstTextBlock.isEmpty()) {
            LogHelper.handleEmptyTextBlock(logEntry, logBuilder);
        }

        JsonObject result = JsonParser.parseString(removeJson(firstTextBlock.get().text())).getAsJsonObject();

        long totalTime = System.currentTimeMillis() - startTime;
        LogHelper.logSuccess(logEntry, logBuilder, totalTime, "시나리오 생성");

        return result;
    }

    // 새로운 통합 스크립트 생성 메서드
    public static JsonObject generateScript(String puzzleData, String roomPrefabUrl, String gameManagerScript,
                                            String targetObjectName, boolean isGameManager) {
        long startTime = System.currentTimeMillis();
        String logPrefix = isGameManager ? "GameManager" : "개별 오브젝트(" + targetObjectName + ")";
        String uuid = "asdfasdfsdaf5"; // TODO: 실제 UUID 가져오기
        String puid = "d9a6a823-0e21-4a88-9f7a-609844279d99"; // TODO: 실제 PUID 가져오기

        // 구조화된 로그 생성
        PrettyLogger.LogEntry logEntry = LogHelper.createLogEntry(uuid, puid, null);

        StringBuilder logBuilder = new StringBuilder();
        LogProcessType processType = isGameManager ? 
                LogProcessType.GAME_MANAGER_GENERATION : 
                LogProcessType.INDIVIDUAL_SCRIPT_GENERATION;

        LogHelper.processLogBuilder(logBuilder, processType, LogLevel.INFO, logPrefix);

        PromptConfig config = PromptConfig.getInstance();
        String requestContent;
        String systemPrompt;

        if (isGameManager) {
            // GameManager 생성용
            requestContent = "All puzzle object data: " + puzzleData + ", Room Prefab: " + roomPrefabUrl;
            systemPrompt = config.getGameManagerSystemPrompt();
            logBuilder.append("===========================================\n");
            logBuilder.append("TYPE: GAMEMANAGER_GENERATION\n");
            logBuilder.append("TIMESTAMP: ").append(LocalDateTime.now().format(
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n");
        } else {
            // 개별 오브젝트 생성용
            requestContent = "Target object: " + targetObjectName +
                    ", All puzzle object data: " + puzzleData +
                    ", GameManager script: " + gameManagerScript;
            systemPrompt = config.getIndividualObjectSystemPrompt();
            logBuilder.append("===========================================\n");
            logBuilder.append("TYPE: OBJECT_SCRIPT_GENERATION\n");
            logBuilder.append("TIMESTAMP: ").append(LocalDateTime.now().format(
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n");
            logBuilder.append("OBJECT: ").append(targetObjectName).append("\n");
        }

        // 입력 정보 로깅
        logBuilder.append("INPUT: ").append(requestContent.length() > 200 ? 
                requestContent.substring(0, 200) + "..." : requestContent).append("\n\n");

        MessageCreateParams params = MessageCreateParams.builder()
                .model(config.getModelName())
                .maxTokens(config.getMaxTokens())
                .temperature(config.getScriptTemperature())
                .system(systemPrompt)
                .addUserMessage(requestContent)
                .build();

        LogHelper.processLogBuilder(logBuilder, LogProcessType.SCENARIO_API_REQUEST, LogLevel.INFO, 
                logPrefix + " 스크립트 생성");

        long apiStartTime = System.currentTimeMillis();
        Message message;
        try {
            message = client.messages().create(params);
            long apiTime = System.currentTimeMillis() - apiStartTime;
            LogHelper.processLogBuilder(logBuilder, LogProcessType.SCENARIO_API_RESPONSE, LogLevel.INFO,
                    logPrefix + " 스크립트 생성 - 소요시간: " + apiTime + "ms");
        } catch (Exception e) {
            LogHelper.logFailure(logEntry, logBuilder, e, 
                    logPrefix + " 스크립트 생성 API 호출", requestContent);
            throw e;
        }

        Optional<TextBlock> firstTextBlock = message.content().getFirst().text();
        if (firstTextBlock.isEmpty()) {
            LogHelper.handleEmptyTextBlock(logEntry, logBuilder);
        }

        String responseText = firstTextBlock.get().text();

        // 응답 로깅 (너무 길면 요약)
        logBuilder.append("OUTPUT: ").append(responseText.length() > 300 ? 
                responseText.substring(0, 300) + "..." : responseText).append("\n");
        logBuilder.append("===========================================\n");

        JsonObject result = JsonParser.parseString(removeJson(responseText)).getAsJsonObject();

        long totalTime = System.currentTimeMillis() - startTime;
        LogHelper.logSuccess(logEntry, logBuilder, totalTime, logPrefix + " 스크립트 생성");

        return result;
    }

    public static JsonObject generateGameManager(String puzzleData, String roomPrefabUrl) {
        return generateScript(puzzleData, roomPrefabUrl, null, null, true);
    }

    public static JsonObject generateIndividualScript(String puzzleData, String gameManagerScript, String targetObjectName) {
        return generateScript(puzzleData, null, gameManagerScript, targetObjectName, false);
    }

    @NotNull
    public static String removeJson(@NotNull String json) {
        if (json.contains("```json")) return json.substring(7, json.lastIndexOf("```"));
        if (json.startsWith("[")) return "{" + json + "}";
        return json;
    }
}