package com.febrie.dto;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;

/**
 * 방 생성 요청 데이터를 위한 레코드 클래스
 * JSON 형식으로 직접 변환 가능한 구조를 제공
 */
public record JsonRoomData(
        String uuid,
        String puid,
        String theme,
        String[] keywords,
        String room_URL
) {
    /**
     * JSON 엘리먼트로부터 JsonRoomData 객체를 생성합니다.
     *
     * @param element JSON 엘리먼트
     * @param puid    프로젝트 ID
     * @return 생성된 JsonRoomData 객체
     */
    @NotNull
    public static JsonRoomData fromJson(@NotNull JsonElement element, String puid) {
        try {
            JsonObject data = element.getAsJsonObject();

            // uuid 필드 검증
            if (!data.has("uuid") || data.get("uuid").isJsonNull()) {
                throw new IllegalArgumentException("uuid field is missing or null");
            }
            String uuid = data.get("uuid").getAsString();

            // theme 필드 검증
            if (!data.has("theme") || data.get("theme").isJsonNull()) {
                throw new IllegalArgumentException("theme field is missing or null");
            }
            String theme = data.get("theme").getAsString();

            // keywords 필드 검증
            if (!data.has("keywords") || !data.get("keywords").isJsonArray()) {
                throw new IllegalArgumentException("keywords field is missing or not an array");
            }
            String[] keywords = data.getAsJsonArray("keywords").asList().stream()
                    .map(JsonElement::getAsString)
                    .toArray(String[]::new);

            // room_URL 필드 검증
            if (!data.has("room_URL") || data.get("room_URL").isJsonNull()) {
                throw new IllegalArgumentException("room_URL field is missing or null");
            }
            String roomUrl = data.get("room_URL").getAsString();

            return new JsonRoomData(uuid, puid, theme, keywords, roomUrl);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse JsonRoomData: " + e.getMessage(), e);
        }
    }

    /**
     * 객체를 JsonObject로 변환합니다.
     *
     * @return JsonObject 형태의 객체
     */
    @NotNull
    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("uuid", uuid);
        json.addProperty("puid", puid);
        json.addProperty("theme", theme);
        json.addProperty("room_URL", room_URL);

        JsonArray keywordsArray = new JsonArray();
        for (String keyword : keywords) {
            keywordsArray.add(keyword);
        }
        json.add("keywords", keywordsArray);

        return json;
    }

    @NotNull
    @Override
    public String toString() {
        return String.format("""
                uuid: %s
                puid: %s
                theme: %s
                keywords: %s
                room_URL: %s
                """, uuid, puid, theme, String.join(", ", keywords), room_URL);
    }
}
