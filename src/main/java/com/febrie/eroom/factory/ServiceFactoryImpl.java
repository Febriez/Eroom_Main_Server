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

import java.util.ArrayList;
import java.util.List;

public class ServiceFactoryImpl implements ServiceFactory {
    private final ApiKeyProvider apiKeyProvider;
    private final ConfigurationManager configManager;

    public ServiceFactoryImpl(ApiKeyProvider apiKeyProvider, ConfigurationManager configManager) {
        this.apiKeyProvider = apiKeyProvider;
        this.configManager = configManager;
    }

    @Override
    public AiService createAiService() {
        return new AnthropicAiService(apiKeyProvider, configManager);
    }

    @Override
    public MeshService createMeshService() {
        return new MeshyApiService(apiKeyProvider);
    }

    public MeshService createLocalModelService() {
        // config.json에서 로컬 서버 목록 읽기
        List<String> serverUrls = new ArrayList<>();

        try {
            JsonObject config = configManager.getConfig();
            if (config.has("localModelServers")) {
                JsonArray servers = config.getAsJsonArray("localModelServers");
                for (int i = 0; i < servers.size(); i++) {
                    serverUrls.add(servers.get(i).getAsString());
                }
            }
        } catch (Exception e) {
            // 기본값 사용
            serverUrls.add("192.168.1.100:8080");
            serverUrls.add("192.168.1.101:8080");
        }

        return new LocalModelService(serverUrls);
    }

    @Override
    public RoomService createRoomService() {
        AiService aiService = createAiService();
        MeshService meshService = createMeshService();
        MeshService localModelService = createLocalModelService();
        return new RoomServiceImpl(aiService, meshService, localModelService, configManager);
    }
}