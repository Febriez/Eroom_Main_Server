package com.febrie.service;

import com.febrie.api.MeshyTextTo3D;
import com.febrie.model.MeshyTask;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.LocalDateTime;
import java.util.concurrent.*;

/**
 * Meshy 태스크 생성 및 관리를 담당하는 서비스 클래스
 */
@Slf4j
public class MeshyTaskService {

    // 기본 API 인스턴스
    private final MeshyTextTo3D meshyApi;

    // 폴링 간격 (초)
    private static final int POLLING_INTERVAL_SECONDS = 5;

    // 최대 대기 시간 (분)
    private static final int MAX_WAIT_MINUTES = 30;

    /**
     * 기본 생성자
     */
    public MeshyTaskService() {
        this.meshyApi = new MeshyTextTo3D();
    }

    /**
     * API 인스턴스를 지정하는 생성자
     *
     * @param meshyApi MeshyTextTo3D API 인스턴스
     */
    public MeshyTaskService(@NotNull MeshyTextTo3D meshyApi) {
        this.meshyApi = meshyApi;
    }

    /**
     * Preview 모델을 생성하고 완료될 때까지 대기
     *
     * @param prompt   3D 모델 생성에 사용할 프롬프트
     * @param artStyle 아트 스타일 (realistic, cartoon, etc.)
     * @return 생성된 MeshyTask 객체, 실패 시 null
     */
    @Nullable
    public MeshyTask createPreviewModel(@NotNull String prompt, @NotNull String artStyle) {
        log.info("Preview 모델 생성 시작 - 프롬프트: {}, 스타일: {}", prompt, artStyle);
        long startTime = System.currentTimeMillis();

        try {
            // 태스크 생성
            String taskId = meshyApi.createPreviewTask(prompt, artStyle);
            log.info("Preview 태스크 ID 생성 완료: {}", taskId);

            // 태스크 완료 대기
            MeshyTextTo3D.TaskStatus status = waitForTaskCompletion(taskId, MeshyTask.TaskType.PREVIEW, prompt);
            if (status == null) {
                log.error("Preview 태스크 결과 조회 실패: {}", taskId);
                return null;
            }

            // 소요 시간 계산
            long elapsedTimeMs = System.currentTimeMillis() - startTime;

            // 태스크 결과 로깅
            MeshyLogService.logCompletedTask(taskId, status, "preview", prompt, elapsedTimeMs);

            // 태스크 모델 객체 생성 및 반환
            MeshyTask result = MeshyTask.fromApiStatus(status, MeshyTask.TaskType.PREVIEW, prompt, null, elapsedTimeMs);

            // 결과 저장 (에러 발생해도 계속 진행)
            try {
                saveMeshyTaskResultToDesktop(result);
            } catch (Exception e) {
                log.error("Preview 태스크 결과 저장 실패: {}", e.getMessage(), e);
            }

            log.info("Preview 모델 생성 완료 - 소요시간: {}", result.getFormattedElapsedTime());
            return result;

        } catch (Exception e) {
            log.error("Preview 모델 생성 실패: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Refine 모델을 생성하고 완료될 때까지 대기
     *
     * @param previewTaskId 기존 Preview 태스크 ID
     * @param texturePrompt 텍스처 생성에 사용할 프롬프트
     * @param enablePbr     PBR 텍스처 활성화 여부
     * @return 생성된 MeshyTask 객체, 실패 시 null
     */
    @Nullable
    public MeshyTask createRefineModel(@NotNull String previewTaskId, @NotNull String texturePrompt, boolean enablePbr) {
        log.info("Refine 모델 생성 시작 - Preview ID: {}, 텍스처 프롬프트: {}", previewTaskId, texturePrompt);
        long startTime = System.currentTimeMillis();

        try {
            // 태스크 생성
            String taskId = meshyApi.createRefineTask(previewTaskId, enablePbr, texturePrompt);
            log.info("Refine 태스크 ID 생성 완료: {}", taskId);

            // 태스크 완료 대기
            MeshyTextTo3D.TaskStatus status = waitForTaskCompletion(taskId, MeshyTask.TaskType.REFINE, texturePrompt, previewTaskId);
            if (status == null) {
                log.error("Refine 태스크 결과 조회 실패: {}", taskId);
                return null;
            }

            // 소요 시간 계산
            long elapsedTimeMs = System.currentTimeMillis() - startTime;

            // 태스크 결과 로깅
            MeshyLogService.logCompletedTask(taskId, status, "refine", texturePrompt, elapsedTimeMs);

            // 태스크 모델 객체 생성 및 반환
            MeshyTask result = MeshyTask.fromApiStatus(status, MeshyTask.TaskType.REFINE, texturePrompt, previewTaskId, elapsedTimeMs);

            // 결과 저장 (에러 발생해도 계속 진행)
            try {
                saveMeshyTaskResultToDesktop(result);
            } catch (Exception e) {
                log.error("Refine 태스크 결과 저장 실패: {}", e.getMessage(), e);
            }

            log.info("Refine 모델 생성 완료 - 소요시간: {}", result.getFormattedElapsedTime());
            return result;

        } catch (Exception e) {
            log.error("Refine 모델 생성 실패: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 전체 3D 모델 생성 프로세스 (Preview + Refine)
     *
     * @param prompt        3D 모델 생성에 사용할 프롬프트
     * @param artStyle      아트 스타일 (realistic, cartoon, etc.)
     * @param texturePrompt 텍스처 생성에 사용할 프롬프트
     * @return 생성된 최종 MeshyTask 객체, 실패 시 null
     */
    @Nullable
    public MeshyTask createFullModel(@NotNull String prompt, @NotNull String artStyle, @NotNull String texturePrompt) {
        log.info("전체 3D 모델 생성 시작 - 프롬프트: {}, 스타일: {}, 텍스처: {}", prompt, artStyle, texturePrompt);
        long startTime = System.currentTimeMillis();

        try {
            // 1. Preview 모델 생성
            MeshyTask previewTask = createPreviewModel(prompt, artStyle);
            if (previewTask == null || !previewTask.isSucceeded()) {
                log.error("Preview 모델 생성 실패, 전체 모델 생성 중단");
                return null;
            }

            // 2. Refine 모델 생성
            MeshyTask refineTask = createRefineModel(previewTask.getTaskId(), texturePrompt, true);
            if (refineTask == null) {
                log.error("Refine 모델 생성 실패, 전체 모델 생성 중단");
                return previewTask; // 실패하더라도 Preview 결과 반환
            }

            // 소요 시간 계산
            long elapsedTimeMs = System.currentTimeMillis() - startTime;
            refineTask.setElapsedTimeMs(elapsedTimeMs); // 전체 프로세스 소요 시간 업데이트

            // 합성 태스크 생성 (전체 프로세스를 나타내는 MeshyTask)
            MeshyTask completeTask = MeshyTask.builder()
                    .taskId(refineTask.getTaskId()) // Refine 태스크 ID 사용
                    .taskType(MeshyTask.TaskType.COMPLETE_MODEL)
                    .status(refineTask.getStatus())
                    .progress(refineTask.getProgress())
                    .prompt(prompt + " (스타일: " + artStyle + ", 텍스처: " + texturePrompt + ")")
                    .parentTaskId(previewTask.getTaskId())
                    .createdAt(previewTask.getCreatedAt())
                    .lastUpdatedAt(LocalDateTime.now())
                    .thumbnailUrl(refineTask.getThumbnailUrl())
                    .modelUrls(refineTask.getModelUrls())
                    .errorMessage(refineTask.getErrorMessage())
                    .elapsedTimeMs(elapsedTimeMs)
                    .build();

            // 결과 저장 (에러 발생해도 계속 진행)
            try {
                saveMeshyTaskResultToDesktop(completeTask);
            } catch (Exception e) {
                log.error("전체 모델 생성 결과 저장 실패: {}", e.getMessage(), e);
            }

            log.info("전체 3D 모델 생성 완료 - 소요시간: {}", completeTask.getFormattedElapsedTime());
            return completeTask;

        } catch (Exception e) {
            log.error("전체 3D 모델 생성 실패: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 중단된 태스크 결과를 확인
     *
     * @param taskId   확인할 태스크 ID
     * @param taskType 태스크 유형 (PREVIEW, REFINE, COMPLETE_MODEL)
     * @param prompt   생성에 사용된 프롬프트
     * @return 확인된 MeshyTask 객체, 실패 시 null
     */
    @Nullable
    public MeshyTask checkInterruptedTask(@NotNull String taskId, @NotNull MeshyTask.TaskType taskType, @NotNull String prompt) {
        log.info("중단된 태스크 확인 시작 - ID: {}, 유형: {}", taskId, taskType);

        try {
            // 태스크 상태 조회
            MeshyTextTo3D.TaskStatus status = meshyApi.getTaskStatus(taskId);
            if (status == null) {
                log.error("태스크 상태 조회 실패: {}", taskId);
                return null;
            }

            // 태스크 모델 객체 생성
            MeshyTask task = MeshyTask.fromApiStatus(status, taskType, prompt, null, 0);

            // 결과 출력 및 저장 (성공한 경우에만)
            if (task.isSucceeded()) {
                log.info("중단된 태스크 확인 성공 - ID: {}, 상태: {}", taskId, status.status);
                printModelResults(task);
                saveMeshyTaskResultToDesktop(task);
            } else {
                log.warn("중단된 태스크가 성공 상태가 아님 - ID: {}, 상태: {}", taskId, status.status);
            }

            return task;
        } catch (Exception e) {
            log.error("중단된 태스크 확인 실패: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 중단된 Preview 및 Refine 태스크 결과를 함께 확인
     *
     * @param previewTaskId Preview 태스크 ID (null 가능)
     * @param refineTaskId  Refine 태스크 ID (null 가능)
     * @return 마지막으로 확인한 태스크 객체, 모두 실패하면 null
     */
    @Nullable
    public MeshyTask retrieveCompletedTaskResults(@Nullable String previewTaskId, @Nullable String refineTaskId) {
        MeshyTask previewTask = null;
        MeshyTask refineTask = null;

        // Preview 태스크 확인
        if (previewTaskId != null && !previewTaskId.isEmpty()) {
            log.info("Preview 태스크 확인 - ID: {}", previewTaskId);
            previewTask = checkInterruptedTask(previewTaskId, MeshyTask.TaskType.PREVIEW, "Preview task");
        }

        // Refine 태스크 확인
        if (refineTaskId != null && !refineTaskId.isEmpty()) {
            log.info("Refine 태스크 확인 - ID: {}", refineTaskId);
            refineTask = checkInterruptedTask(refineTaskId, MeshyTask.TaskType.REFINE, "Refine task");
        }

        // 둘 다 확인한 경우 마지막 태스크 반환
        return refineTask != null ? refineTask : previewTask;
    }

    /**
     * 태스크 진행 상황을 비동기적으로 모니터링
     *
     * @param taskId   모니터링할 태스크 ID
     * @param taskType 태스크 유형
     * @param prompt   사용된 프롬프트
     * @return 태스크 완료 시 상태를 담은 Future 객체
     */
    @NotNull
    public CompletableFuture<MeshyTask> monitorTaskAsync(@NotNull String taskId, @NotNull MeshyTask.TaskType taskType, @NotNull String prompt) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                MeshyTextTo3D.TaskStatus status = waitForTaskCompletion(taskId, taskType, prompt);
                if (status != null) {
                    long elapsedTimeMs = 0; // 이 방식에서는 정확한 시간을 알 수 없음
                    return MeshyTask.fromApiStatus(status, taskType, prompt, null, elapsedTimeMs);
                } else {
                    throw new RuntimeException("태스크 모니터링 실패: " + taskId);
                }
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        });
    }

    /**
     * 태스크 완료를 기다리는 내부 메소드
     *
     * @param taskId   태스크 ID
     * @param taskType 태스크 유형
     * @param prompt   프롬프트
     * @return 완료된 태스크 상태, 오류 발생 시 null
     */
    private MeshyTextTo3D.TaskStatus waitForTaskCompletion(@NotNull String taskId, @NotNull MeshyTask.TaskType taskType, @NotNull String prompt) {
        return waitForTaskCompletion(taskId, taskType, prompt, null);
    }

    /**
     * 태스크 완료를 기다리는 내부 메소드 (상세 버전)
     *
     * @param taskId       태스크 ID
     * @param taskType     태스크 유형
     * @param prompt       프롬프트
     * @param parentTaskId 부모 태스크 ID (Refine 태스크의 경우)
     * @return 완료된 태스크 상태, 오류 발생 시 null
     */
    @Nullable
    private MeshyTextTo3D.TaskStatus waitForTaskCompletion(@NotNull String taskId, @NotNull MeshyTask.TaskType taskType,
                                                           @NotNull String prompt, @Nullable String parentTaskId) {
        log.info("{} 태스크 진행 상황 모니터링 시작: {}", taskType, taskId);

        // 스트리밍 API가 실패할 경우를 대비한 폴링 메커니즘
        final CompletableFuture<MeshyTextTo3D.TaskStatus> resultFuture = new CompletableFuture<>();

        try (final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1)) {
            // 스트리밍 API를 통한 실시간 모니터링 시도
            try {
                meshyApi.streamTaskProgress(taskId, new MeshyTextTo3D.TaskProgressCallback() {
                    @Override
                    public void onProgress(MeshyTextTo3D.TaskStatus status) {
                        String typeStr = taskType.toString();
                        log.debug("{} 태스크 진행률: {}%, 상태: {}", typeStr.toUpperCase(), status.progress, status.status);

                        // 상태 로깅 (에러 발생해도 진행)
                        try {
                            String logTaskType = typeStr.toLowerCase();
                            if ("preview".equals(logTaskType)) {
                                MeshyLogService.logTaskStatus(taskId, status, logTaskType, prompt, null);
                            } else if ("refine".equals(logTaskType) && parentTaskId != null) {
                                MeshyLogService.logTaskStatus(taskId, status, logTaskType, prompt, parentTaskId);
                            }
                        } catch (Exception e) {
                            log.error("태스크 상태 로깅 실패: {}", e.getMessage(), e);
                        }

                        // 작업 완료 시 Future 완료
                        if ("SUCCEEDED".equals(status.status) || "FAILED".equals(status.status)) {
                            resultFuture.complete(status);
                        }
                    }

                    @Override
                    public void onError(Exception e) {
                        log.error("태스크 스트리밍 중 오류 발생: {}", e.getMessage(), e);
                        // 스트리밍 실패 시 Future는 완료하지 않음 (폴링으로 전환)
                    }
                });
            } catch (Exception e) {
                log.error("스트리밍 API 설정 실패: {}", e.getMessage(), e);
                // 스트리밍 API 설정 실패 시 폴링으로 진행 (Future는 완료하지 않음)
            }

            // 폴링 스케줄링 (스트리밍 API가 실패하거나 완료 응답을 주지 않을 경우 대비)
            scheduler.scheduleAtFixedRate(() -> {
                if (resultFuture.isDone()) {
                    scheduler.shutdown();
                    return;
                }

                try {
                    MeshyTextTo3D.TaskStatus status = meshyApi.getTaskStatus(taskId);
                    String typeStr = taskType.toString().toUpperCase();
                    log.debug("{} 태스크 폴링 - 진행률: {}%, 상태: {}", typeStr, status.progress, status.status);

                    // 상태 로깅
                    String logTaskType = taskType.toString().toLowerCase();
                    try {
                        if ("preview".equals(logTaskType)) {
                            MeshyLogService.logTaskStatus(taskId, status, logTaskType, prompt, null);
                        } else if ("refine".equals(logTaskType) && parentTaskId != null) {
                            MeshyLogService.logTaskStatus(taskId, status, logTaskType, prompt, parentTaskId);
                        }
                    } catch (Exception e) {
                        log.error("태스크 상태 로깅 실패: {}", e.getMessage(), e);
                    }

                    // 완료된 경우 Future 완료
                    if ("SUCCEEDED".equals(status.status) || "FAILED".equals(status.status)) {
                        resultFuture.complete(status);
                        scheduler.shutdown();
                    }
                } catch (Exception e) {
                    log.error("태스크 상태 폴링 실패: {}", e.getMessage(), e);
                }
            }, POLLING_INTERVAL_SECONDS, POLLING_INTERVAL_SECONDS, TimeUnit.SECONDS);

            // 결과 대기 (최대 대기 시간 설정)
            try {
                return resultFuture.get(MAX_WAIT_MINUTES, TimeUnit.MINUTES);
            } catch (Exception e) {
                log.error("태스크 완료 대기 중 제한 시간 초과 또는 오류: {}", e.getMessage(), e);
                return null;
            }
        }
    }

    /**
     * 완료된 태스크 결과를 데스크탑에 저장
     *
     * @param task 저장할 MeshyTask 객체
     */
    private void saveMeshyTaskResultToDesktop(@NotNull MeshyTask task) {
        try {
            // Meshy 예제 클래스의 저장 메소드 사용
            String taskTypeStr = task.getTaskType().toString().toLowerCase();
            com.febrie.api.MeshyExample.saveMeshyTaskResultToDesktop(
                    task.getPrompt(),
                    task.getModelUrls() != null ?
                            meshyApi.getTaskStatus(task.getTaskId()) : null,
                    taskTypeStr,
                    task.getElapsedTimeMs());
        } catch (Exception e) {
            log.error("태스크 결과 저장 실패: {}", e.getMessage(), e);
        }
    }

    /**
     * 모델 결과를 출력하는 메소드
     *
     * @param task 완료된 MeshyTask 객체
     */
    private void printModelResults(@NotNull MeshyTask task) {
        if (!task.isSucceeded()) {
            log.warn("성공하지 않은 태스크의 결과를 출력합니다: {}, 상태: {}", task.getTaskId(), task.getStatus());
        }

        log.info("====== 모델 생성 결과 ======");
        log.info("태스크 ID: {}", task.getTaskId());
        log.info("유형: {}", task.getTaskType());
        log.info("상태: {}", task.getStatus());
        log.info("진행률: {}%", task.getProgress());
        log.info("소요 시간: {}", task.getFormattedElapsedTime());

        if (task.getModelUrls() != null) {
            log.info("====== 모델 URL ======");
            MeshyTextTo3D.TaskStatus.ModelUrls urls = task.getModelUrls();
            if (urls.glb != null) log.info("GLB: {}", urls.glb);
            if (urls.fbx != null) log.info("FBX: {}", urls.fbx);
            if (urls.obj != null) log.info("OBJ: {}", urls.obj);
            if (urls.mtl != null) log.info("MTL: {}", urls.mtl);
            if (urls.usdz != null) log.info("USDZ: {}", urls.usdz);
        }

        if (task.getThumbnailUrl() != null) {
            log.info("썸네일: {}", task.getThumbnailUrl());
        }

        if (task.getErrorMessage() != null) {
            log.error("오류: {}", task.getErrorMessage());
        }
    }
}
