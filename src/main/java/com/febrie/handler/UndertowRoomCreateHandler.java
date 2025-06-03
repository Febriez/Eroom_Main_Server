package com.febrie.handler;

import com.febrie.Main;
import com.febrie.config.PathConfig;
import com.febrie.dto.JsonRoomData;
import com.febrie.dto.LogEntry;
import com.febrie.dto.ProcessResult;
import com.febrie.dto.RoomCreationLogData;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.febrie.service.MeshyModelService;
import com.febrie.util.ErrorLogger;
import com.febrie.util.FileManager;
import com.febrie.util.FirebaseLogger;
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
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class UndertowRoomCreateHandler implements HttpHandler {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(UndertowRoomCreateHandler.class);
    private static final Gson gson = new GsonBuilder().disableHtmlEscaping().create(); // 특수문자 이스케이프 처리 비활성화

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
            case "refresh-model-urls" -> {
                // URL 경로에서 PUID 추출
                String pathPuid = exchange.getRequestPath().replace("/room/refresh-model-urls/", "");
                if (pathPuid.isEmpty()) {
                    sendError(exchange, StatusCodes.BAD_REQUEST, "PUID가 제공되지 않았습니다.");
                    yield StatusCodes.BAD_REQUEST;
                }

                log.info("모델 URL 갱신 요청 - PUID: {}", pathPuid);

                // 해당 PUID의 모델 URL 조회
                Map<String, String> modelUrls = MeshyModelService.getInstance().getCompletedModelUrls(pathPuid);
                if (modelUrls == null || modelUrls.isEmpty()) {
                    sendError(exchange, StatusCodes.NOT_FOUND, "해당 PUID의 모델 URL을 찾을 수 없습니다.");
                    yield StatusCodes.NOT_FOUND;
                }

                // URL 갱신 요청
                Map<String, String> refreshedUrls = MeshyModelService.getInstance().refreshModelUrls(pathPuid);

                // 응답 구성
                JsonObject json = new JsonObject();
                json.addProperty("status", "success");
                json.addProperty("puid", pathPuid);
                json.addProperty("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME));

                // 모델 생성 소요시간 추가 (가능한 경우)
                Long elapsedTime = MeshyModelService.getInstance().getModelProcessingTime(pathPuid);
                if (elapsedTime != null && elapsedTime > 0) {
                    json.addProperty("totalElapsedTimeMs", elapsedTime);
                    json.addProperty("totalElapsedTimeSec", elapsedTime / 1000);
                }

                // 모델 URL 정보 구성 (중첩 구조로 변경)
                JsonObject modelsJson = new JsonObject();
                Map<String, JsonObject> modelFormatMaps = new HashMap<>();

                // 모든 URL 순회하며 모델별로 그룹화
                for (Map.Entry<String, String> entry : refreshedUrls.entrySet()) {
                    String key = entry.getKey();
                    String url = entry.getValue();

                    if (key.contains("_")) {
                        // 모델명_형식 형태 처리
                        String[] parts = key.split("_", 2);
                        String modelName = parts[0];
                        String format = parts[1];

                        // 해당 모델의 JsonObject 가져오기
                        JsonObject formatObj = modelFormatMaps.computeIfAbsent(modelName, k -> new JsonObject());
                        formatObj.addProperty(format, url);
                    } else {
                        // 기본 이름 형태 (일반적으로 FBX)
                        JsonObject formatObj = modelFormatMaps.computeIfAbsent(key, k -> new JsonObject());
                        formatObj.addProperty("fbx", url);
                    }
                }

                // 모델별로 정리된 JsonObject 추가
                for (Map.Entry<String, JsonObject> entry : modelFormatMaps.entrySet()) {
                    modelsJson.add(entry.getKey(), entry.getValue());
                }
                json.add("modelUrls", modelsJson);

                // 하위 호환성을 위해 평면 형태의 URL도 포함
                JsonObject flatUrlsJson = new JsonObject();
                for (Map.Entry<String, String> entry : refreshedUrls.entrySet()) {
                    flatUrlsJson.addProperty(entry.getKey(), entry.getValue());
                }
                json.add("allModelUrls", flatUrlsJson);

                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, CONTENT_TYPE_JSON);
                // 이스케이프 처리가 비활성화된 Gson 사용하여 JSON 직렬화
                String jsonResponse = gson.toJson(json);
                exchange.getResponseSender().send(jsonResponse);
                yield StatusCodes.OK;
            }
            case "create" -> {
                // JSON 요청 파싱
                JsonObject requestJson = JsonParser.parseString(requestBody).getAsJsonObject();
                JsonRoomData roomData = JsonRoomData.fromJson(requestJson, UUID.randomUUID().toString());

                // 콜백 URL이 있으면 등록
                if (requestJson.has("callbackUrl") && !requestJson.get("callbackUrl").isJsonNull()) {
                    String callbackUrl = requestJson.get("callbackUrl").getAsString();
                    com.febrie.service.ClientCallbackRegistry.registerCallbackUrl(roomData.puid(), callbackUrl);
                    System.out.println("[INFO] 콜백 URL 등록됨: " + callbackUrl + " (PUID: " + roomData.puid() + ")");
                }

                // 룸 생성 처리
                ProcessResult result = executeRoomCreation(roomData, requestBody);
                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, CONTENT_TYPE_JSON);

                // 응답 구성
                JsonObject json = new JsonObject();
                json.addProperty("status", "success");
                json.addProperty("uuid", roomData.uuid());
                json.addProperty("puid", roomData.puid());
                json.addProperty("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME));
                json.addProperty("scenario_data", result.scenarioResult().get("scenario_data").getAsString());
                json.add("scripts", result.scriptResult());
                json.addProperty("modelsStatus", "pending");
                json.addProperty("message", "Room created successfully. 3D models are being generated in the background.");

                // 모델 URL 갱신 API 경로 추가
                String refreshUrlEndpoint = "/room/refresh-model-urls/" + roomData.puid();
                json.addProperty("refreshUrlsEndpoint", refreshUrlEndpoint);

                // 이스케이프 처리가 비활성화된 Gson 사용하여 JSON 직렬화
                String jsonResponse = gson.toJson(json);
                exchange.getResponseSender().send(jsonResponse);
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
        StringBuilder processLogBuilder = new StringBuilder();
        try {
            long requestLogStartTime = System.currentTimeMillis();
            LogEntry initialLog = LogEntry.create("INITIAL_REQUEST", requestBody, data.toString());
            processLogBuilder.append("[INFO] 초기 요청 로깅 시작\n");
            processLogBuilder.append(initialLog.format()).append("\n");
            System.out.println("[INFO] 초기 요청 로깅 완료 - 소요시간: " + (System.currentTimeMillis() - requestLogStartTime) + "ms");
            processLogBuilder.append("[INFO] 초기 요청 로깅 완료 - 소요시간: ").append(System.currentTimeMillis() - requestLogStartTime).append("ms\n");

            System.out.println("[INFO] 시나리오 생성 중...");
            processLogBuilder.append("[INFO] 시나리오 생성 시작\n");
            long scenarioStartTime = System.currentTimeMillis();
            JsonObject scenarioResult = Main.scenario(data.puid(), data.theme(), data.keywords(), data.room_URL());
            LogEntry scenarioLog = LogEntry.create("SCENARIO_GENERATION", formatScenarioInput(data), scenarioResult.toString());
            processLogBuilder.append(scenarioLog.format()).append("\n");
            System.out.println("[INFO] 시나리오 생성 완료 - 소요시간: " + (System.currentTimeMillis() - scenarioStartTime) + "ms");
            processLogBuilder.append("[INFO] 시나리오 생성 완료 - 소요시간: ").append(System.currentTimeMillis() - scenarioStartTime).append("ms\n");

            processLogBuilder.append("[INFO] Meshy 키워드 처리 시작\n");
            processKeywordsForMeshy(data.puid(), scenarioResult);
            processLogBuilder.append("[INFO] Meshy 키워드 처리 완료\n");

            System.out.println("[INFO] 스크립트 생성 중...");
            processLogBuilder.append("[INFO] 스크립트 생성 시작\n");
            long scriptStartTime = System.currentTimeMillis();
            JsonObject scriptResult = Main.gScripts(scenarioResult.get("datas").getAsJsonArray().toString(), data.room_URL());
            LogEntry scriptLog = LogEntry.create("SCRIPT_GENERATION", scenarioResult.toString(), scriptResult.toString());
            processLogBuilder.append(scriptLog.format()).append("\n");
            System.out.println("[INFO] 스크립트 생성 완료 - 소요시간: " + (System.currentTimeMillis() - scriptStartTime) + "ms");
            processLogBuilder.append("[INFO] 스크립트 생성 완료 - 소요시간: ").append(System.currentTimeMillis() - scriptStartTime).append("ms\n");


            String combinedLog = processLogBuilder.toString();

            System.out.println("[INFO] 룸 생성 프로세스 성공 - 총 소요시간: " + (System.currentTimeMillis() - startTime) + "ms");
            processLogBuilder.append("[INFO] 룸 생성 프로세스 성공 - 총 소요시간: ").append(System.currentTimeMillis() - startTime).append("ms\n");
            return ProcessResult.success(combinedLog, scenarioResult, scriptResult);
        } catch (Exception e) {
            String errorMsg = "[ERROR] 룸 생성 중 오류 발생 - 소요시간: " + (System.currentTimeMillis() - startTime) + "ms";
            System.out.println(errorMsg);
            processLogBuilder.append(errorMsg).append("\n");

            StringBuilder stackTrace = new StringBuilder(e.toString());
            for (StackTraceElement element : e.getStackTrace()) {
                stackTrace.append("\n    at ").append(element.toString());
            }

            System.err.println("[ERROR] 오류 내용: " + e);
            processLogBuilder.append("[ERROR] 오류 내용: ").append(stackTrace).append("\n");

            // 오류가 발생했더라도 지금까지 수집된 모든 로그를 저장
            com.febrie.util.LogManager.logFailure("API 호출 실패 로그:\n" + processLogBuilder.toString());

            return ProcessResult.error(processLogBuilder.toString(), "[Error] " + e.getMessage());
        }
    }

    private void processKeywordsForMeshy(String puid, JsonObject scenarioResult) {
        try {
            System.out.println("[INFO] 시나리오 키워드로 3D 모델 생성 처리 시작 - PUID: " + puid);
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
            // 새 ErrorLogger 클래스를 사용하여 더 자세한 오류 정보 저장
            String contextInfo = "룸 생성 결과 저장 중 오류 - UUID: " + data.uuid() + ", PUID: " + data.puid() + ", 테마: " + data.theme();
            ErrorLogger.logException(contextInfo, e);

            // Firebase에도 기본 정보 저장 (호환성 유지)
            RoomCreationLogData errorLog = RoomCreationLogData.error(data.uuid(), data.puid(), data.theme(), "Logging process failed: " + e.getMessage());
            FirebaseLogger.saveServerLogSync(errorLog);
        } catch (Exception ex) {
            System.err.println("[SEVERE] 로그 저장 중 추가 오류 발생: " + ex);
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

        // 이스케이프 처리가 비활성화된 Gson 사용하여 JSON 직렬화
        String jsonResponse = gson.toJson(errorResponse);
        exchange.getResponseSender().send(jsonResponse);
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