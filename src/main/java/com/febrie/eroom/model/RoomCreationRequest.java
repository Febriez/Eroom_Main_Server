package com.febrie.eroom.model;

import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.Nullable;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RoomCreationRequest {
    private String uuid;
    private String theme;
    private String[] keywords;
    private String difficulty;

    // 새로 추가된 필드
    @SerializedName("existing_objects")
    private List<ExistingObject> existingObjects;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExistingObject {
        private String name;
        private String id;
    }

    @Nullable
    public String getValidatedDifficulty() {
        if (difficulty == null || difficulty.trim().isEmpty()) {
            return "normal";
        }

        String normalized = difficulty.trim().toLowerCase();
        return switch (normalized) {
            case "easy", "normal", "hard" -> normalized;
            default -> "normal";
        };
    }

    // existing_objects가 null인 경우 빈 리스트 반환
    public List<ExistingObject> getExistingObjectsSafe() {
        return existingObjects != null ? existingObjects : List.of();
    }
}