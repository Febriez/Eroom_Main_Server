package com.febrie.service;

import com.febrie.Main;
import com.febrie.api.MeshyTextTo3D;
import com.febrie.dto.MeshyLogData;
import com.febrie.util.FileManager;
import com.febrie.util.FirebaseLogger;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * Meshy API 관련 로깅 작업을 처리하는 서비스 클래스
 */
@Slf4j
public class MeshyLogService {

    private static final String DEBUG_FOLDER = "debug_logs";
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final FileManager fileManager = FileManager.getInstance();

    private MeshyLogService() {
        // 유틸리티 클래스이므로 인스턴스화 방지
    }

    /**
     * 태스크 상태를 로컬 파일과 Firebase에 로깅합니다.
     *
     * @param taskId       태스크 ID
     * @param status       태스크 상태
     * @param taskType     태스크 유형 ("preview", "refine" 등)
     * @param prompt       사용된 프롬프트
     * @param parentTaskId 연관된 부모 태스크 ID (refine의 경우)
     */
    public static void logTaskStatus(String taskId, @NotNull MeshyTextTo3D.TaskStatus status,
                                    String taskType, String prompt, String parentTaskId) {
        try {
            // 로컬 파일 로깅
            String timestamp = LocalDateTime.now().format(DATE_FORMATTER);
            Files.createDirectories(Paths.get(DEBUG_FOLDER));
            String filename = String.format("%s/%s_task_%s_%s.json", DEBUG_FOLDER, taskType, taskId, timestamp);

            // 로그 데이터 생성
            Map<String, Object> logData = createLogData(taskId, status, taskType, prompt, parentTaskId, timestamp);

            // 파일에 저장
            try (FileWriter writer = new FileWriter(filename)) {
                GSON.toJson(logData, writer);
            }

            // Firebase 로깅용 추가 데이터
            Map<String, Object> additionalData = createAdditionalData(taskId, status, taskType, prompt, parentTaskId);

            // MeshyLogData 생성 및 Firebase 로깅
            MeshyLogData fbLogData = MeshyLogData.progress(
                    "system", taskId, taskType, prompt, status.progress, GSON.toJson(additionalData));

            saveToFirebase(fbLogData, taskType);

            System.out.println("[DEBUG] " + taskType + " 태스크 상태 로깅 완료: " + filename);
        } catch (IOException e) {
            System.err.println("[ERROR] 태스크 상태 로깅 실패: " + e.getMessage());
        }
    }

    /**
     * 완료된 태스크를 로깅합니다.
     *
     * @param taskId      태스크 ID
     * @param status      태스크 상태
     * @param taskType    태스크 유형
     * @param prompt      사용된 프롬프트
     * @param elapsedTime 소요된 시간 (밀리초)
     */
    public static void logCompletedTask(String taskId, @NotNull MeshyTextTo3D.TaskStatus status,
                                       String taskType, String prompt, long elapsedTime) {
        try {
            // 로컬 파일 로깅
            String timestamp = LocalDateTime.now().format(DATE_FORMATTER);
            Files.createDirectories(Paths.get(DEBUG_FOLDER));
            String filename = String.format("%s/completed_%s_task_%s_%s.json", DEBUG_FOLDER, taskType, taskId, timestamp);

            // 파일에 저장
            try (FileWriter writer = new FileWriter(filename)) {
                GSON.toJson(status, writer);
            }

            // Firebase 로깅용 추가 데이터
            Map<String, Object> additionalData = new HashMap<>();
            additionalData.put("task_id", taskId);
            additionalData.put("task_type", taskType);
            additionalData.put("status", status.status);
            additionalData.put("progress", status.progress);
            additionalData.put("timestamp", LocalDateTime.now().toString());
            additionalData.put("elapsed_time_ms", elapsedTime);
            additionalData.put("elapsed_time_formatted", formatElapsedTime(elapsedTime));
            additionalData.put("model_urls", status.modelUrls != null ? GSON.toJson(status.modelUrls) : "null");
            additionalData.put("thumbnail_url", status.thumbnailUrl);
            additionalData.put("task_details", GSON.toJson(status));

            // MeshyLogData 생성 및 Firebase 로깅
            MeshyLogData fbLogData = MeshyLogData.completed(
                    "system", taskId, taskType, prompt, GSON.toJson(additionalData));

            saveToFirebase(fbLogData, taskType);

            System.out.println("[DEBUG] 완료된 " + taskType + " 태스크 로깅 완료: " + filename);
        } catch (IOException e) {
            System.err.println("[ERROR] 완료된 태스크 로깅 실패: " + e.getMessage());
        }
    }

