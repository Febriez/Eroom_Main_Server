package com.febrie.dto;

import com.google.gson.JsonObject;
import lombok.Getter;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

/**
 * 유연한 커스텀 로그 형식을 위한 FirebaseLogData 구현체
 * 사용자가 JsonObject를 통해 원하는 데이터 구조를 직접 정의할 수 있습니다.
 */
@Getter
public class CustomLogData extends FirebaseLogData {

    /**
     * -- GETTER --
     * 커스텀 데이터를 반환합니다.
     */
    private final JsonObject customData;

    /**
     * 커스텀 로그 데이터 생성자
     *
     * @param uuid       사용자 UUID
     * @param puid       프로젝트 UUID
     * @param status     상태 값
     * @param customData 커스텀 데이터 JSON 객체
     */
    private CustomLogData(String uuid, String puid, String status, JsonObject customData) {
        super(uuid, puid, status);
        this.customData = customData;
    }

    /**
     * 커스텀 로그 생성 팩토리 메서드
     *
     * @param uuid       사용자 UUID
     * @param puid       프로젝트 UUID
     * @param status     상태 값
     * @param customData 커스텀 데이터 JSON 객체
     * @return 새로운 CustomLogData 인스턴스
     */
    @NotNull
    @Contract("_, _, _, _ -> new")
    public static CustomLogData create(String uuid, String puid, String status, JsonObject customData) {
        return new CustomLogData(uuid, puid, status, customData);
    }

    /**
     * 성공 상태의 커스텀 로그 생성 팩토리 메서드
     *
     * @param uuid       사용자 UUID
     * @param puid       프로젝트 UUID
     * @param customData 커스텀 데이터 JSON 객체
     * @return 새로운 CustomLogData 인스턴스
     */
    @NotNull
    @Contract("_, _, _ -> new")
    public static CustomLogData success(String uuid, String puid, JsonObject customData) {
        return new CustomLogData(uuid, puid, "SUCCESS", customData);
    }

    /**
     * 에러 상태의 커스텀 로그 생성 팩토리 메서드
     *
     * @param uuid       사용자 UUID
     * @param puid       프로젝트 UUID
     * @param customData 커스텀 데이터 JSON 객체
     * @return 새로운 CustomLogData 인스턴스
     */
    @NotNull
    @Contract("_, _, _ -> new")
    public static CustomLogData error(String uuid, String puid, JsonObject customData) {
        return new CustomLogData(uuid, puid, "ERROR", customData);
    }

    @Override
    @NotNull
    public JsonObject toJson() {
        JsonObject json = createBaseJson();

        // 커스텀 데이터의 모든 키-값 쌍을 기본 JSON에 추가
        for (String key : customData.keySet()) {
            json.add(key, customData.get(key));
        }

        return json;
    }

}
