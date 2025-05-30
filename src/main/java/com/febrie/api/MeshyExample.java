package com.febrie.api;

import com.febrie.Main;
import com.febrie.dto.FirebaseLogData;
import com.febrie.dto.MeshyLogData;
import com.febrie.util.FirebaseLogger;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * Meshy AI 3D 모델 생성 예제 및 태스크 관리 유틸리티
 */
public class MeshyExample {
    // 상수 정의
    private static final String DEBUG_FOLDER = "debug_logs";
    private static final String DESKTOP_OUTPUT_FOLDER = "C:\\Users\\201-11\\Desktop\\Meshy_Data\\";
    private static final String UNIFIED_LOG_FILE = DESKTOP_OUTPUT_FOLDER + "meshy_task_log.txt";
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    // 기본 설정값
    private static final int POLLING_INTERVAL_MS = 5000; // 5초

    /**
     * 태스크의 현재 상태를 로컬 파일 및 Firebase에 저장합니다.
     * 로깅 로직을 추상화하여 중복 코드를 제거합니다.
     *
     * @param taskId     태스크 ID
     * @param status     현재 태스크 상태
     * @param taskType   태스크 유형 ("preview" 또는 "refine")
     * @param prompt     생성에 사용된 프롬프트
     * @param parentTaskId 부모 태스크 ID (refine 태스크의 경우에만 사용)
     */
    public static void logTaskStatus(String taskId, @NotNull MeshyTextTo3D.TaskStatus status, 
                                    String taskType, String prompt, String parentTaskId) {
        try {
            // 로컬 파일 로깅
            String timestamp = LocalDateTime.now().format(dateFormatter);
            Files.createDirectories(Paths.get(DEBUG_FOLDER));
            String filename = String.format("%s/%s_task_%s_%s.json", DEBUG_FOLDER, taskType, taskId, timestamp);

            // 태스크 상태 요약 정보 생성
            Map<String, Object> logData = new HashMap<>();
            logData.put("task_id", taskId);
            logData.put("task_type", taskType);
            logData.put("status", status.status);
            logData.put("progress", status.progress);
            logData.put("timestamp", timestamp);
            logData.put("prompt", prompt);

            // refine 태스크인 경우 부모 태스크 ID 추가
            if (parentTaskId != null && "refine".equals(taskType)) {
                logData.put("preview_task_id", parentTaskId);
            }

            try (FileWriter writer = new FileWriter(filename)) {
                // API 응답 전체 대신 요약 정보 저장
                gson.toJson(logData, writer);
            }

            // Firebase 로깅을 위한 추가 데이터 생성
            Map<String, Object> additionalData = createAdditionalDataMap(taskId, status, prompt);
            additionalData.put("task_details", gson.toJson(status));

            // refine 태스크인 경우 부모 태스크 ID 추가
            if (parentTaskId != null && "refine".equals(taskType)) {
                additionalData.put("preview_task_id", parentTaskId);
            }

            // 태스크 트래커 업데이트
            MeshyTaskTracker.updateTaskStatus(taskId, status.status, status.progress);

            // MeshyLogData 생성 및 Firebase 로깅
            MeshyLogData fbLogData = MeshyLogData.progress(
                    "system", taskId, taskType, prompt, status.progress, gson.toJson(additionalData));

            saveToFirebase(fbLogData, taskType);

            System.out.println("[DEBUG] " + taskType + " 태스크 상태 로깅 완료: " + filename);
        } catch (IOException e) {
            System.err.println("[ERROR] 태스크 상태 로깅 실패: " + e.getMessage());
        }
    }

    /**
     * Preview 태스크의 현재 상태를 로컬 파일 및 Firebase에 저장합니다.
     * 태스크의 요약 정보와 전체 상태 정보를 각각 별도의 파일로 저장합니다.
     *
     * @param taskId Preview 태스크 ID
     * @param status 현재 태스크 상태
     * @param prompt 생성에 사용된 프롬프트
     */
    public static void logPreviewTaskStatus(String taskId, @NotNull MeshyTextTo3D.TaskStatus status, String prompt) {
        logTaskStatus(taskId, status, "preview", prompt, null);
    }

    /**
     * Refine 태스크의 현재 상태를 로컬 파일 및 Firebase에 저장합니다.
     * 태스크의 요약 정보와 전체 상태 정보를 각각 별도의 파일로 저장합니다.
     *
     * @param taskId        Refine 태스크 ID
     * @param previewTaskId 연관된 Preview 태스크 ID
     * @param status        현재 태스크 상태
     * @param texturePrompt 텍스처 생성에 사용된 프롬프트
     */
    public static void logRefineTaskStatus(String taskId, String previewTaskId, @NotNull MeshyTextTo3D.TaskStatus status, String texturePrompt) {
        logTaskStatus(taskId, status, "refine", texturePrompt, previewTaskId);
    }

