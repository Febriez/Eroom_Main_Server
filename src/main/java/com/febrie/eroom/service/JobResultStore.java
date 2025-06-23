package com.febrie.eroom.service;

import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 작업 결과를 저장하고 관리하는 저장소
 */
public class JobResultStore {

    /**
     * 작업 상태를 나타내는 열거형
     */
    public enum Status {
        QUEUED,
        PROCESSING,
        COMPLETED,
        FAILED
    }

    /**
     * 작업 상태와 결과를 포함하는 레코드
     */
    public record JobState(Status status, @Nullable JsonObject result) {
    }

    // 최종 상태들
    private static final Set<Status> FINAL_STATUSES = Set.of(Status.COMPLETED, Status.FAILED);

    // 오류 메시지
    private static final String ERROR_INVALID_FINAL_STATUS = "Final status must be COMPLETED or FAILED.";

    private final Map<String, JobState> jobStore = new ConcurrentHashMap<>();

    /**
     * 새 작업을 등록합니다.
     * 초기 상태는 QUEUED로 설정됩니다.
     */
    public void registerJob(String trackingId) {
        jobStore.put(trackingId, new JobState(Status.QUEUED, null));
    }

    /**
     * 작업 상태를 업데이트합니다.
     * 기존 작업이 존재하는 경우에만 상태를 변경합니다.
     */
    public void updateJobStatus(String trackingId, Status status) {
        jobStore.computeIfPresent(trackingId, (key, oldState) ->
                new JobState(status, oldState.result())
        );
    }

    /**
     * 최종 결과와 상태를 저장합니다.
     * 상태는 반드시 COMPLETED 또는 FAILED여야 합니다.
     */
    public void storeFinalResult(String trackingId, JsonObject result, Status finalStatus) {
        validateFinalStatus(finalStatus);
        jobStore.put(trackingId, new JobState(finalStatus, result));
    }

    /**
     * 최종 상태의 유효성을 검증합니다.
     */
    private void validateFinalStatus(Status finalStatus) {
        if (!FINAL_STATUSES.contains(finalStatus)) {
            throw new IllegalArgumentException(ERROR_INVALID_FINAL_STATUS);
        }
    }

    /**
     * 작업 상태를 조회합니다.
     * 존재하지 않는 경우 빈 Optional을 반환합니다.
     */
    public Optional<JobState> getJobState(String trackingId) {
        return Optional.ofNullable(jobStore.get(trackingId));
    }

    /**
     * 작업을 삭제합니다.
     */
    public void deleteJob(String trackingId) {
        jobStore.remove(trackingId);
    }

    /**
     * 현재 저장된 작업 수를 반환합니다.
     * 주로 테스트나 모니터링 목적으로 사용됩니다.
     */
    public int getJobCount() {
        return jobStore.size();
    }

    /**
     * 모든 작업을 삭제합니다.
     * 주로 테스트 목적으로 사용됩니다.
     */
    public void clear() {
        jobStore.clear();
    }
}