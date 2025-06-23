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
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UndertowServer implements Server {
    private static final Logger log = LoggerFactory.getLogger(UndertowServer.class);
    private static final int MAX_CONCURRENT_REQUESTS = 1;
    private static final String HOST = "0.0.0.0";

    private final Undertow server;
    private final QueueManager queueManager;
    private final RoomService roomService;

    /**
     * UndertowServer 생성자
     * 서버와 모든 의존성을 초기화합니다.
     */
    public UndertowServer(int port) {
        // 의존성 초기화
        DependencyContainer dependencies = initializeDependencies();

        // 핸심 서비스 생성
        this.roomService = dependencies.serviceFactory().createRoomService();
        JobResultStore resultStore = new JobResultStore();
        this.queueManager = new RoomRequestQueueManager(roomService, resultStore, MAX_CONCURRENT_REQUESTS);

        // 핸들러 생성
        RequestHandler apiHandler = new ApiHandler(dependencies.gson(), queueManager, resultStore);

        // 서버 빌드
        this.server = buildServer(port, apiHandler, dependencies.authProvider());

        log.info("Undertow 서버가 포트 {}에서 시작 준비 완료", port);
    }

    /**
     * 의존성들을 초기화합니다.
     */
    @NotNull
    private DependencyContainer initializeDependencies() {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        ConfigurationManager configManager = new JsonConfigurationManager();
        ApiKeyProvider apiKeyProvider = new EnvironmentApiKeyProvider();
        AuthProvider authProvider = new EnvironmentAuthProvider();
        ServiceFactory serviceFactory = new ServiceFactoryImpl(apiKeyProvider, configManager);

        return new DependencyContainer(gson, configManager, apiKeyProvider, authProvider, serviceFactory);
    }

    /**
     * 서버를 빌드합니다.
     */
    @NotNull
    private Undertow buildServer(int port, RequestHandler apiHandler, @NotNull AuthProvider authProvider) {
        RoutingHandler routingHandler = createRouting(apiHandler);
        HttpHandler apiKeyProtectedHandler = new ApiKeyAuthFilter(routingHandler, authProvider.getApiKey());

        return Undertow.builder()
                .addHttpListener(port, HOST)
                .setHandler(apiKeyProtectedHandler)
                .build();
    }

    /**
     * 라우팅을 생성합니다.
     */
    private RoutingHandler createRouting(@NotNull RequestHandler handler) {
        return Handlers.routing()
                .get("/", handler::handleRoot)
                .get("/health", handler::handleHealth)
                .get("/queue/status", handler::handleQueueStatus)
                .post("/room/create", handler::handleRoomCreate)
                .get("/room/result", handler::handleRoomResult);
    }

    /**
     * 서버를 시작합니다.
     */
    @Override
    public void start() {
        server.start();
        log.info("서버가 성공적으로 시작되었습니다");
    }

    /**
     * 서버를 중지합니다.
     * 모든 리소스를 안전하게 정리합니다.
     */
    @Override
    public void stop() {
        if (server != null) {
            log.info("서버 종료 시작...");

            shutdownQueueManager();
            shutdownRoomService();
            shutdownServer();

            log.info("서버가 중지되었습니다");
        }
    }

    /**
     * 큐 매니저를 종료합니다.
     */
    private void shutdownQueueManager() {
        if (queueManager != null) {
            queueManager.shutdown();
        }
    }

    /**
     * 룸 서비스를 종료합니다.
     */
    private void shutdownRoomService() {
        if (roomService instanceof AutoCloseable) {
            try {
                ((AutoCloseable) roomService).close();
            } catch (Exception e) {
                log.error("RoomService 종료 중 오류", e);
            }
        }
    }

    /**
     * 서버를 종료합니다.
     */
    private void shutdownServer() {
        server.stop();
    }

    /**
     * 의존성 컨테이너 레코드
     */
    private record DependencyContainer(
            Gson gson,
            ConfigurationManager configManager,
            ApiKeyProvider apiKeyProvider,
            AuthProvider authProvider,
            ServiceFactory serviceFactory
    ) {
    }
}