package com.febrie.dto;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;

public record NecessaryData(String uuid, String puid, String theme, String[] keywords) {
    @NotNull
    public static NecessaryData fromJson(@NotNull JsonElement d, String puid) {
        try {
            JsonObject data = d.getAsJsonObject();

            // uuid 필드 검증
            JsonElement uuidElement = data.get("uuid");
            if (uuidElement == null) {
                throw new IllegalArgumentException("uuid field is missing");
            }
            String uuid = uuidElement.getAsString();

            // theme 필드 검증
            JsonElement themeElement = data.get("theme");
            if (themeElement == null) {
                throw new IllegalArgumentException("theme field is missing");
            }
            String theme = themeElement.getAsString();

            // keywords 필드 검증
            JsonElement keywordsElement = data.get("keywords");
            if (keywordsElement == null) {
                throw new IllegalArgumentException("keywords field is missing");
            }
            if (!keywordsElement.isJsonArray()) {
                throw new IllegalArgumentException("keywords field must be an array");
            }

            String[] keywords = keywordsElement.getAsJsonArray().asList().stream()
                    .map(JsonElement::getAsString)
                    .toArray(String[]::new);

            return new NecessaryData(uuid, puid, theme, keywords);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse NecessaryData: " + e.getMessage(), e);
        }
    }

    @NotNull
    @Override
    public String toString() {
        return String.format("""
                uuid: %s
                puid: %s
                theme: %s
                keywords: %s
                """, uuid, puid, theme, String.join(", ", keywords));
    }
}
