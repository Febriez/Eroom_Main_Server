package com.febrie.logging;

import com.febrie.config.PathConfig;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 통합 로깅 유틸리티 클래스
 * 모든 로그 파일 작성 기능을 단일 클래스로 통합
 */
public class LogUtility {
    private static final Logger log = LoggerFactory.getLogger(LogUtility.class);

    // 날짜/시간 형식
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    // 기본 로그 디렉토리 및 파일
    private static final String DEFAULT_LOG_DIR = System.getProperty("user.dir") + "/logs";
    private static final String SUCCESS_LOG_FILE = DEFAULT_LOG_DIR + "/success.log";
    private static final String CALLBACK_LOG_FILE = DEFAULT_LOG_DIR + "/callback.log";
    private static final String ERROR_LOG_FILE = DEFAULT_LOG_DIR + "/error.log";
    private static final String DEBUG_LOG_FILE = DEFAULT_LOG_DIR + "/debug.log";

    // 클래스 초기화 시 로그 디렉토리 생성
    static {
        try {
            // 로그 디렉토리 생성
            Files.createDirectories(Paths.get(DEFAULT_LOG_DIR));
            log.info("로그 디렉토리 생성됨: {}", DEFAULT_LOG_DIR);

            // PathConfig에서 설정한 로그 디렉토리도 생성
            if (PathConfig.getInstance() != null) {
                String configLogDir = PathConfig.getInstance().getBaseDirectory().replace("Meshy_Data", "Logs");
                Files.createDirectories(Paths.get(configLogDir));
                log.info("PathConfig 로그 디렉토리 생성됨: {}", configLogDir);
            }
        } catch (Exception e) {
            log.error("로그 디렉토리 생성 실패: {}", e.getMessage(), e);
        }
    }

    /**
     * 성공 로그 파일에 기록
     */
    public static void writeSuccessLog(String content) {
        String formattedLog = formatLogEntry(content);
        writeToFile(SUCCESS_LOG_FILE, formattedLog);

        // PathConfig 설정이 있으면 해당 성공 로그 파일에도 기록
        if (PathConfig.getInstance() != null) {
            writeToFile(PathConfig.getInstance().getSuccessLogFilePath(), formattedLog);
        }
    }

    /**
     * 실패/오류 로그 파일에 기록
     */
    public static void writeErrorLog(String content) {
        String formattedLog = formatLogEntry(content);
        writeToFile(ERROR_LOG_FILE, formattedLog);
        writeToFile(DEBUG_LOG_FILE, formattedLog);

        // 시스템 정보 추가
        String systemInfo = formatLogEntry("시스템 정보:\n" + 
            "OS: " + System.getProperty("os.name") + " " + System.getProperty("os.version") + "\n" +
            "Java: " + System.getProperty("java.version") + "\n" +
            "사용 가능 메모리: " + (Runtime.getRuntime().maxMemory() / (1024*1024)) + "MB\n" +
            "현재 시간: " + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        writeToFile(DEBUG_LOG_FILE, systemInfo);

        // PathConfig 설정이 있으면 해당 오류 로그 파일에도 기록
        if (PathConfig.getInstance() != null) {
            writeToFile(PathConfig.getInstance().getFailedLogFilePath(), formattedLog);
            writeToFile(PathConfig.getInstance().getDebugLogFilePath(), formattedLog);
            writeToFile(PathConfig.getInstance().getDebugLogFilePath(), systemInfo);
        }
    }

    /**
     * 콜백 로그 파일에 기록
     */
    public static void writeCallbackLog(String content) {
        String formattedLog = formatLogEntry(content);
        writeToFile(CALLBACK_LOG_FILE, formattedLog);
    }

    /**
     * 성공/실패 여부에 따라 적절한 로그 파일에 기록
     */
    public static void writeLog(boolean isSuccess, String content) {
        if (isSuccess) {
            writeSuccessLog(content);
        } else {
            writeErrorLog(content);
        }
    }

    /**
     * 커스텀 경로에 로그 파일 작성
     */
    public static void writeToCustomFile(String filePath, String content) {
        try {
            // 디렉토리 확인 및 생성
            Path path = Paths.get(filePath);
            Files.createDirectories(path.getParent());

            // 파일에 내용 작성
            writeToFile(filePath, formatLogEntry(content));
        } catch (Exception e) {
            log.error("커스텀 로그 파일 작성 실패 ({}): {}", filePath, e.getMessage(), e);
        }
    }

    /**
     * 로그 항목 형식화 (타임스탬프 포함)
     */
    @NotNull
    private static String formatLogEntry(String message) {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        return "[" + timestamp + "] " + message + System.lineSeparator() + System.lineSeparator();
    }

    /**
     * 파일에 로그 내용 기록 (기존 LogWriter 방식 - 항상 추가)
     */
    private static synchronized void writeToFile(String filePath, String content) {
        try {
            Path path = Paths.get(filePath);

            // 파일의 디렉토리 확인 및 생성
            Files.createDirectories(path.getParent());

            // 파일이 없으면 생성
            if (!Files.exists(path)) {
                Files.createFile(path);
            }

            // 파일에 내용 추가
            Files.write(path, content.getBytes(), StandardOpenOption.APPEND);
        } catch (Exception e) {
            log.error("로그 파일 쓰기 실패 ({}): {}", filePath, e.getMessage(), e);
        }
    }

    /**
     * 파일에 로그 내용 작성 (기존 LogManager 방식 - 읽고 쓰기)
     * 큰 파일이나 자주 접근하는 파일에는 사용하지 않는 것이 좋습니다.
     */
    private static synchronized void readAndWriteToFile(String filePath, String content) {
        try {
            // 파일 경로 객체 생성
            Path path = Paths.get(filePath);

            // 디렉토리 확인 및 생성
            Files.createDirectories(path.getParent());

            // 기존 파일 내용 읽기 (존재하는 경우)
            String existingContent = "";
            if (Files.exists(path)) {
                existingContent = Files.readString(path);
            }

            // 새 내용 추가하여 파일 작성
            String updatedContent = existingContent.isEmpty() ?
                    content : existingContent + System.lineSeparator() + content;

            Files.writeString(path, updatedContent);
        } catch (Exception e) {
            log.error("로그 파일 읽기/쓰기 실패 ({}): {}", filePath, e.getMessage(), e);
        }
    }
}
