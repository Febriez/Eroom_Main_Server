package com.febrie.dto;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;

public record NecessaryData(String uuid, String puid, long timestamp, String theme, String[] keywords) {
    @NotNull
    public static NecessaryData fromJson(@NotNull JsonObject data) {
        return new NecessaryData(
                data.get("uuid").getAsString(),
                data.get("puid").getAsString(),
                data.get("timestamp").getAsLong(),
                data.get("theme").getAsString(),
                data.get("keywords").getAsJsonArray().asList().stream().map(JsonElement::getAsString).toArray(String[]::new));
    }
}
