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

    @SerializedName("existing_objects")
    private List<ExistingObject> existingObjects;

    @SerializedName("is_free_modeling")
    private Boolean isFreeModeling;

    public boolean isFreeModeling() {
        return isFreeModeling != null && isFreeModeling;
    }

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

    public List<ExistingObject> getExistingObjectsSafe() {
        return existingObjects != null ? existingObjects : List.of();
    }
}