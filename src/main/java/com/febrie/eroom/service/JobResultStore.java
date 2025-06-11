package com.febrie.eroom.service;

import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 비동기 작업의 상태와 결과를 저장하고 관리하는 클래스.
 * 모든 메소드는 스레드에 안전합니다.
 */
public class JobResultStore {

    public enum Status {
        QUEUED,      // 큐에 대기 중
        PROCESSING,  // 처리 중
        COMPLETED,   // 성공적으로 완료됨
        FAILED       // 처리 중 오류 발생
    }

    /**
     * 작업의 상태와 최종 결과를 담는 레코드.
     *
     * @param status 현재 작업 상태
     * @param result 작업 완료 시의 결과 JsonObject (실패 시 오류 정보 포함)
     */
    public record JobState(Status status, @Nullable JsonObject result) {
    }

    private final Map<String, JobState> jobStore = new ConcurrentHashMap<>();

    /**
     * 새로운 작업을 'QUEUED' 상태로 저장소에 등록합니다.
     *
     * @param trackingId 작업의 고유 ID
     */
    public void registerJob(String trackingId) {
        jobStore.put(trackingId, new JobState(Status.QUEUED, null));
    }

    /**
     * 작업의 상태를 업데이트합니다.
     *
     * @param trackingId 작업의 고유 ID
     * @param status     새로운 상태
     */
    public void updateJobStatus(String trackingId, Status status) {
        jobStore.computeIfPresent(trackingId, (key, oldState) -> new JobState(status, oldState.result()));
    }

    /**
     * 작업 완료 후 최종 결과를 저장소에 저장합니다.
     *
     * @param trackingId  작업의 고유 ID
     * @param result      최종 결과 JsonObject
     * @param finalStatus 최종 상태 (COMPLETED 또는 FAILED)
     */
    public void storeFinalResult(String trackingId, JsonObject result, Status finalStatus) {
        if (finalStatus != Status.COMPLETED && finalStatus != Status.FAILED) {
            throw new IllegalArgumentException("Final status must be COMPLETED or FAILED.");
        }
        jobStore.put(trackingId, new JobState(finalStatus, result));
    }

    /**
     * trackingId에 해당하는 작업 상태를 조회합니다.
     *
     * @param trackingId 작업의 고유 ID
     * @return JobState를 담은 Optional 객체 (해당 ID가 없으면 empty)
     */
    public Optional<JobState> getJobState(String trackingId) {
        return Optional.ofNullable(jobStore.get(trackingId));
    }

    /**
     * trackingId에 해당하는 작업 결과를 저장소에서 삭제합니다.
     *
     * @param trackingId 작업의 고유 ID
     */
    public void deleteJob(String trackingId) {
        jobStore.remove(trackingId);
    }
}