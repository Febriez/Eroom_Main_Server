package com.febrie.dto;

import com.google.gson.JsonObject;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

public record ProcessResult(String combinedLog, JsonObject scenarioResult, JsonObject scriptResult) {

    @NotNull
    @Contract("_, _, _ -> new")
    public static ProcessResult success(String log, JsonObject scenario, JsonObject script) {
        return new ProcessResult(log, scenario, script);
    }

    @NotNull
    @Contract("_ -> new")
    public static ProcessResult error(String errorMessage) {
        return new ProcessResult("ERROR: " + errorMessage, new JsonObject(), new JsonObject());
    }

    @NotNull
    @Contract("_, _ -> new")
    public static ProcessResult error(String partialLog, String errorMessage) {
        return new ProcessResult(partialLog + "\n[Error] " + errorMessage, new JsonObject(), new JsonObject());
    }

    public boolean isSuccess() {
        return !combinedLog.startsWith("ERROR:") && !combinedLog.contains("\n[Error] ");
    }
}