    /**
     * Firebase에 로그 데이터를 저장합니다.
     * 
     * @param logData  저장할 로그 데이터
     * @param taskType 태스크 유형 (로깅용)
     */
    private static void saveToFirebase(MeshyLogData logData, String taskType) {
        // Main 클래스의 app 인스턴스 사용
        if (Main.app != null) {
            FirebaseLogger.saveServerLogSync(logData);
            System.out.println("[DEBUG] " + taskType + " 태스크 Firebase 로깅 완료");
        } else {
            System.out.println("[WARN] Firebase 앱이 초기화되지 않아 로깅을 건너뜁니다.");
        }
    }

    /**
     * 태스크 ID로 중단된 작업의 상태를 확인합니다.
     *
     * @param taskId   확인할 태스크 ID (null이 아니어야 함)
     * @param taskType 태스크 유형 ("preview" 또는 "refine")
     * @param texturePrompt 텍스처 생성에 사용된 프롬프트 (refine 태스크인 경우에만 사용, 없으면 null)
     */
    public static void checkInterruptedTask(String taskId, String taskType, String texturePrompt) {
        if (taskId == null || taskId.isEmpty()) {
            System.out.println("[경고] 태스크 ID가 null이거나 비어 있습니다.");
            return;
        }

        try {
            MeshyTextTo3D meshy = new MeshyTextTo3D();
            MeshyTextTo3D.TaskStatus status = meshy.getTaskStatus(taskId);

            System.out.println("\n====== 중단된 태스크 상태 확인 ======");
            System.out.println("태스크 ID: " + taskId);
            System.out.println("태스크 유형: " + taskType);
            System.out.println("상태: " + status.status);
            System.out.println("진행률: " + status.progress + "%");

            if ("SUCCEEDED".equals(status.status)) {
                System.out.println("\n====== 완료된 태스크 결과 ======");
                printModelUrls(status.modelUrls);
                System.out.println("썸네일: " + status.thumbnailUrl);

                // 결과를 데스크탑 지정 폴더에 저장
                saveMeshyTaskResultToDesktop("Task " + taskId, status, taskType);

                // 통합 로그에 기록
                appendToLog("중단된 " + taskType + " 태스크 확인 완료 - ID: " + taskId + ", 상태: " + status.status);

                // 전체 JSON 저장
                String timestamp = LocalDateTime.now().format(dateFormatter);
                Files.createDirectories(Paths.get(DEBUG_FOLDER));
                String filename = String.format("%s/completed_%s_task_%s_%s.json",
                        DEBUG_FOLDER, taskType, taskId, timestamp);

                try (FileWriter writer = new FileWriter(filename)) {
                    gson.toJson(status, writer);
                }

                // Firebase에 완료된 태스크 로깅
                if (Main.app != null) {
                    Map<String, Object> additionalData = createAdditionalDataMap(taskId, status, null);
                    additionalData.put("task_type", taskType);
                    additionalData.put("model_urls", status.modelUrls != null ? gson.toJson(status.modelUrls) : "null");
                    additionalData.put("thumbnail_url", status.thumbnailUrl);
                    additionalData.put("task_details", gson.toJson(status));

                    // taskType이 "refine"인 경우에 대한 프롬프트 처리
                    if (taskType.equals("refine")) {
                        // 태스크 추적기에서 텍스처 프롬프트 조회 시도
                        MeshyTaskTracker.TaskInfo taskInfo = MeshyTaskTracker.findTaskById(taskId);
                        if (taskInfo != null) {
                            texturePrompt = taskInfo.getPrompt();
                        }
                    }
                    String promptToUse = taskType.equals("preview") ? 
                            "Preview task" : (texturePrompt != null ? texturePrompt : "Refine task");

                    MeshyLogData fbLogData = MeshyLogData.completed(
                            "system", taskId, taskType, promptToUse, gson.toJson(additionalData));

                    saveToFirebase(fbLogData, taskType);
                } else {
                    System.out.println("[WARN] Firebase 앱이 초기화되지 않아 로깅을 건너뜁니다.");
                }

                System.out.println("\n결과가 다음 파일에 저장되었습니다: " + filename);
            }
        } catch (Exception e) {
            System.err.println("[ERROR] 중단된 태스크 확인 실패: " + e);
        }
    }

