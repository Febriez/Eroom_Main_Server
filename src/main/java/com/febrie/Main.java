package com.febrie;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlock;
import com.febrie.config.PromptConfig;
import com.febrie.http.HttpServer;
import com.febrie.util.FirebaseManager;
import com.google.firebase.FirebaseApp;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
        log.info("환경 변수 확인 중...");

        // ANTHROPIC_API_KEY 확인
        String anthropicApiKey = System.getenv("ANTHROPIC_API_KEY");
        if (anthropicApiKey == null || anthropicApiKey.isEmpty()) {
            log.warn("ANTHROPIC_API_KEY 환경 변수가 설정되지 않았습니다. API 호출이 실패할 수 있습니다.");
        } else {
            log.info("ANTHROPIC_API_KEY 환경 변수가 설정되었습니다.");
        }

        // MESHY_API_KEY 확인
        String meshyApiKey = System.getenv("MESHY_API_KEY");
        if (meshyApiKey == null || meshyApiKey.isEmpty()) {
            log.warn("MESHY_API_KEY 환경 변수가 설정되지 않았습니다. 3D 모델 생성이 실패할 수 있습니다.");
        } else {
            log.info("MESHY_API_KEY 환경 변수가 설정되었습니다.");
        }

        // BASE_DIRECTORY 확인
        String baseDirectory = System.getProperty("user.home") + "/Desktop";
        log.info("BASE_DIRECTORY 경로가 설정되었습니다: {}", baseDirectory);
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

    public static JsonObject scenario(String puid, String theme, String[] keywords, String roomUrl) {
        long startTime = System.currentTimeMillis();
        log.info("시나리오 생성 요청 시작 - PUID: {}, 테마: {}", puid, theme);

        PromptConfig config = PromptConfig.getInstance();

        MessageCreateParams params = MessageCreateParams.builder().model(config.getModelName()).maxTokens(config.getMaxTokens()).temperature(config.getScenarioTemperature()).system(config.getScenarioSystemPrompt()).addUserMessage("uid: " + puid + ", Theme: " + theme + ", " + "keywords: [" + String.join(", ", keywords) + "], room_URL: " + roomUrl).build();

        log.info("Claude API 요청 중...");
        long apiStartTime = System.currentTimeMillis();
        Message message = client.messages().create(params);
        log.info("Claude API 응답 수신 - 소요시간: {}ms", (System.currentTimeMillis() - apiStartTime));

        Optional<TextBlock> firstTextBlock = message.content().getFirst().text();
        if (firstTextBlock.isEmpty()) throw new IllegalArgumentException("First text block is empty");
        String response = firstTextBlock.get().text();
        JsonObject result = JsonParser.parseString(response).getAsJsonObject();

        log.info("시나리오 생성 완료 - 총 소요시간: {}ms", (System.currentTimeMillis() - startTime));
        return result;
    }

    public static JsonArray gScripts(String puzzle_data, String roomPrefabUrl) {
        long startTime = System.currentTimeMillis();
        log.info("스크립트 생성 요청 시작");

        PromptConfig config = PromptConfig.getInstance();

        MessageCreateParams params = MessageCreateParams.builder().model(config.getModelName()).maxTokens(config.getMaxTokens()).temperature(config.getScriptTemperature()).system(config.getScriptSystemPrompt()).addUserMessage("puzzle data: " + puzzle_data + ",Room Prefab: " + roomPrefabUrl).build();

        log.info("Claude API 요청 중 (스크립트 생성)...");
        long apiStartTime = System.currentTimeMillis();
        Message message = client.messages().create(params);
        log.info("Claude API 응답 수신 (스크립트 생성) - 소요시간: {}ms", (System.currentTimeMillis() - apiStartTime));
        Optional<TextBlock> firstTextBlock = message.content().getFirst().text();
        if (firstTextBlock.isEmpty()) throw new IllegalArgumentException("First text block is empty");
        JsonArray result = JsonParser.parseString(firstTextBlock.get().text()).getAsJsonArray();
        log.info("스크립트 생성 완료 - 총 소요시간: {}ms", (System.currentTimeMillis() - startTime));
        return result;
    }
}