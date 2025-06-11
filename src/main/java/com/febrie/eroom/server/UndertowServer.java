package com.febrie.eroom.server;

import com.febrie.eroom.config.ApiKeyConfig;
import com.febrie.eroom.config.GsonConfig;
import com.febrie.eroom.handler.ApiHandler;
import com.febrie.eroom.service.AnthropicService;
import com.febrie.eroom.service.MeshyService;
import com.febrie.eroom.service.RoomRequestQueueManager;
import com.febrie.eroom.service.impl.RoomServiceImpl;
import com.febrie.eroom.util.ConfigUtil;
import com.google.gson.Gson;
import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.server.RoutingHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UndertowServer {

    private static final Logger log = LoggerFactory.getLogger(UndertowServer.class);
    // 나중에 늘릴 때 이 값만 변경
    private static final int MAX_CONCURRENT_REQUESTS = 1;

    private final Undertow server;
    private final RoomRequestQueueManager queueManager;
    private final RoomServiceImpl roomService;

    public UndertowServer(int port) {
        // 서비스 초기화
        GsonConfig gsonConfig = new GsonConfig();
        Gson gson = gsonConfig.createGson();

        ApiKeyConfig apiKeyConfig = new ApiKeyConfig();
        ConfigUtil configUtil = new ConfigUtil();

        AnthropicService anthropicService = new AnthropicService(apiKeyConfig, configUtil);
        MeshyService meshyService = new MeshyService(apiKeyConfig);

        // RoomService 생성
        roomService = new RoomServiceImpl(anthropicService, meshyService, configUtil);

        // 큐 매니저 생성 (현재는 1개씩만 처리)
        queueManager = new RoomRequestQueueManager(roomService, MAX_CONCURRENT_REQUESTS);

        log.info("최대 동시 처리 요청 수: {}", MAX_CONCURRENT_REQUESTS);

        // API 핸들러 생성
        ApiHandler apiHandler = new ApiHandler(gson, queueManager);

        // 라우팅 설정
        RoutingHandler routingHandler = Handlers.routing()
                .get("/", apiHandler::handleRoot)
                .get("/health", apiHandler::handleHealth)
                .get("/queue/status", apiHandler::handleQueueStatus)  // 큐 상태 조회 엔드포인트 추가
                .post("/room/create", apiHandler::handleRoomCreate);

        // 서버 생성
        server = Undertow.builder()
                .addHttpListener(port, "0.0.0.0")
                .setHandler(routingHandler)
                .build();

        log.info("Undertow 서버가 포트 {}에서 시작 준비 완료", port);
    }

    public void start() {
        server.start();
        log.info("서버가 성공적으로 시작되었습니다");
    }

    public void stop() {
        if (server != null) {
            log.info("서버 종료 시작...");

            // 큐 매니저 종료
            if (queueManager != null) {
                queueManager.shutdown();
            }

            // RoomService 종료
            if (roomService != null) {
                try {
                    roomService.close();
                } catch (Exception e) {
                    log.error("RoomService 종료 중 오류", e);
                }
            }

            // 서버 종료
            server.stop();
            log.info("서버가 중지되었습니다");
        }
    }
}