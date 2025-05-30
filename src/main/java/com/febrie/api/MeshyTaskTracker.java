
package com.febrie.api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import lombok.Getter;
import lombok.Setter;

import java.io.File;
import java.io.FileReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Meshy AI 태스크의 상태를 추적하고 디버깅하기 위한 유틸리티 클래스
 */
public class MeshyTaskTracker {

    private static final String DESKTOP_FOLDER = "C:\\Users\\201-11\\Desktop\\Meshy_Data";
    private static final DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final DateTimeFormatter logDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String TRACKER_FOLDER = DESKTOP_FOLDER + "/meshy_tracker";
    private static final String MONITOR_FOLDER = DESKTOP_FOLDER + "/task_monitor";
    private static final String TASKS_FILE = TRACKER_FOLDER + "/active_tasks.json";
    private static final String LOG_FILE = DESKTOP_FOLDER + "/meshy_task_log.txt";
    private static final String DEBUG_LOG_FILE = DESKTOP_FOLDER + "/meshy_debug.log";
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private static final com.febrie.util.FileManager fileManager = com.febrie.util.FileManager.getInstance();

    // 실시간 활성 태스크 컬렉션 (동시성 지원)
    private static final ConcurrentHashMap<String, TaskInfo> activeTasks = new ConcurrentHashMap<>();
    private static final CopyOnWriteArrayList<String> completedTaskIds = new CopyOnWriteArrayList<>();

    // 최대 재시도 횟수와 체크 간격 설정
    private static final int MAX_RETRY_COUNT = 60; // 30분 (30초 간격으로 60회)
    private static final int CHECK_INTERVAL_MS = 30000; // 30초

    /**
     * 활성 태스크 정보를 저장하는 클래스
     */
    @Getter
    @Setter
    public static class TaskInfo {
        private String taskId;
        private String taskType; // "preview" 또는 "refine"
        private String parentTaskId; // refine 태스크의 경우 preview 태스크 ID
        private String prompt;
        private String status;
        private int progress;
        private String createdAt;
        private String lastCheckedAt;

        public TaskInfo() {
            // 기본 생성자
        }

        public TaskInfo(String taskId, String taskType, String prompt) {
            this.taskId = taskId;
            this.taskType = taskType;
            this.prompt = prompt;
            this.status = "PENDING";
            this.progress = 0;
            this.createdAt = LocalDateTime.now().format(dateFormatter);
            this.lastCheckedAt = this.createdAt;
        }

        public TaskInfo(String taskId, String taskType, String parentTaskId, String prompt) {
            this(taskId, taskType, prompt);
            this.parentTaskId = parentTaskId;
        }
    }

    /**
     * 새로운 Preview 태스크를 추적 목록에 추가합니다.
     *
     * @param taskId Preview 태스크 ID
     * @param prompt 사용된 프롬프트
     */
    public static void trackPreviewTask(String taskId, String prompt) {
        TaskInfo task = new TaskInfo(taskId, "preview", prompt);
        addTaskToTracker(task);
        logConsole("Preview 태스크 추적 시작 - ID: " + taskId + ", 프롬프트: " + prompt);
    }

    /**
     * 새로운 Refine 태스크를 추적 목록에 추가합니다.
     *
     * @param taskId        Refine 태스크 ID
     * @param previewTaskId 연관된 Preview 태스크 ID
     * @param texturePrompt 텍스처 생성에 사용된 프롬프트
     */
    public static void trackRefineTask(String taskId, String previewTaskId, String texturePrompt) {
        TaskInfo task = new TaskInfo(taskId, "refine", previewTaskId, texturePrompt);
        addTaskToTracker(task);
        logConsole("Refine 태스크 추적 시작 - ID: " + taskId + ", Preview ID: " + previewTaskId + ", 텍스처: " + texturePrompt);
    }