    /**
     * 중단된 Preview 및 Refine 태스크의 최종 결과물을 확인합니다.
     *
     * @param previewTaskId Preview 태스크 ID (null일 수 있음)
     * @param refineTaskId  Refine 태스크 ID (null일 수 있음)
     */
    public static void retrieveCompletedTaskResults(String previewTaskId, String refineTaskId) {
        try {
            // Preview 태스크가 있으면 확인
            if (previewTaskId != null && !previewTaskId.isEmpty()) {
                System.out.println("\n====== Preview 태스크 확인 중 ======");
                checkInterruptedTask(previewTaskId, "preview", null);
            } else {
                System.out.println("\n====== Preview 태스크 ID가 지정되지 않았습니다 ======");
            }

            // Refine 태스크가 있으면 확인
            if (refineTaskId != null && !refineTaskId.isEmpty()) {
                System.out.println("\n====== Refine 태스크 확인 중 ======");
                checkInterruptedTask(refineTaskId, "refine", null); // 텍스처 프롬프트 정보가 없으므로 null 전달
            } else {
                System.out.println("\n====== Refine 태스크 ID가 지정되지 않았습니다 ======");
            }

            // 둘 다 null이면 안내 메시지 출력
            if ((previewTaskId == null || previewTaskId.isEmpty()) &&
                    (refineTaskId == null || refineTaskId.isEmpty())) {
                System.out.println("\n[안내] 확인할 태스크 ID가 지정되지 않았습니다.");
                System.out.println("태스크 ID를 지정하여 다시 시도해주세요.");
            }
        } catch (Exception e) {
            System.err.println("[ERROR] 태스크 결과 검색 실패: " + e);
        }
    }

    /**
     * 완료된 태스크 결과를 지정된 폴더에 저장합니다.
     *
     * @param prompt   생성에 사용된 프롬프트
     * @param status   태스크 상태 정보
     * @param taskType 태스크 타입("preview" 또는 "refine")
     */
    public static void saveMeshyTaskResultToDesktop(String prompt, MeshyTextTo3D.TaskStatus status, String taskType) {
        saveMeshyTaskResultToDesktop(prompt, status, taskType, 0);
    }

    /**
     * 완료된 태스크 결과를 지정된 폴더에 저장합니다.
     *
     * @param prompt        생성에 사용된 프롬프트
     * @param status        태스크 상태 정보
     * @param taskType      태스크 타입("preview" 또는 "refine")
     * @param elapsedTimeMs 태스크 완료까지 소요된 시간(밀리초)
     */
    public static void saveMeshyTaskResultToDesktop(String prompt, MeshyTextTo3D.TaskStatus status, String taskType, long elapsedTimeMs) {
        if (status == null) {
            System.err.println("[ERROR] 저장할 태스크 상태 정보가 null입니다.");
            return;
        }

        try {
            // FileManager 인스턴스 획득
            com.febrie.util.FileManager fileManager = com.febrie.util.FileManager.getInstance();

            // 현재 시간 기반으로 폴더 생성
            LocalDateTime now = LocalDateTime.now();
            String timestamp = now.format(dateFormatter);
            String dateFolderName = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm"));

            // 기본 출력 디렉토리와 시간별 폴더 설정
            String outputDir = DESKTOP_OUTPUT_FOLDER;
            String timestampFolder = outputDir + "/" + dateFolderName;

            // 출력 디렉토리 생성 확인
            fileManager.createDirectory(outputDir);
            fileManager.createDirectory(timestampFolder);

            // 파일명 설정
            String sanitizedPrompt = prompt != null ?
                    prompt.replaceAll("[\\\\/:*?\"<>|]", "_").substring(0, Math.min(prompt.length(), 50)) :
                    "unknown";
            String filename = String.format("%s_%s.log", sanitizedPrompt, timestamp);
            String fullPath = timestampFolder + "/" + filename;

            // 출력 내용 생성
            StringBuilder content = new StringBuilder();
            content.append("====== Meshy AI 3D 모델 생성 결과 ======\n");
            content.append("생성 시간: ").append(timestamp).append("\n");
            content.append("프롬프트: ").append(prompt).append("\n");
            content.append("태스크 유형: ").append(taskType).append("\n");
            content.append("태스크 ID: ").append(status.id).append("\n");
            content.append("상태: ").append(status.status).append("\n");
            content.append("진행률: ").append(status.progress).append("%\n");

            // 소요 시간 정보 추가
            if (elapsedTimeMs > 0) {
                long totalTimeSec = elapsedTimeMs / 1000;
                long minutes = totalTimeSec / 60;
                long seconds = totalTimeSec % 60;
                content.append("소요 시간: ").append(minutes).append("분 ").append(seconds).append("초\n");
            }
            content.append("\n");

            content.append("====== 모델 URL 정보 ======\n");
            appendModelUrlsToStringBuilder(content, status.modelUrls);

            content.append("\n====== 썸네일 정보 ======\n");
            content.append("썸네일 URL: ").append(status.thumbnailUrl != null ? status.thumbnailUrl : "없음").append("\n\n");

            content.append("====== 전체 상태 정보 (JSON) ======\n");
            content.append(gson.toJson(status));

            // 마지막 오류 정보 추가 (실패한 경우)
            if (status.status != null && status.status.equals("FAILED")) {
                content.append("\n\n====== 오류 정보 ======\n");
                if (status.error != null) {
                    content.append("오류 메시지: ").append(status.error).append("\n");
                } else {
                    content.append("오류 정보가 없습니다. API가 오류 세부 정보를 제공하지 않았습니다.\n");
                }
            }

            // 파일 생성 (FileManager 활용)
            fileManager.createFile(fullPath, content.toString());

            // 시간별 폴더에 태스크 ID 로그 파일도 추가 생성 (빠른 참조용)
            String taskIdLogFile = timestampFolder + "/" + status.id + "_info.txt";
            fileManager.createFile(taskIdLogFile,
                    "태스크 ID: " + status.id + "\n" +
                            "프롬프트: " + prompt + "\n" +
                            "상태: " + status.status + " (" + status.progress + "%)\n" +
                            "결과 파일: " + filename + "\n");

            // 통합 로그에 파일 저장 정보 추가
            appendToLog("Meshy 태스크 결과 파일 저장 - 태스크 ID: " + status.id + ", 폴더: " + timestampFolder + ", 파일: " + filename);

            System.out.println("[INFO] Meshy 태스크 결과가 다음 위치에 저장되었습니다: " + fullPath);
            System.out.println("[INFO] 로그 폴더: " + timestampFolder);
        } catch (Exception e) {
            System.err.println("[ERROR] Meshy 태스크 결과 저장 실패: " + e);
        }
    }

