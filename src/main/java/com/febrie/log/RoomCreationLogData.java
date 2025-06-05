package com.febrie.log;

import com.google.gson.JsonObject;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

/**
 * 룸 생성 프로세스의 로그 데이터 구조
 */
public class RoomCreationLogData extends FirebaseLogData {

    private final String theme;
    private final String logs;
    private final JsonObject scenarioData;
    private final JsonObject scriptData;
    private final String errorMessage;

    /**
     * 성공 상태의 룸 생성 로그 데이터 생성자
     *
     * @param uuid          사용자 UUID
     * @param puid          프로젝트 UUID
     * @param theme         테마
     * @param logs          전체 로그 내용
     * @param scenarioData  시나리오 데이터
     * @param scriptData    스크립트 데이터
     */
    private RoomCreationLogData(String uuid, String puid, String theme, String logs,
                                JsonObject scenarioData, JsonObject scriptData) {
        super(uuid, puid, "SUCCESS");
        this.theme = theme;
        this.logs = logs;
        this.scenarioData = scenarioData;
        this.scriptData = scriptData;
        this.errorMessage = null;
    }

    /**
     * 에러 상태의 룸 생성 로그 데이터 생성자
     *
     * @param uuid         사용자 UUID
     * @param puid         프로젝트 UUID
     * @param theme        테마
     * @param logs         전체 로그 내용
     * @param errorMessage 에러 메시지
     */
    private RoomCreationLogData(String uuid, String puid, String theme, String logs, String errorMessage) {
        super(uuid, puid, "ERROR");
        this.theme = theme;
        this.logs = logs;
        this.scenarioData = new JsonObject();
        this.scriptData = new JsonObject();
        this.errorMessage = errorMessage;
    }

    /**
     * 성공 상태의 룸 생성 로그 데이터 생성 팩토리 메서드
     *
     * @param uuid          사용자 UUID
     * @param puid          프로젝트 UUID
     * @param theme         테마
     * @param logs          전체 로그 내용
     * @param scenarioData  시나리오 데이터
     * @param scriptData    스크립트 데이터
     * @return 새로운 RoomCreationLogData 인스턴스
     */
    @NotNull
    @Contract("_, _, _, _, _, _ -> new")
    public static RoomCreationLogData success(String uuid, String puid, String theme, String logs,
                                           JsonObject scenarioData, JsonObject scriptData) {
        return new RoomCreationLogData(uuid, puid, theme, logs, scenarioData, scriptData);
    }

    /**
     * 에러 상태의 룸 생성 로그 데이터 생성 팩토리 메서드
     *
     * @param uuid         사용자 UUID
     * @param puid         프로젝트 UUID
     * @param theme        테마
     * @param logs         전체 로그 내용
     * @return 새로운 RoomCreationLogData 인스턴스
     */
    @NotNull
    @Contract("_, _, _, _ -> new")
    public static RoomCreationLogData error(String uuid, String puid, String theme, String logs) {
        return new RoomCreationLogData(uuid, puid, theme, logs, "Error during room creation process");
    }

    /**
     * 상세 에러 메시지가 있는 에러 상태의 룸 생성 로그 데이터 생성 팩토리 메서드
     *
     * @param uuid         사용자 UUID
     * @param puid         프로젝트 UUID
     * @param theme        테마
     * @param logs         전체 로그 내용
     * @param errorMessage 상세 에러 메시지
     * @return 새로운 RoomCreationLogData 인스턴스
     */
    @NotNull
    @Contract("_, _, _, _, _ -> new")
    public static RoomCreationLogData error(String uuid, String puid, String theme, String logs, String errorMessage) {
        return new RoomCreationLogData(uuid, puid, theme, logs, errorMessage);
    }

    @Override
    @NotNull
    public JsonObject toJson() {
        JsonObject json = createBaseJson();
        json.addProperty("theme", theme);
        json.addProperty("logs", logs);

        if (isSuccess()) {
            json.add("scenarioData", scenarioData);
            json.add("scriptData", scriptData);
        } else {
            json.addProperty("errorMessage", errorMessage);
        }

        return json;
    }
}
