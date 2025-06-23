package com.febrie.eroom;

import com.febrie.eroom.config.ApplicationConfig;
import com.febrie.eroom.server.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    /**
     * 애플리케이션 진입점입니다.
     * 서버를 초기화하고 시작합니다.
     */
    public static void main(String[] args) {
        try {
            startApplication(args);
        } catch (Exception e) {
            handleStartupError(e);
        }
    }

    /**
     * 애플리케이션을 시작합니다.
     */
    private static void startApplication(String[] args) {
        ApplicationConfig config = new ApplicationConfig(args);
        Server server = config.createServer();

        server.start();
        registerShutdownHook(server);
    }

    /**
     * JVM 종료 시 서버를 안전하게 중지하는 훅을 등록합니다.
     */
    private static void registerShutdownHook(Server server) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("애플리케이션 종료 중...");
            server.stop();
        }));
    }

    /**
     * 시작 중 발생한 오류를 처리합니다.
     */
    private static void handleStartupError(Exception e) {
        log.error("예상치 못한 오류 발생", e);
        System.exit(1);
    }
}