    /**
     * 메인 메소드 - 다양한 기능을 사용할 수 있는 예제 모음
     */
    public static void main(String[] args) {
        // 원하는 메소드 주석 해제 후 사용

        // 1. 전체 3D 모델 생성 프로세스 (Preview + Refine) - 예시
        // createFullModel("a room key", "realistic", "green slimy skin with scales");

        // 2. Preview 모델만 생성
        // String previewTaskId = createPreviewModel("a crystal ball", "realistic");
        // System.out.println("생성된 Preview 태스크 ID: " + previewTaskId);

        // 3. 기존 Preview에서 Refine 모델 생성
        // String previewTaskId = "YOUR_PREVIEW_TASK_ID";
        // String refineTaskId = createRefineModel(previewTaskId, "blue crystal texture with glowing effects", true);
        // System.out.println("생성된 Refine 태스크 ID: " + refineTaskId);

        // 4. 중단된 태스크 결과 확인
        // String previewTaskId = null;
        // String refineTaskId = "01971f82-2275-7406-9b74-209de52b7474"; // 없으면 null 또는 빈 문자열
        // retrieveCompletedTaskResults(previewTaskId, refineTaskId);

        // 5. 모든 활성 태스크 확인
        // MeshyTaskTracker.checkAllActiveTasks();

        // 6. 특정 태스크 모니터링
        // MeshyTaskTracker.monitorTask("태스크ID", true);

        // 실행 방식 1: 기존 메소드 호출 (이전 버전과 호환성 유지)
        // createFullModel("a basic room key without ring", "realistic", "shiny gold");

        // 실행 방식 2: 새로운 서비스 클래스 사용 (권장)
        com.febrie.service.MeshyTaskService taskService = new com.febrie.service.MeshyTaskService();
        com.febrie.model.MeshyTask result = taskService.createFullModel(
                "a basic room key without ring", "realistic", "shiny gold");

        if (result != null && result.isSucceeded()) {
            System.out.println("\n모델 생성 성공: " + result.getTaskId());
            System.out.println("썸네일 URL: " + result.getThumbnailUrl());
            printModelUrls(result.getModelUrls());
        }
    }

