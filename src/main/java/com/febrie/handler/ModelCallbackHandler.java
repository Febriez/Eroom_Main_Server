package com.febrie.handler;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.StatusCodes;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.stream.Collectors;

/**
 * 3D 모델 생성 완료 콜백을 처리하는 핸들러
 */
public class ModelCallbackHandler implements HttpHandler {
    private static final Logger log = LoggerFactory.getLogger(ModelCallbackHandler.class);
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    @Override
    public void handleRequest(@NotNull HttpServerExchange exchange) {
        if (exchange.isInIoThread()) {
            exchange.dispatch(this);
            return;
        }

        try {
            // 요청 본문 읽기
            String body = readRequestBody(exchange);

            // 요청 본문 파싱
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String status = json.has("status") ? json.get("status").getAsString() : "unknown";
            String puid = json.has("puid") ? json.get("puid").getAsString() : "unknown";
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

            // 로그에 결과 출력
            log.info("모델 콜백 수신 - PUID: {}, 상태: {}, 시간: {}", puid, status, timestamp);

            // 콘솔에 예쁘게 출력
            String prettyJson = gson.toJson(json);
            log.info("\n=========== 모델 생성 콜백 수신 ===========\n" +
                    "상태: {}\n" +
                    "PUID: {}\n" +
                    "시간: {}\n\n" +
                    "-- 콜백 데이터 --\n{}\n" +
                    "==========================================",
                    status, puid, timestamp, prettyJson);

            // 응답 반환
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
            JsonObject response = new JsonObject();
            response.addProperty("status", "success");
            response.addProperty("message", "콜백을 성공적으로 수신했습니다");
            response.addProperty("timestamp", timestamp);
            exchange.getResponseSender().send(response.toString());

        } catch (Exception e) {
            log.error("콜백 처리 중 오류 발생: {}", e.getMessage(), e);
            sendError(exchange, e.getMessage());
        }
    }

    private String readRequestBody(@NotNull HttpServerExchange exchange) throws Exception {
        try (var ignored = exchange.startBlocking()) {
            return new BufferedReader(
                    new InputStreamReader(exchange.getInputStream()))
                    .lines()
                    .collect(Collectors.joining("\n"));
        }
    }

    private void sendError(@NotNull HttpServerExchange exchange, String message) {
        exchange.setStatusCode(StatusCodes.INTERNAL_SERVER_ERROR);
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");

        JsonObject errorResponse = new JsonObject();
        errorResponse.addProperty("status", "error");
        errorResponse.addProperty("message", "콜백 처리 중 오류 발생: " + message);
        errorResponse.addProperty("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

        exchange.getResponseSender().send(errorResponse.toString());
    }
}
