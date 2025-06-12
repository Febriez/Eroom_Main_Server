package com.febrie.eroom;

import com.febrie.eroom.config.ApplicationConfig;
import com.febrie.eroom.server.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        try {
            ApplicationConfig config = new ApplicationConfig(args);
            Server server = config.createServer();
            server.start();

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("애플리케이션 종료 중...");
                server.stop();
            }));
        } catch (Exception e) {
            log.error("예상치 못한 오류 발생", e);
            System.exit(1);
        }
    }
}