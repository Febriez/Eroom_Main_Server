package com.febrie.api;

import com.febrie.config.PathConfig;
import com.febrie.service.MeshyLogService;
import com.febrie.util.FirebaseManager;
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

/**
 * Meshy AI 3D 모델 생성 예제 및 태스크 관리 유틸리티
 * <p>
 * 이 클래스는 Meshy API를 통해 3D 모델을 생성하고 관리하는 기본 예제와 유틸리티 메소드를 제공합니다.
 * 복잡한 로깅 및 모니터링 기능은 MeshyLogService와 MeshyTaskTracker로 이관되었습니다.
 */
public class MeshyExample {
    // 경로 설정
    private static final PathConfig pathConfig = PathConfig.getInstance();
    private static final String DEBUG_FOLDER = pathConfig.getDebugDirectory();
    private static final String DESKTOP_OUTPUT_FOLDER = pathConfig.getBaseDirectory();
    private static final String UNIFIED_LOG_FILE = pathConfig.getUnifiedLogFile();
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    /**
     * 태스크의 현재 상태를 로컬 파일 및 Firebase에 저장합니다.
     * 로깅 로직을 추상화하여 중복 코드를 제거합니다.
     *
     * @param taskId       태스크 ID
     * @param status       현재 태스크 상태
     * @param taskType     태스크 유형 ("preview" 또는 "refine")
     * @param prompt       생성에 사용된 프롬프트
     * @param parentTaskId 부모 태스크 ID (refine 태스크의 경우에만 사용)
     */
    public static void logTaskStatus(String taskId, @NotNull MeshyTextTo3D.TaskStatus status,
                                     String taskType, String prompt, String parentTaskId) {
        try {
            // MeshyLogService를 활용하여 로깅 처리
            com.febrie.service.MeshyLogService.logTaskStatus(taskId, status, taskType, prompt, parentTaskId);
        } catch (Exception e) {
            // 오류 로깅만 남김
        }
    }

