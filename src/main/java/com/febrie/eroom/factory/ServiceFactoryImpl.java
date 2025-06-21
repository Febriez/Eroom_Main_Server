package com.febrie.eroom.factory;

import com.febrie.eroom.config.ApiKeyProvider;
import com.febrie.eroom.config.ConfigurationManager;
import com.febrie.eroom.service.ai.AiService;
import com.febrie.eroom.service.ai.AnthropicAiService;
import com.febrie.eroom.service.mesh.MeshService;
import com.febrie.eroom.service.mesh.MeshyApiService;
import com.febrie.eroom.service.room.RoomService;
import com.febrie.eroom.service.room.RoomServiceImpl;

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

    @Override
    public RoomService createRoomService() {
        return new RoomServiceImpl(createAiService(), createMeshService(), configManager);
    }
}