    /**
     * 전체 3D 모델 생성 프로세스 (Preview + Refine)
     *
     * @param prompt        3D 모델 생성에 사용할 프롬프트
     * @param artStyle      아트 스타일 (realistic, cartoon, etc.)
     * @param texturePrompt 텍스처 생성에 사용할 프롬프트
     */
    public static void createFullModel(String prompt, String artStyle, String texturePrompt) {
        long fullStartTime = System.currentTimeMillis();
        try {
            System.out.println("========== 전체 3D 모델 생성 프로세스 시작 ==========");
            System.out.println("프롬프트: " + prompt);
            System.out.println("아트 스타일: " + artStyle);
            System.out.println("텍스처 프롬프트: " + texturePrompt);

            // 통합 로그에 시작 기록
            appendToLog("전체 3D 모델 생성 프로세스 시작 - 프롬프트: " + prompt + ", 스타일: " + artStyle + ", 텍스처: " + texturePrompt);

            // 1. Preview 모델 생성
            String previewTaskId = createPreviewModel(prompt, artStyle);
            if (previewTaskId == null) {
                System.err.println("Preview 모델 생성 실패");
                return;
            }

            // 2. Preview 작업 상태 확인
            MeshyTextTo3D.TaskStatus previewStatus = waitForTaskCompletion(previewTaskId, "preview", prompt);
            if (previewStatus == null || !"SUCCEEDED".equals(previewStatus.status)) {
                System.err.println("Preview 태스크가 실패했거나 완료되지 않았습니다.");
                return;
            }

            // 3. Refine 모델 생성
            String refineTaskId = createRefineModel(previewTaskId, texturePrompt, true);
            if (refineTaskId == null) {
                System.err.println("Refine 모델 생성 실패");
                return;
            }

            // 4. Refine 작업 상태 확인
            MeshyTextTo3D.TaskStatus refineStatus = waitForTaskCompletion(refineTaskId, "refine", texturePrompt, previewTaskId);
            if (refineStatus == null || !"SUCCEEDED".equals(refineStatus.status)) {
                System.err.println("Refine 태스크가 실패했거나 완료되지 않았습니다.");
                return;
            }

            // 5. 최종 결과 출력
            printModelResults(refineStatus);

            // 6. 완료된 결과를 데스크탑에 저장
            long totalTimeMs = System.currentTimeMillis() - fullStartTime;
            long totalTimeSec = totalTimeMs / 1000;
            long minutes = totalTimeSec / 60;
            long seconds = totalTimeSec % 60;
            String timeString = String.format("%d분 %d초", minutes, seconds);

            String fullPrompt = prompt + " (스타일: " + artStyle + ", 텍스처: " + texturePrompt + ")";
            saveMeshyTaskResultToDesktop(fullPrompt, refineStatus, "complete_model", totalTimeMs);

            String completionMessage = "전체 3D 모델 생성 프로세스 완료 - 총 소요 시간: " + timeString;
            System.out.println("========== " + completionMessage + " ==========");
            appendToLog(completionMessage + " - 프롬프트: " + prompt);

        } catch (Exception e) {
            System.err.println("3D 모델 생성 중 오류 발생: " + e);
        }
    }

    /**
     * Preview 모델만 생성하는 메소드
     *
     * @param prompt   3D 모델 생성에 사용할 프롬프트
     * @param artStyle 아트 스타일 (realistic, cartoon, etc.)
     * @return 생성된 Preview 태스크 ID, 실패 시 null
     */
    @Nullable
    public static String createPreviewModel(String prompt, String artStyle) {
        try {
            long startTime = System.currentTimeMillis();
            MeshyTextTo3D meshy = new MeshyTextTo3D();
            String previewTaskId = meshy.createPreviewTask(prompt, artStyle);

            String createMessage = "Preview 태스크 생성됨: " + previewTaskId +
                    " - 소요 시간: " + (System.currentTimeMillis() - startTime) + "ms";
            System.out.println(createMessage);
            appendToLog(createMessage + " - 프롬프트: " + prompt + ", 스타일: " + artStyle);

            // 태스크 추적 시작 (선택 사항)
            MeshyTaskTracker.trackPreviewTask(previewTaskId, prompt);

            return previewTaskId;
        } catch (Exception e) {
            System.err.println("Preview 모델 생성 실패: " + e);
            return null;
        }
    }

    /**
     * Refine 모델을 생성하는 메소드
     *
     * @param previewTaskId 기존 Preview 태스크 ID
     * @param texturePrompt 텍스처 생성에 사용할 프롬프트
     * @param enablePbr     PBR 텍스처 활성화 여부
     * @return 생성된 Refine 태스크 ID, 실패 시 null
     */
    @Nullable
    public static String createRefineModel(String previewTaskId, String texturePrompt, boolean enablePbr) {
        try {
            long startTime = System.currentTimeMillis();
            MeshyTextTo3D meshy = new MeshyTextTo3D();
            String refineTaskId = meshy.createRefineTask(previewTaskId, enablePbr, texturePrompt);

            String createMessage = "Refine 태스크 생성됨: " + refineTaskId +
                    " - 소요 시간: " + (System.currentTimeMillis() - startTime) + "ms";
            System.out.println(createMessage);
            appendToLog(createMessage + " - Preview ID: " + previewTaskId + ", 텍스처: " + texturePrompt);

            // 태스크 추적 시작 (선택 사항)
            MeshyTaskTracker.trackRefineTask(refineTaskId, previewTaskId, texturePrompt);

            return refineTaskId;
        } catch (Exception e) {
            System.err.println("Refine 모델 생성 실패: " + e);
            return null;
        }
    }

    /**
     * 태스크 완료를 기다리는 메소드
     *
     * @param taskId   태스크 ID
     * @param taskType 태스크 유형 ("preview" 또는 "refine")
     * @param prompt   사용된 프롬프트
     * @return 완료된 태스크 상태, 오류 발생 시 null
     */
    public static MeshyTextTo3D.TaskStatus waitForTaskCompletion(String taskId, String taskType, String prompt) {
        return waitForTaskCompletion(taskId, taskType, prompt, null);
    }

