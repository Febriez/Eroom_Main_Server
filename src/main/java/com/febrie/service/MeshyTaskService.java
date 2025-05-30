package com.febrie.service;

import com.febrie.api.MeshyTaskTracker;
import com.febrie.api.MeshyTextTo3D;
import com.febrie.model.MeshyTask;
import com.febrie.model.MeshyTask.TaskType;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/**
 * Meshy 태스크 관리를 위한 서비스 클래스
 */
@Slf4j
public class MeshyTaskService {

    private static final int DEFAULT_POLLING_INTERVAL_MS = 5000; // 5초

    private final MeshyTextTo3D meshyApi;

    /**
     * 기본 생성자
     */
    public MeshyTaskService() {
        this.meshyApi = new MeshyTextTo3D();
    }

    /**
     * Preview 태스크를 생성합니다.
     * 
     * @param prompt 3D 모델 생성에 사용할 프롬프트
     * @param artStyle 아트 스타일 (realistic, cartoon 등)
     * @return 생성된 Preview 태스크 ID
     * @throws IOException API 오류 발생 시
     */
    public String createPreviewTask(@NotNull String prompt, @NotNull String artStyle) throws IOException {
        long startTime = System.currentTimeMillis();
        String taskId = meshyApi.createPreviewTask(prompt, artStyle);

        System.out.println("Preview 태스크 생성됨: " + taskId + 
                " - 소요 시간: " + (System.currentTimeMillis() - startTime) + "ms");

        // 태스크 트래커에 등록
        MeshyTaskTracker.trackPreviewTask(taskId, prompt);

        return taskId;
    }

    /**
     * Refine 태스크를 생성합니다.
     * 
     * @param previewTaskId 기존 Preview 태스크 ID
     * @param texturePrompt 텍스처 생성에 사용할 프롬프트
     * @param enablePbr PBR 텍스처 활성화 여부
     * @return 생성된 Refine 태스크 ID
     * @throws IOException API 오류 발생 시
     */
    public String createRefineTask(@NotNull String previewTaskId, @NotNull String texturePrompt, boolean enablePbr) throws IOException {
        long startTime = System.currentTimeMillis();
        String taskId = meshyApi.createRefineTask(previewTaskId, enablePbr, texturePrompt);

        System.out.println("Refine 태스크 생성됨: " + taskId + 
                " - 소요 시간: " + (System.currentTimeMillis() - startTime) + "ms");

        // 태스크 트래커에 등록
        MeshyTaskTracker.trackRefineTask(taskId, previewTaskId, texturePrompt);

        return taskId;
    }

    /**
     * 태스크 상태를 확인합니다.
     * 
     * @param taskId 확인할 태스크 ID
     * @return 태스크 상태 객체
     * @throws IOException API 오류 발생 시
     */
    public MeshyTextTo3D.TaskStatus getTaskStatus(@NotNull String taskId) throws IOException {
        return meshyApi.getTaskStatus(taskId);
    }

    /**
     * 태스크 진행 상황을 스트리밍 방식으로 모니터링합니다.
     * 
     * @param taskId 모니터링할 태스크 ID
     * @param callback 상태 변경 시 호출될 콜백
     */
    public void streamTaskProgress(@NotNull String taskId, @NotNull MeshyTextTo3D.TaskProgressCallback callback) {
        meshyApi.streamTaskProgress(taskId, callback);
    }

