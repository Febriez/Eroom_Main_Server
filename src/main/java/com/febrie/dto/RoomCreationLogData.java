package com.febrie.dto;

import com.google.gson.JsonObject;
import lombok.Getter;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

/**
 * 방 생성 프로세스를 위한 구체적인 FirebaseLogData 구현체
 */
@Getter
public class RoomCreationLogData extends FirebaseLogData {

    private final String theme;
    private final String logs;
    private final JsonObject scenarioData;
    private final JsonObject scriptData;

    /**
     * 성공적인 방 생성 결과를 위한 생성자
     *
     * @param uuid     사용자 UUID
     * @param puid     프로젝트 UUID
     * @param theme    방 테마
     * @param logs     상세 로그 내용
     * @param scenario 시나리오 데이터
     * @param script   스크립트 데이터
     */
    private RoomCreationLogData(String uuid, String puid, String theme,
                                String status, String logs, JsonObject scenario, JsonObject script) {
        super(uuid, puid, status);
        this.theme = theme;
        this.logs = logs;
        this.scenarioData = scenario;
        this.scriptData = script;
    }

    /**
     * 성공 로그 생성 팩토리 메서드
     *
     * @param uuid     사용자 UUID
     * @param puid     프로젝트 UUID
     * @param theme    방 테마
     * @param logs     상세 로그 내용
     * @param scenario 시나리오 데이터
     * @param script   스크립트 데이터
     * @return 새로운 RoomCreationLogData 인스턴스
     */
    @NotNull
    @Contract("_, _, _, _, _, _ -> new")
    public static RoomCreationLogData success(String uuid, String puid, String theme,
                                              String logs, JsonObject scenario, JsonObject script) {
        return new RoomCreationLogData(uuid, puid, theme,
                "SUCCESS", logs, scenario, script);
    }

    /**
     * 에러 로그 생성 팩토리 메서드
     *
     * @param uuid         사용자 UUID
     * @param puid         프로젝트 UUID
     * @param theme        방 테마
     * @param errorMessage 에러 메시지
     * @return 새로운 RoomCreationLogData 인스턴스
     */
    @NotNull
    @Contract("_, _, _, _ -> new")
    public static RoomCreationLogData error(String uuid, String puid, String theme, String errorMessage) {
        return new RoomCreationLogData(uuid, puid, theme,
                "ERROR", errorMessage, new JsonObject(), new JsonObject());
    }

    @Override
    @NotNull
    public JsonObject toJson() {
        JsonObject json = createBaseJson();
        json.addProperty("theme", theme);
        json.addProperty("logs", logs);
        json.add("scenarioData", scenarioData);
        json.add("scriptData", scriptData);
        return json;
    }

}
