package com.febrie.eroom.factory;

import com.febrie.eroom.config.ApiKeyProvider;
import com.febrie.eroom.config.ConfigurationManager;
import com.febrie.eroom.service.ai.AiService;
import com.febrie.eroom.service.ai.AnthropicAiService;
import com.febrie.eroom.service.mesh.LocalModelService;
import com.febrie.eroom.service.mesh.MeshService;
import com.febrie.eroom.service.mesh.MeshyApiService;
import com.febrie.eroom.service.room.RoomService;
import com.febrie.eroom.service.room.RoomServiceImpl;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.List;

/**
 * 서비스 팩토리 구현체
 * 애플리케이션에서 사용하는 각종 서비스를 생성합니다.
 */
public class ServiceFactoryImpl implements ServiceFactory {

    // 설정 필드 상수
    private static final String CONFIG_LOCAL_MODEL_SERVERS = "localModelServers";

    // 기본 로컬 서버 주소
    private static final String[] DEFAULT_LOCAL_SERVERS = {
            "192.168.1.100:8080",
            "192.168.1.101:8080"
    };

    private final ApiKeyProvider apiKeyProvider;
    private final ConfigurationManager configManager;

    /**
     * ServiceFactoryImpl 생성자
     */
    public ServiceFactoryImpl(ApiKeyProvider apiKeyProvider, ConfigurationManager configManager) {
        this.apiKeyProvider = apiKeyProvider;
        this.configManager = configManager;
    }

    /**
     * AI 서비스를 생성합니다.
     */
    @Override
    public AiService createAiService() {
        return new AnthropicAiService(apiKeyProvider, configManager);
    }

    /**
     * 메시 서비스를 생성합니다.
     */
    @Override
    public MeshService createMeshService() {
        return new MeshyApiService(apiKeyProvider);
    }

    /**
     * 로컬 모델 서비스를 생성합니다.
     * 설정에서 서버 목록을 읽거나 기본값을 사용합니다.
     */
    public MeshService createLocalModelService() {
        List<String> serverUrls = loadLocalServerUrls();
        return new LocalModelService(serverUrls);
    }

    /**
     * 룸 서비스를 생성합니다.
     * 필요한 모든 의존성을 주입합니다.
     */
    @Override
    public RoomService createRoomService() {
        AiService aiService = createAiService();
        MeshService meshService = createMeshService();
        MeshService localModelService = createLocalModelService();

        return new RoomServiceImpl(aiService, meshService, localModelService, configManager);
    }

    /**
     * 설정에서 로컬 서버 URL 목록을 로드합니다.
     */
    private List<String> loadLocalServerUrls() {
        try {
            return loadServerUrlsFromConfig();
        } catch (Exception e) {
            return useDefaultServerUrls();
        }
    }

    /**
     * 설정 파일에서 서버 URL 목록을 읽습니다.
     */
    @NotNull
    private List<String> loadServerUrlsFromConfig() {
        List<String> serverUrls = new ArrayList<>();
        JsonObject config = configManager.getConfig();

        if (config.has(CONFIG_LOCAL_MODEL_SERVERS)) {
            JsonArray servers = config.getAsJsonArray(CONFIG_LOCAL_MODEL_SERVERS);
            extractServerUrls(servers, serverUrls);
        }

        return serverUrls;
    }

    /**
     * JSON 배열에서 서버 URL들을 추출합니다.
     */
    private void extractServerUrls(@NotNull JsonArray servers, List<String> serverUrls) {
        for (int i = 0; i < servers.size(); i++) {
            serverUrls.add(servers.get(i).getAsString());
        }
    }

    /**
     * 기본 서버 URL 목록을 사용합니다.
     */
    @Unmodifiable
    @NotNull
    @Contract(pure = true)
    private List<String> useDefaultServerUrls() {
        return List.of(DEFAULT_LOCAL_SERVERS);
    }
}