    /**
     * 태스크 완료를 기다리는 메소드 (Refine용 오버로드)
     *
     * @param taskId       태스크 ID
     * @param taskType     태스크 유형 ("preview" 또는 "refine")
     * @param prompt       사용된 프롬프트
     * @param parentTaskId Refine 태스크의 경우 연관된 Preview 태스크 ID
     * @return 완료된 태스크 상태, 오류 발생 시 null
     */
    @Nullable
    public static MeshyTextTo3D.TaskStatus waitForTaskCompletion(String taskId, String taskType,
                                                                 String prompt, String parentTaskId) {
        try {
            MeshyTextTo3D meshy = new MeshyTextTo3D();
            final long startTime = System.currentTimeMillis();
            final long[] lastLogTime = {startTime};
            final MeshyTextTo3D.TaskStatus[] finalStatus = {null};
            final Object lock = new Object();

            // 통합 로그에 태스크 시작 기록
            appendToLog(taskType.toUpperCase() + " 태스크 시작 - ID: " + taskId + ", 프롬프트: " + prompt);
            System.out.println(taskType.toUpperCase() + " 태스크 완료 대기 중: " + taskId);

            // 스트리밍 API를 통해 진행 상황 모니터링
            meshy.streamTaskProgress(taskId, new MeshyTextTo3D.TaskProgressCallback() {
                @Override
                public void onProgress(MeshyTextTo3D.TaskStatus status) {
                    long currentTime = System.currentTimeMillis();
                    long elapsedSeconds = (currentTime - startTime) / 1000;

                    // 진행 상황 출력 - 초 단위 경과 시간 표시
                    System.out.println(taskType.toUpperCase() + " 진행률: " + status.progress + "%, 경과 시간: " + elapsedSeconds + "초");

                    // 5초마다 로그 갱신
                    if (currentTime - lastLogTime[0] > 5000) { // 5초마다로 변경
                        lastLogTime[0] = currentTime;

                        // 통합 로그에 진행 상황 추가
                        String progressLog = taskType.toUpperCase() + " 태스크 진행 중 - ID: " + taskId +
                                ", 진행률: " + status.progress + "%, 경과 시간: " + elapsedSeconds + "초";
                        appendToLog(progressLog);

                        // 태스크 유형에 따라 로깅
                        if ("preview".equals(taskType)) {
                            logPreviewTaskStatus(taskId, status, prompt);
                        } else if ("refine".equals(taskType) && parentTaskId != null) {
                            logRefineTaskStatus(taskId, parentTaskId, status, prompt);
                        }

                        // 태스크 트래커 업데이트
                        MeshyTaskTracker.updateTaskStatus(taskId, status.status, status.progress);
                    }

                    // 작업 완료 시 상태 저장 및 대기 중인 스레드 깨우기
                    if ("SUCCEEDED".equals(status.status) || "FAILED".equals(status.status)) {
                        finalStatus[0] = status;
                        synchronized (lock) {
                            lock.notify();
                        }
                    }
                }

                @Override
                public void onError(Exception e) {
                    System.err.println(taskType.toUpperCase() + " 태스크 스트리밍 중 오류 발생: " + e.getMessage());
                    synchronized (lock) {
                        lock.notify();
                    }
                }
            });

            // 결과가 나올 때까지 기다림
            synchronized (lock) {
                try {
                    // 스트리밍 API가 응답하지 않는 경우를 대비한 최대 대기 시간(10분)
                    lock.wait(10 * 60 * 1000);
                } catch (InterruptedException e) {
                    System.err.println("태스크 대기 중 인터럽트 발생: " + e.getMessage());
                }
            }

            // 스트리밍 API가 실패하거나 응답이 없는 경우 기존 방식으로 상태 확인
            if (finalStatus[0] == null) {
                System.out.println("스트리밍 API에서 최종 상태를 얻지 못했습니다. 직접 상태를 확인합니다.");
                finalStatus[0] = meshy.getTaskStatus(taskId);
            }

            MeshyTextTo3D.TaskStatus status = finalStatus[0];

            // 소요 시간 계산
            long totalTimeMs = System.currentTimeMillis() - startTime;
            long totalTimeSec = totalTimeMs / 1000;
            long minutes = totalTimeSec / 60;
            long seconds = totalTimeSec % 60;
            String timeString = String.format("%d분 %d초", minutes, seconds);

            // 최종 상태 로깅 - 기존 메서드 사용
            if ("preview".equals(taskType)) {
                logPreviewTaskStatus(taskId, status, prompt);
            } else if ("refine".equals(taskType) && parentTaskId != null) {
                logRefineTaskStatus(taskId, parentTaskId, status, prompt);
            }

            if ("SUCCEEDED".equals(status.status)) {
                String successMessage = taskType.toUpperCase() + " 태스크 완료! - 소요 시간: " + timeString;
                System.out.println(successMessage);
                appendToLog(successMessage + " - ID: " + taskId);

                // 완료된 태스크 로깅 - MeshyLogService 사용
                com.febrie.service.MeshyLogService.logCompletedTask(taskId, status, taskType, prompt, totalTimeMs);

                // 완료된 태스크 결과를 데스크탑에 저장
                saveMeshyTaskResultToDesktop(prompt, status, taskType, totalTimeMs);
            } else {
                // 실패 시 가능한 많은 정보 수집
                String errorDetail = status.error != null ? status.error : "세부 오류 정보 없음";
                String failMessage = taskType.toUpperCase() + " 태스크 실패: " + status.status +
                        " - 소요 시간: " + timeString + " - 오류: " + errorDetail;

                System.err.println(failMessage);
                appendToLog(failMessage + " - ID: " + taskId, true);

                // 실패한 태스크 로깅 - MeshyLogService 사용
                com.febrie.service.MeshyLogService.logFailedTask(taskId, status, taskType, prompt, errorDetail);

                // 실패한 결과도 저장
                saveMeshyTaskResultToDesktop(prompt + " [실패]", status, taskType + "_failed", totalTimeMs);

                // 실패 원인 분석 시도
                try {
                    MeshyTextTo3D.TaskStatus latestStatus = meshy.getTaskStatus(taskId);
                    appendToLog("최종 태스크 상태 재확인: " + gson.toJson(latestStatus), true);
                } catch (Exception e) {
                    appendToLog("태스크 상태 재확인 실패: " + e.getMessage(), true);
                }
            }

            return status;

        } catch (Exception e) {
            System.err.println(taskType.toUpperCase() + " 태스크 대기 중 오류 발생: " + e);
            return null;
        }
    }

