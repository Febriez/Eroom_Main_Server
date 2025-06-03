package com.febrie.handler;

import com.febrie.Main;
import com.febrie.config.PathConfig;
import com.febrie.dto.JsonRoomData;
import com.febrie.dto.LogEntry;
import com.febrie.dto.ProcessResult;
import com.febrie.dto.RoomCreationLogData;
import com.febrie.service.MeshyModelService;
import com.febrie.util.FileManager;
import com.febrie.util.FirebaseLogger;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.StatusCodes;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class UndertowRoomCreateHandler implements HttpHandler {

    private static final String CONTENT_TYPE_JSON = "application/json";
    private final FileManager fileManager = FileManager.getInstance();

    @Override
    public void handleRequest(@NotNull HttpServerExchange exchange) {
        if (exchange.isInIoThread()) {
            exchange.dispatch(this);
            return;
        }
        processRequest(exchange);
    }

    protected void processRequest(@NotNull HttpServerExchange exchange) {
        try {
            String method = exchange.getRequestMethod().toString();
            String action = extractAction(exchange.getRequestPath());

            switch (method) {
                case "GET" -> handleGetRequest(exchange);
                case "PUT", "POST" -> handlePutNPostRequest(exchange, action);
                case "DELETE" -> handleDeleteRequest(exchange);
                default -> sendMethodNotAllowed(exchange);
            }
        } catch (Exception e) {
            sendError(exchange, StatusCodes.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    private String extractAction(@NotNull String requestPath) {
        return requestPath.replace("/room/", "").split("/")[0];
    }

    private void handlePutNPostRequest(@NotNull HttpServerExchange exchange, @NotNull String action) {
        try {
            String body = readRequestBody(exchange);
            int statusCode = processAction(exchange, action, body);
            if (!exchange.isResponseStarted()) {
                exchange.setStatusCode(statusCode);
                exchange.endExchange();
            }
        } catch (Exception e) {
            sendError(exchange, StatusCodes.BAD_REQUEST, e.getMessage());
        }
    }

    private int processAction(@NotNull HttpServerExchange exchange, @NotNull String action, @NotNull String requestBody) {
        return switch (action) {
            case "create" -> {
                JsonRoomData roomData = JsonRoomData.fromJson(JsonParser.parseString(requestBody), UUID.randomUUID().toString());
                ProcessResult result = executeRoomCreation(roomData, requestBody);
                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, CONTENT_TYPE_JSON);
                JsonObject json = new JsonObject();
                json.add("scenario_data", result.scenarioResult());
                json.add("scripts", result.scriptResult());
                exchange.getResponseSender().send(json.toString());
                saveResults(roomData, result, buildFilePath(roomData));
                yield StatusCodes.OK;
            }
            case "update" -> StatusCodes.METHOD_NOT_ALLOWED;
            default -> StatusCodes.BAD_REQUEST;
        };
    }


    @NotNull
    private String buildFilePath(@NotNull JsonRoomData data) {
        return buildFilePath(data, false);
    }

    @NotNull
    private String buildFilePath(@NotNull JsonRoomData data, boolean isError) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String suffix = isError ? "_[Error]" : "";
        // PathConfig에서 기본 경로 가져오기
        String basePath = PathConfig.getInstance().getBaseDirectory().replace("Meshy_Data", "Logs");
        return basePath + "/" + data.uuid() + "/" + data.puid() + "/log_" + timestamp + suffix + ".txt";
    }

    private ProcessResult executeRoomCreation(@NotNull JsonRoomData data, String requestBody) {
        long startTime = System.currentTimeMillis();
        System.out.println("[INFO] 룸 생성 프로세스 시작 - UUID: " + data.uuid() + ", PUID: " + data.puid());

        // 중요 환경 변수 확인
        String anthropicApiKey = System.getenv("ANTHROPIC_API_KEY");
        if (anthropicApiKey == null || anthropicApiKey.isEmpty()) {
            System.out.println("[WARN] ANTHROPIC_API_KEY 환경 변수가 설정되지 않았습니다. API 호출이 실패할 수 있습니다.");
        }
        try {
            long requestLogStartTime = System.currentTimeMillis();
            LogEntry initialLog = LogEntry.create("INITIAL_REQUEST", requestBody, data.toString());
            System.out.println("[INFO] 초기 요청 로깅 완료 - 소요시간: " + (System.currentTimeMillis() - requestLogStartTime) + "ms");

            System.out.println("[INFO] 시나리오 생성 중...");
            long scenarioStartTime = System.currentTimeMillis();
            JsonObject scenarioResult = Main.scenario(data.puid(), data.theme(), data.keywords(), data.room_URL());
            LogEntry scenarioLog = LogEntry.create("SCENARIO_GENERATION", formatScenarioInput(data), scenarioResult.toString());
            System.out.println("[INFO] 시나리오 생성 완료 - 소요시간: " + (System.currentTimeMillis() - scenarioStartTime) + "ms");

            processKeywordsForMeshy(data.puid(), scenarioResult);

            System.out.println("[INFO] 스크립트 생성 중...");
            long scriptStartTime = System.currentTimeMillis();
            JsonArray scriptResult = Main.gScripts(scenarioResult.get("datas").getAsJsonArray().toString(), data.room_URL());
            LogEntry scriptLog = LogEntry.create("SCRIPT_GENERATION", scenarioResult.toString(), scriptResult.toString());
            System.out.println("[INFO] 스크립트 생성 완료 - 소요시간: " + (System.currentTimeMillis() - scriptStartTime) + "ms");


            String combinedLog = String.join("\n", initialLog.format(), scenarioLog.format(), scriptLog.format());

            System.out.println("[INFO] 룸 생성 프로세스 성공 - 총 소요시간: " + (System.currentTimeMillis() - startTime) + "ms");
            return ProcessResult.success(combinedLog, scenarioResult, scriptResult);
        } catch (Exception e) {
            System.out.println("[ERROR] 룸 생성 중 오류 발생 - 소요시간: " + (System.currentTimeMillis() - startTime) + "ms");
            System.err.println("[ERROR] 오류 내용: " + e);
            return ProcessResult.error("[Error] " + e);
        }
    }

    private void processKeywordsForMeshy(String puid, JsonObject scenarioResult) {
        try {
            System.out.println("[INFO] 시나리오 키워드로 3D 모델 생성 처리 시작 - PUID: " + puid);

            String meshyApiKey = System.getenv("MESHY_API_KEY");
            if (meshyApiKey == null || meshyApiKey.isEmpty()) {
                System.out.println("[WARN] MESHY_API_KEY 환경 변수가 설정되지 않았습니다. 3D 모델 생성이 실패할 수 있습니다.");
            }

            MeshyModelService.getInstance().processScenarioKeywords(puid, scenarioResult);

        } catch (Exception e) {
            System.out.println("[ERROR] 3D 모델 생성 처리 중 오류: " + e.getMessage());
        }
    }

    private void saveResults(JsonRoomData data, @NotNull ProcessResult result, String filePath) {
        long startTime = System.currentTimeMillis();
        System.out.println("[INFO] 결과 저장 시작 - 상태: " + (result.isSuccess() ? "성공" : "실패"));
        try {
            if (result.isSuccess()) {
                saveSuccessResults(data, result, filePath);
            } else {
                System.out.println("[INFO] 에러 결과 저장 중...");
                String errorFilePath = buildFilePath(data, true);
                saveErrorResults(data, result.combinedLog(), errorFilePath);
            }
        } catch (Exception e) {
            System.out.println("[ERROR] 결과 저장 중 오류 발생 - 소요시간: " + (System.currentTimeMillis() - startTime) + "ms");
            System.err.println("[ERROR] 오류 내용: " + e);
            handleSaveError(data, e);
        }
        System.out.println("[INFO] 결과 저장 완료 - 총 소요시간: " + (System.currentTimeMillis() - startTime) + "ms");
    }

    private void saveSuccessResults(@NotNull JsonRoomData data, @NotNull ProcessResult result, String filePath) {
        long startTime = System.currentTimeMillis();
        System.out.println("[INFO] 성공 결과 저장 시작 - UUID: " + data.uuid() + ", PUID: " + data.puid());

        RoomCreationLogData firebaseLog = RoomCreationLogData.success(
                data.uuid(), data.puid(), data.theme(),
                result.combinedLog(), result.scenarioResult(), result.scriptResult()
        );
        FirebaseLogger.saveServerLogSync(firebaseLog);

        // 성공 로그 파일에 저장 (별도 스레드로 비동기 실행)
        CompletableFuture.runAsync(() -> {
            long fileStartTime = System.currentTimeMillis();
            String logContent = String.format(
                    "UUID: %s, PUID: %s, Theme: %s\n%s",
                    data.uuid(), data.puid(), data.theme(), result.combinedLog()
            );
            com.febrie.util.LogManager.logSuccess(logContent);
            System.out.println("[INFO] 성공 로그 저장 완료 - 소요시간: " +
                    (System.currentTimeMillis() - fileStartTime) + "ms");
        }).join();

        System.out.println("[INFO] 성공 결과 저장 작업 요청 완료 - 소요시간: " +
                (System.currentTimeMillis() - startTime) + "ms");
    }

    private void saveErrorResults(@NotNull JsonRoomData data, String errorLog, String errorFilePath) {
        long startTime = System.currentTimeMillis();
        System.out.println("[INFO] 에러 결과 저장 시작 - UUID: " + data.uuid() + ", PUID: " + data.puid());

        // Firebase에 에러 로그 저장 (FirebaseLogger 사용)
        RoomCreationLogData errorData = RoomCreationLogData.error(data.uuid(), data.puid(), data.theme(), errorLog);
        FirebaseLogger.saveServerLogSync(errorData);

        // 파일에 에러 로그 저장 (별도 스레드로 비동기 실행)
        CompletableFuture.runAsync(() -> {
            long fileStartTime = System.currentTimeMillis();
            System.out.println("[INFO] 에러 로그 파일 생성 중: " + errorFilePath);
            fileManager.createFile(errorFilePath, errorLog);
            System.out.println("[INFO] 에러 로그 파일 생성 완료 - 소요시간: " +
                    (System.currentTimeMillis() - fileStartTime) + "ms");
        });

        System.out.println("[INFO] 에러 결과 저장 작업 요청 완료 - 소요시간: " +
                (System.currentTimeMillis() - startTime) + "ms");
    }

    private void handleSaveError(JsonRoomData data, @NotNull Exception e) {
        try {
            // 현재 구현에서는 직접 Firebase에 로그를 저장하기 때문에 더 이상 필요하지 않지만,
            // 기존 인터페이스 유지를 위해 FirebaseLogger를 계속 사용
            RoomCreationLogData errorLog = RoomCreationLogData.error(data.uuid(), data.puid(), data.theme(), "Logging process failed: " + e.getMessage());
            FirebaseLogger.saveServerLogSync(errorLog);
        } catch (Exception ignored) {
            // 무시
        }
    }

    @NotNull
    private String formatScenarioInput(@NotNull JsonRoomData data) {
        return data.toString();
    }


    private void handleGetRequest(@NotNull HttpServerExchange exchange) {
        exchange.setStatusCode(StatusCodes.OK);
        exchange.endExchange();
    }

    private void handleDeleteRequest(@NotNull HttpServerExchange exchange) {
        exchange.setStatusCode(StatusCodes.OK);
        exchange.endExchange();
    }

    private void sendMethodNotAllowed(@NotNull HttpServerExchange exchange) {
        exchange.setStatusCode(StatusCodes.METHOD_NOT_ALLOWED);
        exchange.endExchange();
    }

    private void sendError(@NotNull HttpServerExchange exchange, int statusCode, @NotNull String message) {
        exchange.setStatusCode(statusCode);
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, CONTENT_TYPE_JSON);

        String requestPath = exchange.getRequestPath();
        String requestMethod = exchange.getRequestMethod().toString();

        JsonObject errorResponse = new JsonObject();
        errorResponse.addProperty("error", message.replace("\"", "\\\""));
        errorResponse.addProperty("path", requestPath);
        errorResponse.addProperty("method", requestMethod);
        errorResponse.addProperty("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME));

        exchange.getResponseSender().send(errorResponse.toString());
        exchange.endExchange();
    }

    @NotNull
    protected String readRequestBody(@NotNull HttpServerExchange exchange) throws Exception {
        try (var ignored = exchange.startBlocking()) {
            return new BufferedReader(
                    new InputStreamReader(exchange.getInputStream()))
                    .lines()
                    .collect(Collectors.joining("\n"));
        }
    }
}