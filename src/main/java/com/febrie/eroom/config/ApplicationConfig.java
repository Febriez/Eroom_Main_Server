package com.febrie.eroom.config;

import com.febrie.eroom.server.Server;
import com.febrie.eroom.server.UndertowServer;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 애플리케이션 설정 클래스
 * 명령줄 인자를 파싱하고 서버를 생성합니다.
 */
@Getter
public class ApplicationConfig {
    private static final Logger log = LoggerFactory.getLogger(ApplicationConfig.class);

    // 기본 포트 번호
    private static final int DEFAULT_PORT = 8080;

    // 로그 메시지
    private static final String LOG_INVALID_PORT = "유효하지 않은 포트 번호: {}. 기본값 {}을 사용합니다.";

    /**
     * -- GETTER --
     *  설정된 포트 번호를 반환합니다.
     *  주로 테스트 목적으로 사용됩니다.
     */
    private final int port;

    /**
     * ApplicationConfig 생성자
     * 명령줄 인자에서 포트 번호를 파싱합니다.
     */
    public ApplicationConfig(String[] args) {
        this.port = parsePort(args);
    }

    /**
     * 명령줄 인자에서 포트 번호를 파싱합니다.
     * 유효하지 않은 경우 기본값을 사용합니다.
     */
    private int parsePort(@NotNull String[] args) {
        if (args.length == 0) {
            return DEFAULT_PORT;
        }

        return parsePortFromArgument(args[0]);
    }

    /**
     * 문자열 인자에서 포트 번호를 파싱합니다.
     */
    private int parsePortFromArgument(String portArg) {
        try {
            return Integer.parseInt(portArg);
        } catch (NumberFormatException e) {
            log.warn(LOG_INVALID_PORT, portArg, DEFAULT_PORT);
            return DEFAULT_PORT;
        }
    }

    /**
     * 설정된 포트로 서버를 생성합니다.
     */
    public Server createServer() {
        return new UndertowServer(port);
    }

}