    /**
     * 모델 URL 정보를 출력하는 유틸리티 메소드
     *
     * @param modelUrls 모델 URL 정보 객체
     */
    public static void printModelUrls(MeshyTextTo3D.TaskStatus.ModelUrls modelUrls) {
        if (modelUrls != null) {
            System.out.println("GLB: " + modelUrls.glb);
            System.out.println("FBX: " + modelUrls.fbx);
            if (modelUrls.obj != null) System.out.println("OBJ: " + modelUrls.obj);
            if (modelUrls.mtl != null) System.out.println("MTL: " + modelUrls.mtl);
            if (modelUrls.usdz != null) System.out.println("USDZ: " + modelUrls.usdz);
        } else {
            System.out.println("모델 URL이 null입니다.");
        }
    }

    /**
     * 모델 결과를 출력하는 메소드
     *
     * @param status 완료된 태스크 상태
     */
    public static void printModelResults(@NotNull MeshyTextTo3D.TaskStatus status) {
        System.out.println("\n====== 모델 생성 결과 ======");

        printModelUrls(status.modelUrls);

        if (status.thumbnailUrl != null) {
            System.out.println("썸네일: " + status.thumbnailUrl);
        }
    }

    /**
     * 중단된 태스크를 확인하기 위한 메인 메소드
     * 태스크 ID를 알고 있을 때 사용
     */
    public static void main_checkInterrupted(String[] args) {
        // 중단된 태스크 ID를 입력하세요
        String previewTaskId = "YOUR_PREVIEW_TASK_ID";
        String refineTaskId = "YOUR_REFINE_TASK_ID"; // 없으면 null 또는 빈 문자열

        retrieveCompletedTaskResults(previewTaskId, refineTaskId);
    }

    /**
     * Firebase 로깅을 위한 초기화 메소드
     * Main 클래스에서 사용하는 Firebase 앱 인스턴스가 없을 경우 사용
     *
     * @param firebaseApp Firebase 앱 인스턴스
     */
    public static void initializeWithFirebaseApp(com.google.firebase.FirebaseApp firebaseApp) {
        if (firebaseApp != null) {
            System.out.println("[INFO] MeshyExample Firebase 앱 인스턴스 설정 완료");
        } else {
            System.err.println("[ERROR] 유효하지 않은 Firebase 앱 인스턴스입니다.");
        }
    }

    /**
     * 통합 로그 파일에 로그를 추가합니다.
     * 모든 태스크의 로그가 하나의 파일에 시간순으로 저장됩니다.
     * 또한 시간별 폴더를 생성하여 해당 폴더에도 동일한 로그를 저장합니다.
     *
     * @param logEntry 로그 항목
     */
    private static synchronized void appendToLog(String logEntry) {
        appendToLog(logEntry, false);
    }

    /**
     * 모델 URL 정보를 StringBuilder에 추가합니다.
     *
     * @param builder   추가할 StringBuilder 객체
     * @param modelUrls 모델 URL 정보 객체
     */
    private static void appendModelUrlsToStringBuilder(StringBuilder builder, MeshyTextTo3D.TaskStatus.ModelUrls modelUrls) {
        if (modelUrls != null) {
            if (modelUrls.glb != null) builder.append("GLB: ").append(modelUrls.glb).append("\n");
            if (modelUrls.fbx != null) builder.append("FBX: ").append(modelUrls.fbx).append("\n");
            if (modelUrls.obj != null) builder.append("OBJ: ").append(modelUrls.obj).append("\n");
            if (modelUrls.mtl != null) builder.append("MTL: ").append(modelUrls.mtl).append("\n");
            if (modelUrls.usdz != null) builder.append("USDZ: ").append(modelUrls.usdz).append("\n");
        } else {
            builder.append("모델 URL 정보가 없습니다.\n");
        }
    }

