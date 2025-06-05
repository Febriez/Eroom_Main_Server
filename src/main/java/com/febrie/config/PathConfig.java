package com.febrie.config;

import com.febrie.manager.file.FileManager;
import lombok.Getter;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Getter
public class PathConfig {

    private static volatile PathConfig instance;

    private static final String BASE_DIRECTORY = System.getProperty("user.home") + "/Desktop/Claude_Logs";
    private String logsDirectory;

    private static final String SUCCESS_LOG_FILENAME = "success.log";
    private static final String FAILED_LOG_FILENAME = "failed.log";
    private static final String DEBUG_LOG_FILENAME = "debug_details.log";

    private PathConfig() {
        System.out.println("[INFO] 기본 디렉토리 경로: " + BASE_DIRECTORY);

        this.logsDirectory = generateTimestampedLogsDirectory();
        ensureDirectoriesExist();
    }

    private void ensureDirectoriesExist() {
        try {
            FileManager.ensureDirectoryExists(getBaseDirectory());
            FileManager.ensureDirectoryExists(logsDirectory);
        } catch (Exception e) {
            System.err.println("[ERROR] 디렉토리 생성 중 오류 발생: " + e.getMessage());
        }
    }

    private String normalizePath(String path) {
        return FileManager.normalizePath(path);
    }

    private String generateTimestampedLogsDirectory() {
        LocalDateTime now = LocalDateTime.now();
        String timestamp = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        return normalizePath(getBaseDirectory() + "/" + timestamp);
    }

    public String getSuccessLogFilePath() {
        return normalizePath(logsDirectory + "/" + SUCCESS_LOG_FILENAME);
    }

    public String getFailedLogFilePath() {
        return normalizePath(logsDirectory + "/" + FAILED_LOG_FILENAME);
    }

    public String getDebugLogFilePath() {
        return normalizePath(logsDirectory + "/" + DEBUG_LOG_FILENAME);
    }

    public static PathConfig getInstance() {
        if (instance == null) {
            synchronized (PathConfig.class) {
                if (instance == null) {
                    instance = new PathConfig();
                }
            }
        }
        return instance;
    }

    public String getBaseDirectory() {
        return normalizePath(BASE_DIRECTORY);
    }

    public void createNewLogsDirectory() {
        this.logsDirectory = generateTimestampedLogsDirectory();
        ensureDirectoriesExist();

        System.out.println("[INFO] 새 로그 디렉토리가 생성되었습니다: " + logsDirectory);
    }

    public String getSubPath(String subPath) {
        return getBaseDirectory() + "/" + subPath;
    }

    public Path getSubPathAsPath(String subPath) {
        return Paths.get(getBaseDirectory(), subPath);
    }
}