    /**
     * 태스크의 상태를 업데이트합니다.
     *
     * @param taskId   업데이트할 태스크 ID
     * @param status   새 상태
     * @param progress 새 진행률
     */
    public static void updateTaskStatus(String taskId, String status, int progress) {
        TaskInfo task = activeTasks.get(taskId);
        if (task != null) {
            task.setStatus(status);
            task.setProgress(progress);
            task.setLastCheckedAt(LocalDateTime.now().format(dateFormatter));
            logConsole("태스크 상태 업데이트 - ID: " + taskId + ", 상태: " + status + ", 진행률: " + progress + "%");

            // 태스크가 완료됐으면 activeTasks에서 제거하고 completedTaskIds에 추가
            if ("SUCCEEDED".equals(status) || "FAILED".equals(status)) {
                completedTaskIds.add(taskId);
                logConsole("태스크 완료 - ID: " + taskId + ", 최종 상태: " + status);
            }
        } else {
            logConsole("[경고] 알 수 없는 태스크 ID: " + taskId + " - 추적 목록에 없습니다.");
        }

        // 메모리 내 변경사항을 파일에 저장
        saveTasks(new ArrayList<>(activeTasks.values()));
    }

    /**
     * 모든 추적 중인 태스크의 상태를 확인합니다.
     *
     * @return 확인 후 남아있는 활성 태스크의 개수
     */
    public static int checkAllActiveTasksAndGetCount() {
        logConsole("\n====== 모든 활성 태스크 확인 시작 ======");

        // 현재 활성 태스크 목록을 복사해서 작업
        List<TaskInfo> tasksToCheck = new ArrayList<>(activeTasks.values());
        logConsole("확인할 태스크 수: " + tasksToCheck.size());

        if (tasksToCheck.isEmpty()) {
            logConsole("확인할 활성 태스크가 없습니다.");
            return 0;
        }

        // 각 태스크 상태 확인
        for (TaskInfo task : tasksToCheck) {
            try {
                MeshyTextTo3D meshy = new MeshyTextTo3D();
                MeshyTextTo3D.TaskStatus status = meshy.getTaskStatus(task.getTaskId());

                if (status != null) {
                    logConsole("태스크 확인 - ID: " + task.getTaskId() + ", 유형: " + task.getTaskType() +
                            ", 상태: " + status.status + ", 진행률: " + status.progress + "%");

                    // 태스크 상태 업데이트
                    updateTaskStatus(task.getTaskId(), status.status, status.progress);

                    // 태스크가 완료됐으면 처리
                    if ("SUCCEEDED".equals(status.status) || "FAILED".equals(status.status)) {
                        // 태스크가 완료됐으면 활성 목록에서 제거
                        activeTasks.remove(task.getTaskId());
                        saveCompletedTaskDetails(task.getTaskId(), status);
                    }
                } else {
                    logConsole("[경고] 태스크 상태 조회 실패 - ID: " + task.getTaskId());
                }
            } catch (Exception e) {
                logConsole("[오류] 태스크 확인 중 예외 발생 - ID: " + task.getTaskId() + " - " + e.getMessage());
            }
        }

        // 활성 태스크 수 반환
        int activeCount = activeTasks.size();
        logConsole("====== 모든 활성 태스크 확인 완료 - 남은 활성 태스크: " + activeCount + " ======\n");
        return activeCount;
    }

    /**
     * 모든 추적 중인 태스크의 상태를 확인합니다.
     * 이전 버전과의 호환성을 위해 유지됩니다.
     */
    public static void checkAllActiveTasks() {
        checkAllActiveTasksAndGetCount();
    }

