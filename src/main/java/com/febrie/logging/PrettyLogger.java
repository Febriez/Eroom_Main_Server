package com.febrie.logging;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import lombok.Data;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

/**
 * 로그 출력을 시각적으로 향상시키는 유틸리티 클래스
 * 구조화된 로그 및 텍스트 로그를 예쁘게 포맷팅
 */
public class PrettyLogger {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * 텍스트 로그를 예쁘게 포맷팅합니다.
     *
     * @param text 포맷팅할 텍스트 로그
     * @return 포맷팅된 텍스트
     */
    @NotNull
    public static String formatTextLog(@NotNull String text) {
        return String.format("""
                ╔════════════════════════════════════════════════════════════════════
                ║ %s
                ╚════════════════════════════════════════════════════════════════════
                """, text.replace("\n", "\n║ "));
    }

    /**
     * 구조화된 로그 데이터를 예쁘게 포맷팅합니다.
     *
     * @param logData 로그 데이터 맵
     * @return 포맷팅된 JSON 텍스트
     */
    @NotNull
    public static String formatStructuredLog(@NotNull Map<String, Object> logData) {
        JsonObject jsonObject = GSON.toJsonTree(logData).getAsJsonObject();
        String prettyJson = GSON.toJson(jsonObject);

        return String.format("""
                ╔══════ 구조화된 로그 데이터 ═════════════════════════════════════════
                ║ %s
                ╚════════════════════════════════════════════════════════════════════
                """, prettyJson.replace("\n", "\n║ "));
    }

    /**
     * 내부 로그 엔트리 클래스
     * 로그 엔트리 작성을 위한 구조화된 데이터 모델
     */
    @Data
    public static class LogEntry {
        private String uuid;
        private String puid;
        private String theme;
        private String timestamp;
        private String status;
        private String logs;

        /**
         * 로그 엔트리를 Map으로 변환합니다.
         *
         * @return Map 형태의 로그 데이터
         */
        @NotNull
        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("uuid", uuid);
            map.put("puid", puid);
            if (theme != null) {
                map.put("theme", theme);
            }
            map.put("timestamp", timestamp);
            map.put("status", status);
            map.put("logs", logs);
            return map;
        }
    }
}
