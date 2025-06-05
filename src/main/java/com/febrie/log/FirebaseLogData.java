package com.febrie.log;

import com.google.gson.JsonObject;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 파이어베이스 로그 데이터를 위한 추상 클래스.
 * 각 클래스에서 이를 상속받아 자체적인 로그 구조를 정의할 수 있습니다.
 */
@Getter
public abstract class FirebaseLogData {

    protected static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    protected String uuid;
    protected String puid;
    protected String timestamp;
    protected String status;

    protected FirebaseLogData(String uuid, String puid, String status) {
        this.uuid = uuid;
        this.puid = puid;
        this.timestamp = LocalDateTime.now().format(FORMATTER);
        this.status = status;
    }

    @NotNull
    public abstract JsonObject toJson();

    @NotNull
    protected JsonObject createBaseJson() {
        JsonObject json = new JsonObject();
        json.addProperty("uuid", uuid);
        json.addProperty("puid", puid);
        json.addProperty("timestamp", timestamp);
        json.addProperty("status", status);
        return json;
    }

    /**
     * 로그의 성공 여부를 반환합니다.
     * 
     * @return 로그가 성공 상태인 경우 true, 그렇지 않으면 false
     */
    public boolean isSuccess() {
        return "success".equalsIgnoreCase(status);
    }

}