    /**
     * 완료된 태스크의 세부 정보를 저장합니다.
     *
     * @param taskId 태스크 ID
     * @param status 태스크 상태 객체
     */
    private static void saveCompletedTaskDetails(String taskId, MeshyTextTo3D.TaskStatus status) {
        try {
            // 태스크 정보 가져오기
            TaskInfo task = findTaskById(taskId);
            if (task == null) {
                logConsole("[경고] 완료된 태스크 세부 정보 저장 실패 - ID: " + taskId + " - 태스크 정보를 찾을 수 없습니다.");
                return;
            }

            // 결과 저장 폴더 생성
            String completedFolder = TRACKER_FOLDER + "/completed_tasks";
            fileManager.createDirectory(completedFolder);

            // 태스크 세부 정보를 JSON으로 저장
            String timestamp = LocalDateTime.now().format(dateFormatter);
            String taskTypeStr = task.getTaskType() != null ? task.getTaskType() : "unknown";
            String filename = String.format("%s/task_%s_%s_%s.json", completedFolder, taskTypeStr, taskId, timestamp);

            // 태스크 세부 정보 및 결과 정보 저장
            Map<String, Object> taskDetails = new HashMap<>();
            taskDetails.put("task_id", taskId);
            taskDetails.put("task_type", task.getTaskType());
            taskDetails.put("parent_task_id", task.getParentTaskId());
            taskDetails.put("prompt", task.getPrompt());
            taskDetails.put("status", status.status);
            taskDetails.put("progress", status.progress);
            taskDetails.put("created_at", task.getCreatedAt());
            taskDetails.put("completed_at", timestamp);
            taskDetails.put("model_urls", status.modelUrls);
            taskDetails.put("thumbnail_url", status.thumbnailUrl);
            taskDetails.put("task_status", status);

            // JSON 저장
            fileManager.createFile(filename, gson.toJson(taskDetails));
            logConsole("완료된 태스크 세부 정보 저장 완료 - ID: " + taskId + " - 파일: " + filename);

            // 모니터링 파일에도 복사 (선택 사항)
            if (task.getPrompt() != null) {
                String monitorFile = MONITOR_FOLDER + "/" + taskId + "_result.json";
                fileManager.createFile(monitorFile, gson.toJson(taskDetails));
            }
        } catch (Exception e) {
            logConsole("[오류] 완료된 태스크 세부 정보 저장 실패 - ID: " + taskId + " - " + e.getMessage());
        }
    }

    /**
     * 특정 태스크 ID에 대한 태스크만 확인하고 모니터링합니다.
     * 메인 메서드에서 특정 태스크 ID를 지정할 때 사용합니다.
     *
     * @param taskId 확인할 태스크 ID
     */
    public static void checkSpecificTask(String taskId) {
        logConsole("\n====== 특정 태스크 확인 시작 - ID: " + taskId + " ======");

        try {
            // 태스크 확인
            MeshyTextTo3D meshy = new MeshyTextTo3D();
            MeshyTextTo3D.TaskStatus status = meshy.getTaskStatus(taskId);

            if (status != null) {
                // 태스크 정보 출력
                logConsole("태스크 정보 - ID: " + taskId +
                        ", 상태: " + status.status + ", 진행률: " + status.progress + "%");

                // 태스크가 활성 상태가 아니면 완료된 태스크로 처리
                if ("SUCCEEDED".equals(status.status) || "FAILED".equals(status.status)) {
                    // 결과 정보 출력
                    if ("SUCCEEDED".equals(status.status) && status.modelUrls != null) {
                        logConsole("태스크 결과 - GLB: " + (status.modelUrls.glb != null ? status.modelUrls.glb : "없음"));
                        logConsole("태스크 결과 - FBX: " + (status.modelUrls.fbx != null ? status.modelUrls.fbx : "없음"));
                        logConsole("태스크 결과 - 썸네일: " + (status.thumbnailUrl != null ? status.thumbnailUrl : "없음"));
                    } else if ("FAILED".equals(status.status)) {
                        logConsole("태스크 실패 - 오류: " + (status.error != null ? status.error : "알 수 없는 오류"));
                    }

                    // 세부 정보 저장
                    TaskInfo taskInfo = new TaskInfo(taskId, "unknown", "알 수 없는 프롬프트");
                    activeTasks.put(taskId, taskInfo);
                    saveCompletedTaskDetails(taskId, status);
                    activeTasks.remove(taskId);
                    completedTaskIds.add(taskId);
                } else {
                    // 활성 태스크인 경우 모니터링 시작
                    TaskInfo taskInfo = new TaskInfo(taskId, "unknown", "알 수 없는 프롬프트");
                    taskInfo.setStatus(status.status);
                    taskInfo.setProgress(status.progress);
                    activeTasks.put(taskId, taskInfo);
                    saveTasks(new ArrayList<>(activeTasks.values()));
                    logConsole("태스크가 아직 활성 상태입니다. 모니터링을 시작합니다.");
                    monitorTask(taskId);
                }
            } else {
                logConsole("[경고] 태스크 정보를 가져올 수 없습니다 - ID: " + taskId);
            }
        } catch (Exception e) {
            logConsole("[오류] 태스크 확인 중 예외 발생 - ID: " + taskId + " - " + e.getMessage());
        }

        logConsole("====== 특정 태스크 확인 완료 - ID: " + taskId + " ======\n");
    }

