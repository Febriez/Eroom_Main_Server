
package com.febrie.model;

import com.febrie.api.MeshyAPI;
import lombok.Builder;
import lombok.Data;
import org.jetbrains.annotations.NotNull;

import java.time.LocalDateTime;

/**
 * Meshy 태스크 정보를 담는 모델 클래스
 */
@Data
@Builder
public class MeshyTask {

    /**
     * 태스크 유형
     */
    public enum TaskType {
        PREVIEW, REFINE, COMPLETE_MODEL;

        @Override
        public String toString() {
            return name().toLowerCase();
        }
    }

    /**
     * 태스크 상태
     */
    public enum TaskStatus {
        PENDING, IN_PROGRESS, SUCCEEDED, FAILED, CANCELED, UNKNOWN;

        @NotNull
        public static TaskStatus fromApiStatus(String apiStatus) {
            if (apiStatus == null) return UNKNOWN;

            return switch (apiStatus.toUpperCase()) {
                case "PENDING" -> PENDING;
                case "IN_PROGRESS" -> IN_PROGRESS;
                case "SUCCEEDED" -> SUCCEEDED;
                case "FAILED" -> FAILED;
                case "CANCELED" -> CANCELED;
                default -> UNKNOWN;
            };
        }
    }

    // 태스크 기본 정보
    private String taskId;
    private TaskType taskType;
    private TaskStatus status;
    private int progress;
    private String prompt;
    private LocalDateTime createdAt;
    private LocalDateTime lastUpdatedAt;

    // 관계 정보
    private String parentTaskId;  // refine 태스크의 경우 preview 태스크 ID

    // 결과 정보
    private String thumbnailUrl;
    private MeshyAPI.TaskStatus.ModelUrls modelUrls;
    private String errorMessage;

    // 성능 정보
    private long elapsedTimeMs;

}
