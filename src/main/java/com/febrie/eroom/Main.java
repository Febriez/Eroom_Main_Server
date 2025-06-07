package com.febrie.eroom;

import com.febrie.eroom.server.UndertowServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        try {
            int port = 8080;
            if (args.length > 0) {
                try {
                    port = Integer.parseInt(args[0]);
                } catch (NumberFormatException e) {
                    log.warn("유효하지 않은 포트 번호: {}. 기본값 8080을 사용합니다.", args[0]);
                }
            }

            UndertowServer server = new UndertowServer(port);
            server.start();

            // 셧다운 훅 추가
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
