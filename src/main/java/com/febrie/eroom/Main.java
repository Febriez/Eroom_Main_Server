package com.febrie.eroom;

import com.febrie.eroom.server.UndertowServer;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);
    private static final int DEFAULT_PORT = 8080;

    public static void main(String[] args) {
        try {
            int port = parsePort(args);
            startServer(port);
        } catch (Exception e) {
            log.error("예상치 못한 오류 발생", e);
            System.exit(1);
        }
    }

    private static int parsePort(@NotNull String[] args) {
        if (args.length > 0) {
            try {
                return Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                log.warn("유효하지 않은 포트 번호: {}. 기본값 {}을 사용합니다.", args[0], DEFAULT_PORT);
            }
        }
        return DEFAULT_PORT;
    }

    private static void startServer(int port) {
        UndertowServer server = new UndertowServer(port);
        server.start();
        registerShutdownHook(server);
    }

    private static void registerShutdownHook(UndertowServer server) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("애플리케이션 종료 중...");
            server.stop();
        }));
    }
}