    /**
     * Preview 태스크의 현재 상태를 로컬 파일 및 Firebase에 저장합니다.
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
     * 태스크 ID로 중단된 작업의 상태를 확인합니다.
     *
     * @param taskId        확인할 태스크 ID (null이 아니어야 함)
     * @param taskType      태스크 유형 ("preview" 또는 "refine")
     * @param texturePrompt 텍스처 생성에 사용된 프롬프트 (refine 태스크인 경우에만 사용, 없으면 null)
     */
    public static void checkInterruptedTask(String taskId, String taskType, String texturePrompt) {
        if (taskId == null || taskId.isEmpty()) {
            return;
        }

        try {
            MeshyTextTo3D meshy = new MeshyTextTo3D();
            MeshyTextTo3D.TaskStatus status = meshy.getTaskStatus(taskId);

            if ("SUCCEEDED".equals(status.status)) {
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

                String promptToUse = taskType.equals("preview") ?
                        "Preview task" : (texturePrompt != null ? texturePrompt : "Refine task");

                // Firebase 로깅 - MeshyLogService 사용
                MeshyLogService.logCompletedTask(taskId, status, taskType, promptToUse, 0);
            }
        } catch (Exception e) {
            // 오류 로깅
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
                checkInterruptedTask(previewTaskId, "preview", null);
            }

            // Refine 태스크가 있으면 확인
            if (refineTaskId != null && !refineTaskId.isEmpty()) {
                checkInterruptedTask(refineTaskId, "refine", null); // 텍스처 프롬프트 정보가 없으므로 null 전달
            }
        } catch (Exception e) {
            // 오류 로깅
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
        } catch (Exception e) {
            // 오류 로깅
        }
    }


    /**
     * 전체 3D 모델 생성 프로세스 (Preview + Refine)
     *
     * @param prompt        3D 모델 생성에 사용할 프롬프트
     * @param artStyle      아트 스타일 (realistic, cartoon, etc.)
     * @param texturePrompt 텍스처 생성에 사용할 프롬프트
     * @return 생성된 refine 태스크 ID, 실패 시 null
     */
    public static String createFullModel(String prompt, String artStyle, String texturePrompt) {
        long fullStartTime = System.currentTimeMillis();
        try {
            // 통합 로그에 시작 기록
            appendToLog("전체 3D 모델 생성 프로세스 시작 - 프롬프트: " + prompt + ", 스타일: " + artStyle + ", 텍스처: " + texturePrompt);

            // 1. Preview 모델 생성
            String previewTaskId = createPreviewModel(prompt, artStyle);
            if (previewTaskId == null) {
                return null;
            }

            // 2. Preview 작업 상태 확인
            MeshyTextTo3D.TaskStatus previewStatus = waitForTaskCompletion(previewTaskId, "preview", prompt);
            if (previewStatus == null || !"SUCCEEDED".equals(previewStatus.status)) {
                return null;
            }

            // 3. Refine 모델 생성
            String refineTaskId = createRefineModel(previewTaskId, texturePrompt, true);
            if (refineTaskId == null) {
                return null;
            }

            // 4. Refine 작업 상태 확인
            MeshyTextTo3D.TaskStatus refineStatus = waitForTaskCompletion(refineTaskId, "refine", texturePrompt, previewTaskId);
            if (refineStatus == null || !"SUCCEEDED".equals(refineStatus.status)) {
                return null;
            }

            // 5. 완료된 결과를 데스크탑에 저장
            long totalTimeMs = System.currentTimeMillis() - fullStartTime;
            String fullPrompt = prompt + " (스타일: " + artStyle + ", 텍스처: " + texturePrompt + ")";
            saveMeshyTaskResultToDesktop(fullPrompt, refineStatus, "complete_model", totalTimeMs);

            long totalTimeSec = totalTimeMs / 1000;
            long minutes = totalTimeSec / 60;
            long seconds = totalTimeSec % 60;
            String timeString = String.format("%d분 %d초", minutes, seconds);
            String completionMessage = "전체 3D 모델 생성 프로세스 완료 - 총 소요 시간: " + timeString;
            appendToLog(completionMessage + " - 프롬프트: " + prompt);

            return refineTaskId;

        } catch (Exception e) {
            return null;
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

            // 로그에만 기록
            String createMessage = "Preview 태스크 생성됨: " + previewTaskId +
                    " - 소요 시간: " + (System.currentTimeMillis() - startTime) + "ms";
            appendToLog(createMessage + " - 프롬프트: " + prompt + ", 스타일: " + artStyle);

            return previewTaskId;
        } catch (Exception e) {
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
            appendToLog(createMessage + " - Preview ID: " + previewTaskId + ", 텍스처: " + texturePrompt);

            return refineTaskId;
        } catch (Exception e) {
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

            // 통합 로그에 태스크 시작 기록
            appendToLog(taskType.toUpperCase() + " 태스크 시작 - ID: " + taskId + ", 프롬프트: " + prompt);

            // 폴링 방식으로 상태 확인
            MeshyTextTo3D.TaskStatus status = null;
            boolean isCompleted = false;
            int retryCount = 0;
            int maxRetry = 60; // 최대 30분 (30초 간격으로 60회)

            while (!isCompleted && retryCount < maxRetry) {
                status = meshy.getTaskStatus(taskId);

                // 태스크 유형에 따라 로깅
                if ("preview".equals(taskType)) {
                    logPreviewTaskStatus(taskId, status, prompt);
                } else if ("refine".equals(taskType) && parentTaskId != null) {
                    logRefineTaskStatus(taskId, parentTaskId, status, prompt);
                }

                // 완료 또는 실패 시 루프 종료
                if ("SUCCEEDED".equals(status.status) || "FAILED".equals(status.status)) {
                    isCompleted = true;
                } else {
                    // 30초 대기 후 다시 확인
                    try {
                        Thread.sleep(30000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    retryCount++;
                }
            }

            // 소요 시간 계산
            long totalTimeMs = System.currentTimeMillis() - startTime;
            long totalTimeSec = totalTimeMs / 1000;
            long minutes = totalTimeSec / 60;
            long seconds = totalTimeSec % 60;
            String timeString = String.format("%d분 %d초", minutes, seconds);

            // 최종 상태 로깅 - MeshyLogService 사용
            MeshyLogService.logTaskStatus(taskId, status, taskType, prompt, parentTaskId);

            if ("SUCCEEDED".equals(status.status)) {
                String successMessage = taskType.toUpperCase() + " 태스크 완료! - 소요 시간: " + timeString;
                appendToLog(successMessage + " - ID: " + taskId);

                // 완료된 태스크 로깅
                MeshyLogService.logCompletedTask(taskId, status, taskType, prompt, totalTimeMs);

                // 완료된 태스크 결과를 데스크탑에 저장
                saveMeshyTaskResultToDesktop(prompt, status, taskType, totalTimeMs);
            } else {
                // 실패 처리
                String errorDetail = status.error != null ? status.error : "세부 오류 정보 없음";
                String failMessage = taskType.toUpperCase() + " 태스크 실패: " + status.status + " - 소요 시간: " + timeString;
                appendToLog(failMessage + " - ID: " + taskId, true);

                // 실패한 태스크 로깅
                MeshyLogService.logFailedTask(taskId, status, taskType, prompt, errorDetail);
            }

            return status;
        } catch (Exception e) {
            return null;
        }
    }



    /**
     * Firebase 로깅을 위한 초기화 메소드
     * Main 클래스에서 사용하는 Firebase 앱 인스턴스가 없을 경우 사용
     *
     * @param firebaseApp Firebase 앱 인스턴스
     */
    public static void initializeWithFirebaseApp(com.google.firebase.FirebaseApp firebaseApp) {
        if (firebaseApp == null) {
            // FirebaseManager를 통해 초기화 시도
            try {
                firebaseApp = FirebaseManager.getInstance().getFirebaseApp();
            } catch (Exception e) {
                // 오류 로깅
            }
        }
    }

    /**
     * 통합 로그 파일에 로그를 추가합니다.
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


    private static synchronized void appendToLog(String logEntry, boolean isError) {
        try {
            // 로그 폴더 생성
            com.febrie.util.FileManager fileManager = com.febrie.util.FileManager.getInstance();
            fileManager.createDirectory(DESKTOP_OUTPUT_FOLDER);

            // 타임스탬프 생성
            String timestamp = LocalDateTime.now().format(dateFormatter);
            String prefix = isError ? "[ERROR] " : "[INFO] ";
            String formattedEntry = "\n[" + timestamp + "] " + prefix + logEntry + "\n";

            // 로그 파일에 기록
            Path mainLogFile = Paths.get(UNIFIED_LOG_FILE);
            if (!Files.exists(mainLogFile)) {
                Files.createDirectories(mainLogFile.getParent());
                Files.writeString(mainLogFile, "=== MESHY TASK LOG - STARTED AT " + timestamp + " ===\n",
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            }

            Files.writeString(mainLogFile, formattedEntry, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("[ERROR] 로그 파일 작성 실패: " + e.getMessage());
        }
    }
}
