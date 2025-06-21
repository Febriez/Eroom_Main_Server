package com.febrie.eroom.service;

import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class JobResultStore {

    public enum Status {
        QUEUED,
        PROCESSING,
        COMPLETED,
        FAILED
    }

    public record JobState(Status status, @Nullable JsonObject result) {
    }

    private final Map<String, JobState> jobStore = new ConcurrentHashMap<>();

    public void registerJob(String trackingId) {
        jobStore.put(trackingId, new JobState(Status.QUEUED, null));
    }

    public void updateJobStatus(String trackingId, Status status) {
        jobStore.computeIfPresent(trackingId, (key, oldState) -> new JobState(status, oldState.result()));
    }

    public void storeFinalResult(String trackingId, JsonObject result, Status finalStatus) {
        validateFinalStatus(finalStatus);
        jobStore.put(trackingId, new JobState(finalStatus, result));
    }

    private void validateFinalStatus(Status finalStatus) {
        if (finalStatus != Status.COMPLETED && finalStatus != Status.FAILED) {
            throw new IllegalArgumentException("Final status must be COMPLETED or FAILED.");
        }
    }

    public Optional<JobState> getJobState(String trackingId) {
        return Optional.ofNullable(jobStore.get(trackingId));
    }

    public void deleteJob(String trackingId) {
        jobStore.remove(trackingId);
    }
}