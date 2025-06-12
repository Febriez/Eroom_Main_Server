package com.febrie.eroom.server;

import com.febrie.eroom.config.*;
import com.febrie.eroom.factory.ServiceFactory;
import com.febrie.eroom.factory.ServiceFactoryImpl;
import com.febrie.eroom.filter.ApiKeyAuthFilter;
import com.febrie.eroom.handler.ApiHandler;
import com.febrie.eroom.handler.RequestHandler;
import com.febrie.eroom.service.JobResultStore;
import com.febrie.eroom.service.queue.QueueManager;
import com.febrie.eroom.service.queue.RoomRequestQueueManager;
import com.febrie.eroom.service.room.RoomService;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import io.undertow.server.RoutingHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UndertowServer implements Server {
    private static final Logger log = LoggerFactory.getLogger(UndertowServer.class);
    private static final int MAX_CONCURRENT_REQUESTS = 1;

    private final Undertow server;
    private final QueueManager queueManager;
    private final RoomService roomService;

    public UndertowServer(int port) {
        // Dependencies initialization
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        ConfigurationManager configManager = new JsonConfigurationManager();
        ApiKeyProvider apiKeyProvider = new EnvironmentApiKeyProvider();
        AuthProvider authProvider = new EnvironmentAuthProvider();

        // Service factory
        ServiceFactory serviceFactory = new ServiceFactoryImpl(apiKeyProvider, configManager);

        // Core services
        roomService = serviceFactory.createRoomService();
        JobResultStore resultStore = new JobResultStore();
        queueManager = new RoomRequestQueueManager(roomService, resultStore, MAX_CONCURRENT_REQUESTS);

        // Handler
        RequestHandler apiHandler = new ApiHandler(gson, queueManager, resultStore);

        // Routing
        RoutingHandler routingHandler = createRouting(apiHandler);
        HttpHandler apiKeyProtectedHandler = new ApiKeyAuthFilter(routingHandler, authProvider.getApiKey());

        // Server creation
        server = Undertow.builder()
                .addHttpListener(port, "0.0.0.0")
                .setHandler(apiKeyProtectedHandler)
                .build();

        log.info("Undertow 서버가 포트 {}에서 시작 준비 완료", port);
    }

    private RoutingHandler createRouting(RequestHandler handler) {
        return Handlers.routing()
                .get("/", handler::handleRoot)
                .get("/health", handler::handleHealth)
                .get("/queue/status", handler::handleQueueStatus)
                .post("/room/create", handler::handleRoomCreate)
                .get("/room/result", handler::handleRoomResult);
    }

    @Override
    public void start() {
        server.start();
        log.info("서버가 성공적으로 시작되었습니다");
    }

    @Override
    public void stop() {
        if (server != null) {
            log.info("서버 종료 시작...");

            if (queueManager != null) {
                queueManager.shutdown();
            }

            if (roomService instanceof AutoCloseable) {
                try {
                    ((AutoCloseable) roomService).close();
                } catch (Exception e) {
                    log.error("RoomService 종료 중 오류", e);
                }
            }

            server.stop();
            log.info("서버가 중지되었습니다");
        }
    }
}