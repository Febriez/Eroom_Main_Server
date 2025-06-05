package com.febrie.logging;

import com.febrie.util.ErrorLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 로그 처리를 위한 헬퍼 클래스
 * 중복 코드를 줄이고 표준화된 로그 처리를 제공합니다.
 */
public class LogHelper {
    private static final Logger log = LoggerFactory.getLogger(LogHelper.class);
    private static final boolean USE_PRETTY_LOGGING = true;

    /**
     * 로그 빌더에 포맷팅된 메시지를 추가합니다.
     *
     * @param logBuilder        로그 메시지를 누적하는 StringBuilder
     * @param processType       로그 처리 유형
     * @param logLevel          로그 레벨
     * @param additionalMessage 추가 메시지 (선택적)
     * @return 업데이트된 StringBuilder
     */
    public static StringBuilder processLogBuilder(StringBuilder logBuilder, LogProcessType processType, LogLevel logLevel, String... additionalMessage) {
        // 기본 로그 메시지 구성
        logBuilder.append(logLevel.getPrefix()).append(processType.getMessage());

        // 추가 메시지가 있으면 덧붙임
        if (additionalMessage != null && additionalMessage.length > 0) {
            for (String message : additionalMessage) {
                if (message != null && !message.isEmpty()) {
                    logBuilder.append(" ").append(message);
                }
            }
        }

        // 줄바꿈 추가
        logBuilder.append("\n");

        // SLF4J 로그도 함께 출력
        switch (logLevel) {
            case INFO:
                log.info("{}{}", processType.getMessage(),
                        additionalMessage.length > 0 ? " " + String.join(" ", additionalMessage) : "");
                break;
            case WARN:
                log.warn("{}{}", processType.getMessage(),
                        additionalMessage.length > 0 ? " " + String.join(" ", additionalMessage) : "");
                break;
            case ERROR:
                log.error("{}{}", processType.getMessage(),
                        additionalMessage.length > 0 ? " " + String.join(" ", additionalMessage) : "");
                break;
            case DEBUG:
                log.debug("{}{}", processType.getMessage(),
                        additionalMessage.length > 0 ? " " + String.join(" ", additionalMessage) : "");
                break;
        }

        return logBuilder;
    }

    /**
     * API 호출 결과를 처리하고 적절한 로그를 생성합니다.
     *
     * @param logEntry     로그 엔트리 객체
     * @param logBuilder   로그 메시지를 누적하는 StringBuilder
     * @param errorMessage 오류 발생 시 메시지
     * @param isError      오류 여부
     * @return 포맷팅된 로그 문자열
     */
    public static String processApiResult(PrettyLogger.LogEntry logEntry, StringBuilder logBuilder,
                                          String errorMessage, boolean isError) {
        if (isError) {
            processLogBuilder(logBuilder, LogProcessType.COMPLETION, LogLevel.ERROR, errorMessage);
            logEntry.setStatus("FAILED");
            logEntry.setLogs(logBuilder.toString());

            if (USE_PRETTY_LOGGING) {
                String prettyLog = PrettyLogger.formatStructuredLog(logEntry.toMap());
                LogUtility.writeErrorLog(prettyLog);
            }
        }

        return logBuilder.toString();
    }

    /**
     * API 호출이 성공적으로 완료된 경우 로그를 생성합니다.
     *
     * @param logEntry      로그 엔트리 객체
     * @param logBuilder    로그 메시지를 누적하는 StringBuilder
     * @param totalTime     총 소요 시간 (밀리초)
     * @param operationName 작업 이름
     */
    public static void logSuccess(PrettyLogger.LogEntry logEntry, StringBuilder logBuilder,
                                  long totalTime, String operationName) {
        processLogBuilder(logBuilder, LogProcessType.COMPLETION, LogLevel.INFO,
                operationName + " 완료 - 총 소요시간: " + totalTime + "ms");

        // 성공 로그 생성 및 저장
        logEntry.setStatus("SUCCESS");
        logEntry.setLogs(logBuilder.toString());

        if (USE_PRETTY_LOGGING) {
            String prettyLog = PrettyLogger.formatStructuredLog(logEntry.toMap());
            LogUtility.writeSuccessLog(prettyLog);
        }
    }

    /**
     * API 호출에 실패한 경우 로그를 생성합니다.
     *
     * @param logEntry          로그 엔트리 객체
     * @param logBuilder        로그 메시지를 누적하는 StringBuilder
     * @param exception         발생한 예외
     * @param apiRequestContext API 요청 컨텍스트 설명
     * @param requestContent    API 요청 내용
     */
    public static void logFailure(PrettyLogger.LogEntry logEntry, StringBuilder logBuilder,
                                  Exception exception, String apiRequestContext, String requestContent) {
        processLogBuilder(logBuilder, LogProcessType.COMPLETION, LogLevel.ERROR,
                "API 요청 실패: " + exception.getMessage());

        logEntry.setStatus("FAILED");
        logEntry.setLogs(logBuilder.toString());

        if (USE_PRETTY_LOGGING) {
            String prettyLog = PrettyLogger.formatStructuredLog(logEntry.toMap());
            LogUtility.writeErrorLog(prettyLog);
        }

        ErrorLogger.logApiFailure(apiRequestContext,
                requestContent, "응답 없음 (API 예외)", exception);
    }

    /**
     * 표준 포맷의 로그 엔트리를 생성합니다.
     *
     * @param uuid  UUID
     * @param puid  PUID
     * @param theme 테마 (선택적)
     * @return 생성된 로그 엔트리 객체
     */
    public static PrettyLogger.LogEntry createLogEntry(String uuid, String puid, String theme) {
        PrettyLogger.LogEntry logEntry = new PrettyLogger.LogEntry();
        logEntry.setUuid(uuid);
        logEntry.setPuid(puid);
        logEntry.setTimestamp(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        if (theme != null) {
            logEntry.setTheme(theme);
        }
        logEntry.setStatus("PROCESSING");
        return logEntry;
    }

    /**
     * 텍스트 블록이 비어있는 경우 예외를 처리합니다.
     *
     * @param logEntry   로그 엔트리 객체
     * @param logBuilder 로그 메시지를 누적하는 StringBuilder
     * @throws IllegalArgumentException 항상 발생
     */
    public static void handleEmptyTextBlock(PrettyLogger.LogEntry logEntry, StringBuilder logBuilder) {
        processLogBuilder(logBuilder, LogProcessType.COMPLETION, LogLevel.ERROR, "응답 텍스트 블록이 비어 있습니다.");
        logEntry.setStatus("FAILED");
        logEntry.setLogs(logBuilder.toString());

        if (USE_PRETTY_LOGGING) {
            String prettyLog = PrettyLogger.formatStructuredLog(logEntry.toMap());
            LogUtility.writeErrorLog(prettyLog);
        }

        throw new IllegalArgumentException("First text block is empty");
    }
}
