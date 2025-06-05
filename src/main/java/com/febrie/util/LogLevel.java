package com.febrie.util;

/**
 * 로그 레벨을 정의하는 열거형
 */
public enum LogLevel {
    INFO,
    WARN,
    ERROR,
    DEBUG,
    TRACE;

    @Override
    public String toString() {
        return switch (this) {
            case INFO -> "INFO";
            case WARN -> "WARN";
            case ERROR -> "ERROR";
            case DEBUG -> "DEBUG";
            case TRACE -> "TRACE";
        };
    }
}
