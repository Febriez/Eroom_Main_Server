
package com.febrie.http;

import com.febrie.handler.ModelCallbackHandler;
import com.febrie.handler.UndertowRoomCreateHandler;
import io.undertow.Undertow;
import io.undertow.server.RoutingHandler;
import io.undertow.server.handlers.resource.ClassPathResourceManager;
import io.undertow.util.Methods;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpServer {
    private static final Logger log = LoggerFactory.getLogger(HttpServer.class);
    private final Undertow server;
    private final int port;

    public HttpServer(int port) {
        this.port = port;

        // Undertow 라우팅 핸들러 설정
        RoutingHandler routes = new RoutingHandler()
                .add(Methods.POST, "/room/create", new UndertowRoomCreateHandler())
                .add(Methods.POST, "/callback", new ModelCallbackHandler());

        // Undertow 서버 생성
        this.server = Undertow.builder()
                .addHttpListener(port, "0.0.0.0")
                .setHandler(routes)
                .build();
    }

    public void start() {
        server.start();
        log.info("HTTP 서버가 포트 {}에서 시작되었습니다.", port);
        log.info("모델 콜백을 받으려면 http://localhost:{}/callback을 사용하세요.", port);
        System.out.println("\n=====================================");
        System.out.println("3D 모델 콜백 테스트를 위한 설정 안내");
        System.out.println("-------------------------------------");
        System.out.println("1. 룸 생성 요청에 다음 콜백 URL을 포함하세요:");
        System.out.println("   http://localhost:" + port + "/callback");
        System.out.println("2. 모델 생성이 완료되면 서버 로그에 콜백 내용이 표시됩니다.");
        System.out.println("=====================================\n");
    }

    public void stop() {
        if (server != null) {
            server.stop();
            log.info("HTTP 서버가 중지되었습니다.");
        }
    }
}