package com.febrie.eroom.server;

import com.febrie.eroom.config.ApiKeyConfig;
import com.febrie.eroom.config.GsonConfig;
import com.febrie.eroom.handler.ApiHandler;
import com.febrie.eroom.service.AnthropicService;
import com.febrie.eroom.service.MeshyService;
import com.febrie.eroom.service.RoomService;
import com.febrie.eroom.service.impl.RoomServiceImpl;
import com.febrie.eroom.util.ConfigUtil;
import com.google.gson.Gson;
import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.server.RoutingHandler;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UndertowServer {

    private static final Logger log = LoggerFactory.getLogger(UndertowServer.class);
    private final Undertow server;

    public UndertowServer(int port) {
        ApiHandler apiHandler = createApiHandler();

        // 라우팅 설정
        RoutingHandler routingHandler = Handlers.routing()
                .get("/", apiHandler::handleRoot)
                .get("/health", apiHandler::handleHealth)
                .post("/room/create", apiHandler::handleRoomCreate);

        // 서버 생성
        server = Undertow.builder()
                .addHttpListener(port, "0.0.0.0")
                .setHandler(routingHandler)
                .build();

        log.info("Undertow 서버가 포트 {}에서 시작 준비 완료", port);
    }

    @NotNull
    private ApiHandler createApiHandler() {
        GsonConfig gsonConfig = new GsonConfig();
        Gson gson = gsonConfig.createGson();

        ApiKeyConfig apiKeyConfig = new ApiKeyConfig();
        ConfigUtil configUtil = new ConfigUtil();

        AnthropicService anthropicService = new AnthropicService(apiKeyConfig, configUtil);
        MeshyService meshyService = new MeshyService(apiKeyConfig);

        RoomService roomService = new RoomServiceImpl(anthropicService, meshyService, configUtil);

        return new ApiHandler(gson, roomService);
    }

    public void start() {
        server.start();
        log.info("서버가 성공적으로 시작되었습니다");
    }

    public void stop() {
        if (server != null) {
            server.stop();
            log.info("서버가 중지되었습니다");
        }
    }
}
