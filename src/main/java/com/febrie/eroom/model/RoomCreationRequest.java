package com.febrie.eroom.model;

import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.Nullable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RoomCreationRequest {
    private String uuid;
    private String theme;
    private String[] keywords;
    private String difficulty; // 추가: "easy", "normal", "hard"

    @SerializedName("room_prefab")
    private String roomPrefab;

    @Nullable
    public String getValidatedDifficulty() {
        if (difficulty == null || difficulty.trim().isEmpty()) {
            return "normal"; // 기본값
        }

        String normalized = difficulty.trim().toLowerCase();
        return switch (normalized) {
            case "easy", "normal", "hard" -> normalized;
            default -> "normal"; // 잘못된 값일 경우 기본값
        };
    }
}