    /**
     * 태스크 ID로 태스크 정보를 찾습니다.
     *
     * @param taskId 찾을 태스크 ID
     * @return 태스크 정보 객체, 없으면 null
     */
    public static TaskInfo findTaskById(String taskId) {
        // 활성 태스크에서 먼저 찾기
        TaskInfo task = activeTasks.get(taskId);
        if (task != null) return task;

        // 저장된 태스크에서 찾기
        List<TaskInfo> savedTasks = loadTasks();
        if (savedTasks != null) {
            for (TaskInfo savedTask : savedTasks) {
                if (taskId.equals(savedTask.getTaskId())) {
                    return savedTask;
                }
            }
        }

        return null;
    }

    /**
     * 태스크 상태를 API에서 강제로 다시 가져와 동기화합니다.
     * 트래커와 실제 API 간 상태 불일치가 발생했을 때 사용합니다.
     *
     * @param taskId 동기화할 태스크 ID
     * @return 동기화 성공 여부
     */
    public static boolean forceResyncTaskStatus(String taskId) {
        logConsole("태스크 상태 강제 동기화 시작 - ID: " + taskId);

        try {
            MeshyTextTo3D meshy = new MeshyTextTo3D();
            MeshyTextTo3D.TaskStatus status = meshy.getTaskStatus(taskId);

            if (status != null) {
                TaskInfo task = findTaskById(taskId);
                if (task != null) {
                    // 태스크 정보 업데이트
                    task.setStatus(status.status);
                    task.setProgress(status.progress);
                    task.setLastCheckedAt(LocalDateTime.now().format(dateFormatter));
                    logConsole("태스크 상태 동기화 완료 - ID: " + taskId + ", 상태: " + status.status + ", 진행률: " + status.progress + "%");

                    // 태스크가 완료됐으면 처리
                    if ("SUCCEEDED".equals(status.status) || "FAILED".equals(status.status)) {
                        activeTasks.remove(taskId);
                        completedTaskIds.add(taskId);
                        saveCompletedTaskDetails(taskId, status);
                    } else {
                        // 아직 진행 중이면 활성 태스크 목록에 유지
                        activeTasks.put(taskId, task);
                    }

                    // 변경사항 저장
                    saveTasks(new ArrayList<>(activeTasks.values()));
                    return true;
                } else {
                    // 태스크 정보가 없는 경우 새로 생성
                    TaskInfo newTask = new TaskInfo(taskId, "unknown", "Unknown Prompt");
                    newTask.setStatus(status.status);
                    newTask.setProgress(status.progress);
                    activeTasks.put(taskId, newTask);
                    saveTasks(new ArrayList<>(activeTasks.values()));
                    logConsole("새 태스크 정보 생성 및 동기화 완료 - ID: " + taskId);
                    return true;
                }
            } else {
                logConsole("[경고] 태스크 상태 동기화 실패 - API 응답 없음 - ID: " + taskId);
                return false;
            }
        } catch (Exception e) {
            logConsole("[오류] 태스크 상태 동기화 중 예외 발생 - ID: " + taskId + " - " + e.getMessage());
            return false;
        }
    }

    /**
     * 태스크를 추적 목록에 추가합니다.
     *
     * @param task 추가할 태스크 정보
     */
    private static void addTaskToTracker(TaskInfo task) {
        // 메모리에 추가
        activeTasks.put(task.getTaskId(), task);

        // 초기 폴더 생성
        fileManager.createDirectory(TRACKER_FOLDER);
        fileManager.createDirectory(MONITOR_FOLDER);

        // 파일에 저장
        List<TaskInfo> tasks = loadTasks();
        if (tasks == null) tasks = new ArrayList<>();
        tasks.add(task);
        saveTasks(tasks);
    }

