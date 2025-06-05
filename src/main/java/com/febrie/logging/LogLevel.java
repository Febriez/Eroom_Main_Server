package com.febrie.logging;

/**
 * 로그 레벨을 정의하는 Enum
 */
public enum LogLevel {
    INFO("[INFO] "),
    WARN("[WARN] "),
    ERROR("[ERROR] "),
    DEBUG("[DEBUG] ");

    private final String prefix;

    LogLevel(String prefix) {
        this.prefix = prefix;
    }

    public String getPrefix() {
        return prefix;
    }
}
