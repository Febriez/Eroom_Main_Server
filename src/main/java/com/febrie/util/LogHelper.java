package com.febrie.util;

import com.febrie.log.LogEntry;
import org.jetbrains.annotations.NotNull;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 로그 생성 및 처리를 위한 헬퍼 클래스
 */
public class LogHelper {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 구조화된 로그 항목을 생성합니다.
     *
     * @param uuid 프로세스 UUID
     * @param puid 사용자 PUID
     * @param theme 테마 (선택 사항)
     * @return 구조화된 로그 항목
     */
    @NotNull
    public static LogEntry createLogEntry(String uuid, String puid, String theme) {
        StringBuilder logDetails = new StringBuilder();
        logDetails.append("UUID: ").append(uuid).append("\n");
        logDetails.append("PUID: ").append(puid).append("\n");
        if (theme != null && !theme.isEmpty()) {
            logDetails.append("테마: ").append(theme).append("\n");
        }
        return LogEntry.create("INITIALIZATION", logDetails.toString(), "Process Started");
    }

    /**
     * 로그 빌더에 프로세스 정보를 추가합니다.
     *
     * @param logBuilder 로그 빌더
     * @param processType 프로세스 유형
     * @param logLevel 로그 레벨
     */
    public static void processLogBuilder(@NotNull StringBuilder logBuilder, @NotNull LogProcessType processType, @NotNull LogLevel logLevel) {
        processLogBuilder(logBuilder, processType, logLevel, null);
    }

    /**
     * 로그 빌더에 프로세스 정보와 메시지를 추가합니다.
     *
     * @param logBuilder 로그 빌더
     * @param processType 프로세스 유형
     * @param logLevel 로그 레벨
     * @param message 추가 메시지 (선택 사항)
     */
    public static void processLogBuilder(@NotNull StringBuilder logBuilder, @NotNull LogProcessType processType,
                                         @NotNull LogLevel logLevel, String message) {
        String timestamp = LocalDateTime.now().format(FORMATTER);
        logBuilder.append("[").append(timestamp).append("] ")
                .append("[").append(logLevel).append("] ")
                .append("[").append(processType).append("] ");

        if (message != null && !message.isEmpty()) {
            logBuilder.append(message);
        }

        logBuilder.append("\n");
    }

    /**
     * 성공 로그를 기록합니다.
     *
     * @param logEntry 로그 항목
     * @param logBuilder 로그 빌더
     * @param totalTime 총 처리 시간(ms)
     * @param processName 프로세스 이름
     */
    public static void logSuccess(@NotNull LogEntry logEntry, @NotNull StringBuilder logBuilder, long totalTime, String processName) {
        logBuilder.append("\n성공: ").append(processName).append(" - 총 소요시간: ").append(totalTime).append("ms\n");
        logBuilder.append(logEntry.format()).append("\n");
    }

    /**
     * 실패 로그를 기록합니다.
     *
     * @param logEntry 로그 항목
     * @param logBuilder 로그 빌더
     * @param e 예외
     * @param processName 프로세스 이름
     * @param requestContent 요청 내용
     */
    public static void logFailure(@NotNull LogEntry logEntry, @NotNull StringBuilder logBuilder, @NotNull Exception e,
                                 String processName, String requestContent) {
        logBuilder.append("\n실패: ").append(processName).append("\n");
        logBuilder.append("오류: ").append(e.getMessage()).append("\n");
        logBuilder.append("요청 내용: ").append(requestContent).append("\n");
        logBuilder.append(logEntry.format()).append("\n");
    }

    /**
     * 빈 텍스트 블록 오류를 처리합니다.
     *
     * @param logEntry 로그 항목
     * @param logBuilder 로그 빌더
     */
    public static void handleEmptyTextBlock(@NotNull LogEntry logEntry, @NotNull StringBuilder logBuilder) {
        String errorMessage = "API 응답에 텍스트 블록이 없습니다.";
        logBuilder.append("[ERROR] ").append(errorMessage).append("\n");
        logBuilder.append(logEntry.format()).append("\n");
        throw new IllegalStateException(errorMessage);
    }
}