    /**
     * 추적 중인 태스크 목록을 로드합니다.
     *
     * @return 태스크 목록, 파일이 없거나 오류 발생 시 null
     */
    private static List<TaskInfo> loadTasks() {
        try {
            File file = new File(TASKS_FILE);
            if (!file.exists()) {
                return new ArrayList<>();
            }

            try (FileReader reader = new FileReader(file)) {
                TaskList taskList = gson.fromJson(reader, TaskList.class);
                return taskList != null ? taskList.tasks : new ArrayList<>();
            }
        } catch (Exception e) {
            logConsole("[오류] 태스크 목록 로드 실패: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 추적 중인 태스크 목록을 저장합니다.
     *
     * @param tasks 저장할 태스크 목록
     */
    private static void saveTasks(List<TaskInfo> tasks) {
        try {
            fileManager.createDirectory(TRACKER_FOLDER);

            // 완료된 태스크 필터링
            List<TaskInfo> activeTasks = tasks.stream()
                    .filter(t -> !completedTaskIds.contains(t.getTaskId()))
                    .collect(Collectors.toList());

            TaskList taskList = new TaskList();
            taskList.tasks = activeTasks;
            fileManager.createFile(TASKS_FILE, gson.toJson(taskList));
        } catch (Exception e) {
            logConsole("[오류] 태스크 목록 저장 실패: " + e.getMessage());
        }
    }

    /**
     * JSON 직렬화를 위한 래퍼 클래스
     */
    private static class TaskList {
        private List<TaskInfo> tasks = new ArrayList<>();
    }

    /**
     * 특정 태스크를 지속적으로 모니터링하는 메서드 (기본 설정)
     *
     * @param taskId 모니터링할 태스크 ID
     */
    public static void monitorTask(String taskId) {
        monitorTask(taskId, false);
    }

    /**
     * 특정 태스크를 지속적으로 모니터링하는 메서드
     *
     * @param taskId             모니터링할 태스크 ID
     * @param useTimestampFolder 시간별 폴더에 로그 저장 여부
     */
    public static void monitorTask(String taskId, boolean useTimestampFolder) {
        logConsole("\n====== 태스크 모니터링 시작 - ID: " + taskId + " ======");

        // 모니터링 폴더 생성
        fileManager.createDirectory(MONITOR_FOLDER);

        // 시간별 폴더 설정
        String timestampStr = LocalDateTime.now().format(dateFormatter);
        String logFolder = MONITOR_FOLDER;
        String taskLogFile;

        if (useTimestampFolder) {
            logFolder = MONITOR_FOLDER + "/" + timestampStr;
            fileManager.createDirectory(logFolder);
            taskLogFile = logFolder + "/task_" + taskId + "_monitor.log";
        } else {
            taskLogFile = MONITOR_FOLDER + "/task_" + taskId + "_monitor.log";
        }

        // 초기 로그 생성
        String initialLog = "====== 태스크 모니터링 시작 - " + timestampStr + " ======\n";
        initialLog += "태스크 ID: " + taskId + "\n\n";

        fileManager.createFile(taskLogFile, initialLog);
        logConsole("모니터링 로그 파일 생성: " + taskLogFile);

        // 초기 상태 확인
        try {
            MeshyTextTo3D meshy = new MeshyTextTo3D();
            MeshyTextTo3D.TaskStatus initialStatus = meshy.getTaskStatus(taskId);

            if (initialStatus != null) {
                String statusLog = "초기 상태:\n";
                statusLog += "- 상태: " + initialStatus.status + "\n";
                statusLog += "- 진행률: " + initialStatus.progress + "%\n";

                // 로그 파일에 기록
                appendToLogFile(taskLogFile, statusLog);
                logConsole("초기 상태 - 상태: " + initialStatus.status + ", 진행률: " + initialStatus.progress + "%");

                // 이미 완료된 상태면 결과 기록 후 종료
                if ("SUCCEEDED".equals(initialStatus.status) || "FAILED".equals(initialStatus.status)) {
                    String resultLog = "\n====== 태스크가 이미 완료됨 ======\n";
                    resultLog += "최종 상태: " + initialStatus.status + "\n";

                    if ("SUCCEEDED".equals(initialStatus.status) && initialStatus.modelUrls != null) {
                        resultLog += "\n모델 URL:\n";
                        if (initialStatus.modelUrls.glb != null)
                            resultLog += "- GLB: " + initialStatus.modelUrls.glb + "\n";
                        if (initialStatus.modelUrls.fbx != null)
                            resultLog += "- FBX: " + initialStatus.modelUrls.fbx + "\n";
                        if (initialStatus.modelUrls.obj != null)
                            resultLog += "- OBJ: " + initialStatus.modelUrls.obj + "\n";
                        if (initialStatus.thumbnailUrl != null)
                            resultLog += "\n썸네일: " + initialStatus.thumbnailUrl + "\n";
                    } else if ("FAILED".equals(initialStatus.status)) {
                        resultLog += "\n오류 정보: " + (initialStatus.error != null ? initialStatus.error : "알 수 없는 오류") + "\n";
                    }

                    appendToLogFile(taskLogFile, resultLog);
                    logConsole("태스크가 이미 완료되었습니다 - 최종 상태: " + initialStatus.status);
                    return;
                }
            } else {
                String errorLog = "[경고] 초기 상태 확인 실패 - API 응답 없음\n";
                appendToLogFile(taskLogFile, errorLog);
                logConsole(errorLog.trim());
                return;
            }
        } catch (Exception e) {
            String errorLog = "[오류] 초기 상태 확인 중 예외 발생: " + e.getMessage() + "\n";
            appendToLogFile(taskLogFile, errorLog);
            logConsole(errorLog.trim());
            return;
        }

        // 모니터링 루프
        logConsole("태스크 모니터링 루프 시작 - 간격: " + (CHECK_INTERVAL_MS / 1000) + "초, 최대 재시도: " + MAX_RETRY_COUNT + "회");

        MeshyTextTo3D.TaskStatus finalStatus = null;
        int retryCount = 0;
        long startTime = System.currentTimeMillis();

        while (retryCount < MAX_RETRY_COUNT) {
            try {
                // 지정된 간격만큼 대기
                Thread.sleep(CHECK_INTERVAL_MS);
                retryCount++;

                // 경과 시간 계산
                long elapsedTimeMs = System.currentTimeMillis() - startTime;
                long elapsedTimeSec = elapsedTimeMs / 1000;
                long minutes = elapsedTimeSec / 60;
                long seconds = elapsedTimeSec % 60;

                // 상태 확인
                MeshyTextTo3D meshy = new MeshyTextTo3D();
                MeshyTextTo3D.TaskStatus status = meshy.getTaskStatus(taskId);

                if (status != null) {
                    // 로그 메시지 생성
                    String checkTime = LocalDateTime.now().format(logDateFormatter);
                    String progressLog = String.format("[%s] 확인 #%d (경과: %d분 %d초):\n",
                            checkTime, retryCount, minutes, seconds);
                    progressLog += "- 상태: " + status.status + "\n";
                    progressLog += "- 진행률: " + status.progress + "%\n";

                    // 로그 파일에 기록
                    appendToLogFile(taskLogFile, progressLog);
                    logConsole(String.format("확인 #%d - 상태: %s, 진행률: %d%%, 경과: %d분 %d초",
                            retryCount, status.status, status.progress, minutes, seconds));

                    // 태스크 상태 업데이트
                    updateTaskStatus(taskId, status.status, status.progress);

                    // 완료 확인
                    if ("SUCCEEDED".equals(status.status) || "FAILED".equals(status.status)) {
                        finalStatus = status;
                        break;
                    }
                } else {
                    String errorLog = String.format("[%s] 확인 #%d - API 응답 없음\n",
                            LocalDateTime.now().format(logDateFormatter), retryCount);
                    appendToLogFile(taskLogFile, errorLog);
                    logConsole("확인 #" + retryCount + " - API 응답 없음");
                }
            } catch (InterruptedException e) {
                String errorLog = "[오류] 모니터링 중단됨: " + e.getMessage() + "\n";
                appendToLogFile(taskLogFile, errorLog);
                logConsole(errorLog.trim());
                break;
            } catch (Exception e) {
                String errorLog = String.format("[%s] 확인 #%d - 오류 발생: %s\n",
                        LocalDateTime.now().format(logDateFormatter), retryCount, e.getMessage());
                appendToLogFile(taskLogFile, errorLog);
                logConsole("확인 #" + retryCount + " - 오류 발생: " + e.getMessage());
            }
        }

        // 최종 상태 기록
        String finalLog = "\n====== 태스크 모니터링 완료 ======\n";
        finalLog += "총 확인 횟수: " + retryCount + "\n";

        // 총 소요 시간 계산
        long totalTimeMs = System.currentTimeMillis() - startTime;
        long totalTimeSec = totalTimeMs / 1000;
        long minutes = totalTimeSec / 60;
        long seconds = totalTimeSec % 60;
        finalLog += String.format("총 소요 시간: %d분 %d초\n", minutes, seconds);

        // 최종 상태 기록
        if (finalStatus != null) {
            finalLog += "\n최종 상태: " + finalStatus.status + "\n";
            finalLog += "최종 진행률: " + finalStatus.progress + "%\n";

            if ("SUCCEEDED".equals(finalStatus.status) && finalStatus.modelUrls != null) {
                finalLog += "\n모델 URL:\n";
                if (finalStatus.modelUrls.glb != null) finalLog += "- GLB: " + finalStatus.modelUrls.glb + "\n";
                if (finalStatus.modelUrls.fbx != null) finalLog += "- FBX: " + finalStatus.modelUrls.fbx + "\n";
                if (finalStatus.modelUrls.obj != null) finalLog += "- OBJ: " + finalStatus.modelUrls.obj + "\n";
                if (finalStatus.thumbnailUrl != null) finalLog += "\n썸네일: " + finalStatus.thumbnailUrl + "\n";

                // 완료된 태스크 세부 정보 저장
                saveCompletedTaskDetails(taskId, finalStatus);

                // JSON 결과 파일 저장
                String resultFile = MONITOR_FOLDER + "/" + taskId + "_result.json";
                fileManager.createFile(resultFile, gson.toJson(finalStatus));
                finalLog += "\n결과 JSON 파일: " + resultFile + "\n";
            } else if ("FAILED".equals(finalStatus.status)) {
                finalLog += "\n오류 정보: " + (finalStatus.error != null ? finalStatus.error : "알 수 없는 오류") + "\n";
            }

            logConsole("태스크 모니터링 완료 - 최종 상태: " + finalStatus.status + ", 소요 시간: " + minutes + "분 " + seconds + "초");
        } else {
            finalLog += "\n최종 상태: 알 수 없음 (최대 재시도 횟수 초과)\n";
            logConsole("태스크 모니터링 완료 - 최대 재시도 횟수 초과");
        }

        // 최종 로그 기록
        appendToLogFile(taskLogFile, finalLog);
        logConsole("====== 태스크 모니터링 완료 - ID: " + taskId + " ======\n");
    }

    /**
     * 로그 파일에 내용을 추가합니다.
     *
     * @param logFileName 로그 파일 경로
     * @param content     추가할 내용
     */
    private static void appendToLogFile(String logFileName, String content) {
        try {
            // 로그 파일이 없으면 생성
            Path logFile = Paths.get(logFileName);
            if (!Files.exists(logFile)) {
                Files.createDirectories(logFile.getParent());
                Files.writeString(logFile, "", StandardOpenOption.CREATE);
            }

            // 새 내용 추가하여 다시 쓰기
            String existingContent = Files.readString(logFile);
            fileManager.createFile(logFileName, existingContent + content);
        } catch (Exception e) {
            System.err.println("[ERROR] 로그 파일 쓰기 실패: " + e.getMessage());
        }
    }

    /**
     * 일괄 처리 기능을 실행합니다.
     * 이 메소드는 배치 파일에 있던 기능을 통합합니다.
     */
    public static void runBatch() {
        logConsole("\n======== Meshy 일괄 처리 시작 ========");

        // 1. 모든 태스크 상태 확인
        int activeCount = checkAllActiveTasksAndGetCount();

        // 2. 완료된 모든 태스크의 결과물 저장 확인
        checkCompletedTasksResults();

        // 3. 일괄 처리 결과 출력
        logConsole("======== Meshy 일괄 처리 완료 ========");
        logConsole("활성 태스크: " + activeCount + "개");
        logConsole("완료된 태스크: " + completedTaskIds.size() + "개");
    }

    /**
     * 완료된 모든 태스크의 결과물을 확인합니다.
     */
    public static void checkCompletedTasksResults() {
        logConsole("\n====== 완료된 태스크 결과 확인 시작 ======");

        // 저장된 태스크 목록 로드
        List<TaskInfo> allTasks = loadTasks();

        // 완료된 태스크 ID 목록
        List<String> tasksToCheck = new ArrayList<>(completedTaskIds);
        logConsole("확인할 완료 태스크 수: " + tasksToCheck.size());

        if (tasksToCheck.isEmpty()) {
            logConsole("확인할 완료된 태스크가 없습니다.");
            return;
        }

        // 각 완료된 태스크의 결과 확인
        int successCount = 0;
        int failedCount = 0;

        for (String taskId : tasksToCheck) {
            try {
                MeshyTextTo3D meshy = new MeshyTextTo3D();
                MeshyTextTo3D.TaskStatus status = meshy.getTaskStatus(taskId);

                if (status != null) {
                    if ("SUCCEEDED".equals(status.status)) {
                        successCount++;
                        logConsole("성공한 태스크 확인 - ID: " + taskId);

                        // 결과 세부 정보 저장
                        saveCompletedTaskDetails(taskId, status);
                    } else if ("FAILED".equals(status.status)) {
                        failedCount++;
                        logConsole("실패한 태스크 확인 - ID: " + taskId + ", 오류: " +
                                (status.error != null ? status.error : "알 수 없는 오류"));

                        // 실패 정보도 저장
                        saveCompletedTaskDetails(taskId, status);
                    } else {
                        // 아직 완료되지 않은 태스크는 다시 활성 목록에 추가
                        logConsole("태스크가 아직 완료되지 않음 - ID: " + taskId + ", 상태: " + status.status);
                        TaskInfo taskInfo = findTaskById(taskId);
                        if (taskInfo != null) {
                            taskInfo.setStatus(status.status);
                            taskInfo.setProgress(status.progress);
                            activeTasks.put(taskId, taskInfo);
                            completedTaskIds.remove(taskId);
                        }
                    }
                } else {
                    logConsole("[경고] 태스크 상태 조회 실패 - ID: " + taskId);
                }
            } catch (Exception e) {
                logConsole("[오류] 태스크 확인 중 예외 발생 - ID: " + taskId + " - " + e.getMessage());
            }
        }

        // 완료 태스크 결과 요약
        logConsole("====== 완료된 태스크 결과 확인 완료 ======");
        logConsole("성공한 태스크: " + successCount + "개");
        logConsole("실패한 태스크: " + failedCount + "개");
        logConsole("총 확인한 태스크: " + tasksToCheck.size() + "개\n");
    }

    /**
     * 여러 개의 태스크 ID를 일괄 확인합니다.
     * 배치 파일에서 사용하던 기능을 통합합니다.
     *
     * @param taskIds 확인할 태스크 ID 배열
     */
    public static void batchCheckTasks(String... taskIds) {
        if (taskIds == null || taskIds.length == 0) {
            logConsole("[경고] 확인할 태스크 ID가 없습니다.");
            return;
        }

        logConsole("\n====== 태스크 일괄 확인 시작 - 총 " + taskIds.length + "개 ======");

        for (String taskId : taskIds) {
            if (taskId != null && !taskId.trim().isEmpty()) {
                logConsole("태스크 ID 확인 중: " + taskId);
                checkSpecificTask(taskId);
            }
        }

        logConsole("====== 태스크 일괄 확인 완료 ======\n");
    }

    /**
     * 콘솔에 로그를 출력하고 로그 파일에도 저장합니다.
     *
     * @param message 로그 메시지
     */
    public static void logConsole(String message) {
        String timestamp = LocalDateTime.now().format(logDateFormatter);
        String logMessage = "[" + timestamp + "] " + message;
        System.out.println(logMessage);

        // 로그 파일에도 저장
        appendToLogFile(DEBUG_LOG_FILE, logMessage + "\n");
    }

    /**
     * 메인 메소드 - 다양한 기능을 테스트하는 데 사용할 수 있습니다.
     */
    public static void main(String[] args) {
        // 예시: 모든 활성 태스크 확인
        // checkAllActiveTasks();

        // 예시: 특정 태스크 확인
        // String taskId = "YOUR_TASK_ID";
        // checkSpecificTask(taskId);

        // 예시: 특정 태스크 모니터링
        // monitorTask("YOUR_TASK_ID", true);

        // 예시: 일괄 처리 실행
        runBatch();
    }
}