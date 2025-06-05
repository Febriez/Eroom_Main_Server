package com.febrie.util;

import com.febrie.config.PathConfig;
import com.febrie.manager.file.FileManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 로그 파일 생성 및 관리를 위한 유틸리티 클래스
 */
public class LogUtility {
    private static final Logger log = LoggerFactory.getLogger(LogUtility.class);
    private static final DateTimeFormatter FILE_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /**
     * 성공 또는 실패에 따라 적절한 로그 파일에 내용을 작성합니다.
     *
     * @param isSuccess 성공 여부
     * @param logContent 로그 내용
     */
    public static void writeLog(boolean isSuccess, String logContent) {
        if (isSuccess) {
            writeSuccessLog(logContent);
        } else {
            writeErrorLog(logContent);
        }
    }

    /**
     * 성공 로그를 파일에 기록합니다.
     *
     * @param content 로그 내용
     */
    public static void writeSuccessLog(String content) {
        writeToLogFile("success", content);
    }

    /**
     * 오류 로그를 파일에 기록합니다.
     *
     * @param content 로그 내용
     */
    public static void writeErrorLog(String content) {
        writeToLogFile("error", content);
    }

    /**
     * 디버그 로그를 파일에 기록합니다.
     *
     * @param content 로그 내용
     */
    public static void writeDebugLog(String content) {
        writeToLogFile("debug", content);
    }

    /**
     * 지정된 유형의 로그 파일에 내용을 기록합니다.
     *
     * @param logType 로그 유형 ("success", "error", "debug" 등)
     * @param content 로그 내용
     */
    private static void writeToLogFile(String logType, String content) {
        try {
            String today = LocalDateTime.now().format(FILE_DATE_FORMATTER);
            String timestamp = LocalDateTime.now().format(TIME_FORMATTER);
            String formattedContent = String.format("[%s] %s\n\n", timestamp, content);

            String logDirectory = PathConfig.getInstance().getBaseDirectory() + "/logs/";
            String logFilePath = logDirectory + today + "_" + logType + ".log";

            // 디렉토리 존재 확인 및 생성
            FileManager.ensureDirectoryExists(logDirectory);

            // 파일 추가 모드로 열어 내용 추가
            FileManager.getInstance().createFile(logFilePath, formattedContent);

        } catch (Exception e) {
            log.error("로그 파일 기록 중 오류 발생: {}", e.getMessage(), e);
        }
    }
}