    /**
     * 디버그 폴더에 태스크의 자세한 정보와 요약 정보를 모두 저장합니다.
     *
     * @param taskId   태스크 ID
     * @param taskType 태스크 유형
     * @param status   태스크 상태 객체
     * @param summary  요약 정보 맵
     */
    private static void saveTaskDetailsToDebugFolder(String taskId, String taskType,
                                                     MeshyTextTo3D.TaskStatus status, Map<String, Object> summary) {
        try {
            String timestamp = LocalDateTime.now().format(dateFormatter);
            Files.createDirectories(Paths.get(DEBUG_FOLDER));

            // 요약 정보 저장
            String summaryFilename = String.format("%s/%s_task_%s_summary_%s.json",
                    DEBUG_FOLDER, taskType, taskId, timestamp);
            try (FileWriter writer = new FileWriter(summaryFilename)) {
                gson.toJson(summary, writer);
            }

            // 전체 상태 정보 저장
            String detailFilename = String.format("%s/%s_task_%s_detail_%s.json",
                    DEBUG_FOLDER, taskType, taskId, timestamp);
            try (FileWriter writer = new FileWriter(detailFilename)) {
                gson.toJson(status, writer);
            }

            System.out.println("[DEBUG] " + taskType.toUpperCase() + " 태스크 정보 저장 완료: " + summaryFilename + ", " + detailFilename);
        } catch (IOException e) {
            System.err.println("[ERROR] 태스크 세부 정보 저장 실패: " + e.getMessage());
        }
    }

    /**
     * 태스크 로깅에 사용되는 추가 데이터 맵을 생성합니다.
     *
     * @param taskId  태스크 ID
     * @param status  태스크 상태
     * @param prompt  사용된 프롬프트 (없으면 null)
     * @return 기본 데이터가 포함된 Map 객체
     */
    private static Map<String, Object> createAdditionalDataMap(String taskId, MeshyTextTo3D.TaskStatus status, String prompt) {
        Map<String, Object> data = new HashMap<>();
        data.put("task_id", taskId);
        data.put("status", status.status);
        data.put("progress", status.progress);
        data.put("timestamp", LocalDateTime.now().toString());

        if (prompt != null) {
            data.put("prompt", prompt);
        }

        return data;
    }

    private static synchronized void appendToLog(String logEntry, boolean isError) {
        try {
            // 로그 폴더가 없으면 생성
            com.febrie.util.FileManager fileManager = com.febrie.util.FileManager.getInstance();
            fileManager.createDirectory(DESKTOP_OUTPUT_FOLDER);

            // 현재 타임스탬프 생성
            LocalDateTime now = LocalDateTime.now();
            String timestamp = now.format(dateFormatter);
            String dateFolderName = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm"));

            // 시간별 폴더 경로 설정
            String timestampFolder = DESKTOP_OUTPUT_FOLDER + "/" + dateFolderName;
            fileManager.createDirectory(timestampFolder);

            // 로그 파일 내용 준비
            String prefix = isError ? "[ERROR] " : "[INFO] ";
            String formattedEntry = "\n[" + timestamp + "] " + prefix + logEntry + "\n";

            // 메인 로그 파일에 추가 (파일이 없으면 생성)
            Path mainLogFile = Paths.get(UNIFIED_LOG_FILE);
            if (!Files.exists(mainLogFile)) {
                Files.writeString(mainLogFile, "=== MESHY TASK LOG - STARTED AT " + timestamp + " ===\n",
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            }

            // 메인 로그 파일에 로그 추가
            Files.writeString(mainLogFile, formattedEntry, StandardOpenOption.APPEND);

            // 시간별 폴더에 로그 파일 생성/추가
            String timestampLogFileName = timestampFolder + "/meshy_log.txt";
            Path timestampLogFile = Paths.get(timestampLogFileName);

            if (!Files.exists(timestampLogFile)) {
                Files.writeString(timestampLogFile, "=== MESHY TASK LOG - STARTED AT " + timestamp + " ===\n",
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            }

            // 시간별 로그 파일에 로그 추가
            Files.writeString(timestampLogFile, formattedEntry, StandardOpenOption.APPEND);

            // 로그가 추가된 경로를 콘솔에 출력
            System.out.println("[DEBUG] 로그가 저장됨: " + timestampLogFileName);

        } catch (IOException e) {
            System.err.println("[ERROR] 로그 파일 작성 실패: " + e.getMessage());
        }
    }
}
