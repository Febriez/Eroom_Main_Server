package com.febrie.util;

import com.febrie.config.PathConfig;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 애플리케이션 로그를 관리하는 유틸리티 클래스
 * 모든 로그를 시간 기반 폴더에 성공/실패 로그 파일로 저장
 */
public class LogManager {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LogManager.class);

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /**
     * 성공 로그 저장
     *
     * @param message 로그 메시지
     */
    public static void logSuccess(String message) {
        String logEntry = formatLogEntry(message);
        writeToFile(PathConfig.getInstance().getSuccessLogFilePath(), logEntry);
    }

    /**
     * 실패 로그 저장
     *
     * @param message 로그 메시지
     */
    public static void logFailure(String message) {
        String logEntry = formatLogEntry(message);
        writeToFile(PathConfig.getInstance().getFailedLogFilePath(), logEntry);

        // 모든 실패 로그를 디버그 로그에도 저장
        writeToFile(PathConfig.getInstance().getDebugLogFilePath(), logEntry);

        // 실패 발생 시 시스템 정보도 함께 저장
        String systemInfo = formatLogEntry("시스템 정보:\n" + 
            "OS: " + System.getProperty("os.name") + " " + System.getProperty("os.version") + "\n" +
            "Java: " + System.getProperty("java.version") + "\n" +
            "사용 가능 메모리: " + (Runtime.getRuntime().maxMemory() / (1024*1024)) + "MB\n" +
            "현재 시간: " + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        writeToFile(PathConfig.getInstance().getDebugLogFilePath(), systemInfo);
    }

    /**
     * 성공 여부에 따라 적절한 로그 파일에 저장
     *
     * @param isSuccess 성공 여부
     * @param message   로그 메시지
     */
    public static void log(boolean isSuccess, String message) {
        if (isSuccess) {
            logSuccess(message);
        } else {
            logFailure(message);
        }
    }

    /**
     * 로그 항목 형식화 (타임스탬프 포함)
     */
    @NotNull
    private static String formatLogEntry(String message) {
        String timestamp = LocalDateTime.now().format(TIME_FORMATTER);
        return "[" + timestamp + "] " + message;
    }

    /**
     * 파일에 로그 내용 추가
     */
    private static synchronized void writeToFile(String filePath, String content) {
        try {
            // 기존 파일 내용 읽기 (존재하는 경우)
            String existingContent = "";
            Path path = Paths.get(filePath);
            if (Files.exists(path)) {
                existingContent = Files.readString(path);
            }

            // 새 내용 추가하여 파일 작성
            String updatedContent = existingContent.isEmpty() ?
                    content : existingContent + System.lineSeparator() + content;

            FileManager.getInstance().createFile(filePath, updatedContent);
        } catch (Exception e) {
            log.error("로그 파일 쓰기 실패: {}", e.getMessage(), e);
            System.err.println("[ERROR] 로그 파일 쓰기 실패: " + filePath + " - " + e.getMessage());
        }
    }
}
