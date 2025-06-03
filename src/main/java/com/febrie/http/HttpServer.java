
package com.febrie.http;

import com.febrie.handler.UndertowRoomCreateHandler;
import io.undertow.Undertow;
import io.undertow.server.RoutingHandler;
import io.undertow.util.Methods;

public class HttpServer {
    private final Undertow server;

    public HttpServer(int port) {

        // Undertow 라우팅 핸들러 설정
        RoutingHandler routes = new RoutingHandler();
        routes.add(Methods.POST, "/room/create", new UndertowRoomCreateHandler());

        // Undertow 서버 생성
        this.server = Undertow.builder()
                .addHttpListener(port, "0.0.0.0")
                .setHandler(routes)
                .build();
    }

    public void start() {
        server.start();
    }

    public void stop() {
        if (server != null) {
            server.stop();
        }
    }
}