    /**
     * 실패한 태스크를 로깅합니다.
     *
     * @param taskId      태스크 ID
     * @param status      태스크 상태
     * @param taskType    태스크 유형
     * @param prompt      사용된 프롬프트
     * @param errorDetails 오류 세부 정보
     */
    public static void logFailedTask(String taskId, @NotNull MeshyTextTo3D.TaskStatus status,
                                    String taskType, String prompt, String errorDetails) {
        try {
            // 로컬 파일 로깅
            String timestamp = LocalDateTime.now().format(DATE_FORMATTER);
            Files.createDirectories(Paths.get(DEBUG_FOLDER));
            String filename = String.format("%s/failed_%s_task_%s_%s.json", DEBUG_FOLDER, taskType, taskId, timestamp);

            // 파일에 저장
            try (FileWriter writer = new FileWriter(filename)) {
                GSON.toJson(status, writer);
            }

            // Firebase 로깅용 추가 데이터
            Map<String, Object> additionalData = new HashMap<>();
            additionalData.put("task_id", taskId);
            additionalData.put("task_type", taskType);
            additionalData.put("status", status.status);
            additionalData.put("progress", status.progress);
            additionalData.put("timestamp", LocalDateTime.now().toString());
            additionalData.put("error_details", errorDetails);
            additionalData.put("api_error", status.error);
            additionalData.put("task_details", GSON.toJson(status));

            // MeshyLogData 생성 및 Firebase 로깅
            MeshyLogData fbLogData = MeshyLogData.error(
                    "system", taskId, taskType, prompt, GSON.toJson(additionalData));

            saveToFirebase(fbLogData, taskType);

            System.out.println("[DEBUG] 실패한 " + taskType + " 태스크 로깅 완료: " + filename);
        } catch (IOException e) {
            System.err.println("[ERROR] 실패한 태스크 로깅 실패: " + e.getMessage());
        }
    }

    /**
     * 기본 로그 데이터를 생성합니다.
     */
    private static Map<String, Object> createLogData(String taskId, MeshyTextTo3D.TaskStatus status,
                                                 String taskType, String prompt, String parentTaskId,
                                                 String timestamp) {
        Map<String, Object> logData = new HashMap<>();
        logData.put("task_id", taskId);
        logData.put("task_type", taskType);
        logData.put("status", status.status);
        logData.put("progress", status.progress);
        logData.put("timestamp", timestamp);
        logData.put("prompt", prompt);

        if (parentTaskId != null && "refine".equals(taskType)) {
            logData.put("preview_task_id", parentTaskId);
        }

        return logData;
    }

    /**
     * Firebase 로깅용 추가 데이터를 생성합니다.
     */
    private static Map<String, Object> createAdditionalData(String taskId, MeshyTextTo3D.TaskStatus status,
                                                         String taskType, String prompt, String parentTaskId) {
        Map<String, Object> additionalData = new HashMap<>();
        additionalData.put("task_id", taskId);
        additionalData.put("status", status.status);
        additionalData.put("progress", status.progress);
        additionalData.put("prompt", prompt);
        additionalData.put("timestamp", LocalDateTime.now().toString());
        additionalData.put("task_details", GSON.toJson(status));

        if (parentTaskId != null && "refine".equals(taskType)) {
            additionalData.put("preview_task_id", parentTaskId);
        }

        return additionalData;
    }

    /**
     * Firebase에 로그 데이터를 저장합니다.
     */
    private static void saveToFirebase(MeshyLogData logData, String taskType) {
        if (Main.app != null) {
            FirebaseLogger.saveServerLogSync(logData);
            System.out.println("[DEBUG] " + taskType + " 태스크 Firebase 로깅 완료");
        } else {
            System.out.println("[WARN] Firebase 앱이 초기화되지 않아 로깅을 건너뜁니다.");
        }
    }

    /**
     * 소요 시간을 포맷팅합니다.
     */
    private static String formatElapsedTime(long elapsedTimeMs) {
        long totalTimeSec = elapsedTimeMs / 1000;
        long minutes = totalTimeSec / 60;
        long seconds = totalTimeSec % 60;
        return String.format("%d분 %d초", minutes, seconds);
    }
}
