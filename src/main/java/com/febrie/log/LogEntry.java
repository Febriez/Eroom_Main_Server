package com.febrie.log;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public record LogEntry(String type, String timestamp, String input, String output) {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @NotNull
    @Contract("_, _, _ -> new")
    public static LogEntry create(String type, String input, String output) {
        return new LogEntry(type, LocalDateTime.now().format(FORMATTER), input, output);
    }

    @NotNull
    @Contract(pure = true)
    public String format() {
        return String.format("""
                ===========================================
                TYPE: %s
                TIMESTAMP: %s
                INPUT: %s
                OUTPUT: %s
                ===========================================
                """, type, timestamp, input, output);
    }
}
