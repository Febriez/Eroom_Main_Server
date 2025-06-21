package com.febrie.eroom.config;

import com.febrie.eroom.server.Server;
import com.febrie.eroom.server.UndertowServer;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ApplicationConfig {
    private static final Logger log = LoggerFactory.getLogger(ApplicationConfig.class);
    private static final int DEFAULT_PORT = 8080;

    private final int port;

    public ApplicationConfig(String[] args) {
        this.port = parsePort(args);
    }

    private int parsePort(@NotNull String[] args) {
        if (args.length > 0) {
            try {
                return Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                log.warn("유효하지 않은 포트 번호: {}. 기본값 {}을 사용합니다.", args[0], DEFAULT_PORT);
            }
        }
        return DEFAULT_PORT;
    }

    public Server createServer() {
        return new UndertowServer(port);
    }
}