    /**
     * 태스크 완료를 대기하고 최종 상태를 반환합니다.
     * 별도의 서비스로 분리한 waitForTaskCompletion 메서드입니다.
     * 
     * @param taskId 태스크 ID
     * @param taskType 태스크 유형
     * @param prompt 사용된 프롬프트
     * @param parentTaskId 부모 태스크 ID (Refine의 경우)
     * @return 완료된 태스크 객체, 오류 시 null
     */
    @Nullable
    public MeshyTask waitForTaskCompletion(@NotNull String taskId, @NotNull TaskType taskType,
                                            @NotNull String prompt, @Nullable String parentTaskId) {
        final long startTime = System.currentTimeMillis();
        final long[] lastLogTime = {startTime};
        final MeshyTextTo3D.TaskStatus[] finalStatus = {null};
        final Object lock = new Object();

        try {
            // 통합 로그에 태스크 시작 기록
            String taskTypeStr = taskType.toString();
            System.out.println(taskTypeStr.toUpperCase() + " 태스크 완료 대기 중: " + taskId);

            // 스트리밍 API를 통해 진행 상황 모니터링
            streamTaskProgress(taskId, new MeshyTextTo3D.TaskProgressCallback() {
                @Override
                public void onProgress(MeshyTextTo3D.TaskStatus status) {
                    long currentTime = System.currentTimeMillis();
                    long elapsedSeconds = (currentTime - startTime) / 1000;

                    // 진행 상황 출력
                    System.out.println(taskTypeStr.toUpperCase() + " 진행률: " + status.progress + 
                            "%, 경과 시간: " + elapsedSeconds + "초");

                    // 5초마다 로그 갱신
                    if (currentTime - lastLogTime[0] > 5000) {
                        lastLogTime[0] = currentTime;

                        // 태스크 유형에 따라 로깅
                        MeshyLogService.logTaskStatus(taskId, status, taskTypeStr, prompt, parentTaskId);

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
                    System.err.println(taskTypeStr.toUpperCase() + " 태스크 스트리밍 중 오류 발생: " + e.getMessage());
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
                finalStatus[0] = getTaskStatus(taskId);
            }

            MeshyTextTo3D.TaskStatus status = finalStatus[0];
            long totalTimeMs = System.currentTimeMillis() - startTime;

            // MeshyTask 모델로 변환
            MeshyTask task = MeshyTask.fromApiStatus(status, taskType, prompt, parentTaskId, totalTimeMs);

            // 로깅 처리
            if (task.isSucceeded()) {
                MeshyLogService.logCompletedTask(taskId, status, taskTypeStr, prompt, totalTimeMs);
            } else if (task.isFailed()) {
                String errorDetail = status.error != null ? status.error : "세부 오류 정보 없음";
                MeshyLogService.logFailedTask(taskId, status, taskTypeStr, prompt, errorDetail);
            }

            return task;

        } catch (Exception e) {
            System.err.println(taskType.toString().toUpperCase() + " 태스크 대기 중 오류 발생: " + e);
            return null;
        }
    }

    /**
     * 전체 3D 모델 생성 프로세스를 수행합니다. (Preview + Refine)
     * 
     * @param prompt 3D 모델 생성에 사용할 프롬프트
     * @param artStyle 아트 스타일
     * @param texturePrompt 텍스처 생성에 사용할 프롬프트
     * @return 생성된 최종 Refine 태스크 객체, 실패 시 null
     */
    @Nullable
    public MeshyTask createFullModel(@NotNull String prompt, @NotNull String artStyle, @NotNull String texturePrompt) {
        try {
            long fullStartTime = System.currentTimeMillis();

            System.out.println("========== 전체 3D 모델 생성 프로세스 시작 ==========");
            System.out.println("프롬프트: " + prompt);
            System.out.println("아트 스타일: " + artStyle);
            System.out.println("텍스처 프롬프트: " + texturePrompt);

            // 1. Preview 모델 생성
            String previewTaskId = createPreviewTask(prompt, artStyle);
            if (previewTaskId == null) {
                System.err.println("Preview 모델 생성 실패");
                return null;
            }

            // 2. Preview 작업 상태 확인
            MeshyTask previewTask = waitForTaskCompletion(previewTaskId, TaskType.PREVIEW, prompt, null);
            if (previewTask == null || !previewTask.isSucceeded()) {
                System.err.println("Preview 태스크가 실패했거나 완료되지 않았습니다.");
                return null;
            }

            // 3. Refine 모델 생성
            String refineTaskId = createRefineTask(previewTaskId, texturePrompt, true);
            if (refineTaskId == null) {
                System.err.println("Refine 모델 생성 실패");
                return null;
            }

            // 4. Refine 작업 상태 확인
            MeshyTask refineTask = waitForTaskCompletion(refineTaskId, TaskType.REFINE, texturePrompt, previewTaskId);
            if (refineTask == null || !refineTask.isSucceeded()) {
                System.err.println("Refine 태스크가 실패했거나 완료되지 않았습니다.");
                return null;
            }

            // 전체 소요 시간 계산
            refineTask.setElapsedTimeMs(System.currentTimeMillis() - fullStartTime);

            // 완료 메시지 출력
            String completionMessage = "전체 3D 모델 생성 프로세스 완료 - 총 소요 시간: " + refineTask.getFormattedElapsedTime();
            System.out.println("========== " + completionMessage + " ==========");

            return refineTask;

        } catch (Exception e) {
            System.err.println("3D 모델 생성 중 오류 발생: " + e);
            return null;
        }
    }
}
