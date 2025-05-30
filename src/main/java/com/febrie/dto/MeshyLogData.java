package com.febrie.dto;

import com.google.gson.JsonObject;
import lombok.Getter;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

/**
 * Meshy API 관련 로그를 위한 구체적인 FirebaseLogData 구현체
 */
@Getter
public class MeshyLogData extends FirebaseLogData {

    /**
     * -- GETTER --
     *  태스크 ID를 반환합니다.
     *
     * @return 태스크 ID
     */
    private final String taskId;
    /**
     * -- GETTER --
     *  태스크 타입을 반환합니다.
     *
     * @return 태스크 타입
     */
    private final String taskType;
    /**
     * -- GETTER --
     *  프롬프트를 반환합니다.
     *
     * @return 프롬프트
     */
    private final String prompt;
    /**
     * -- GETTER --
     *  태스크 세부 정보를 반환합니다.
     *
     * @return 태스크 세부 정보
     */
    private final String taskDetails;
    /**
     * -- GETTER --
     *  진행률을 반환합니다.
     *
     * @return 진행률
     */
    private final int progress;

    /**
     * Meshy API 로그 생성자
     *
     * @param uuid        사용자 UUID
     * @param taskId      Meshy 태스크 ID
     * @param taskType    태스크 타입 (preview, refine 등)
     * @param status      상태 (SUCCESS, ERROR, PROCESSING 등)
     * @param prompt      사용된 프롬프트
     * @param progress    진행률 (0-100)
     * @param taskDetails 태스크 세부 정보 (JSON 문자열)
     */
    private MeshyLogData(String uuid, String taskId, String taskType,
                         String status, String prompt, int progress, String taskDetails) {
        super(uuid, taskId, status);  // taskId를 puid 위치에 사용
        this.taskId = taskId;
        this.taskType = taskType;
        this.prompt = prompt;
        this.progress = progress;
        this.taskDetails = taskDetails;
    }

    /**
     * Meshy 태스크 로그 생성 팩토리 메서드
     *
     * @param uuid        사용자 UUID
     * @param taskId      태스크 ID
     * @param taskType    태스크 타입
     * @param prompt      사용된 프롬프트
     * @param progress    진행률
     * @param taskDetails 태스크 세부 정보
     * @return 새로운 MeshyLogData 인스턴스
     */
    @NotNull
    @Contract("_, _, _, _, _, _ -> new")
    public static MeshyLogData progress(String uuid, String taskId, String taskType,
                                        String prompt, int progress, String taskDetails) {
        return new MeshyLogData(uuid, taskId, taskType, "PROCESSING", prompt, progress, taskDetails);
    }

    /**
     * 완료된 Meshy 태스크 로그 생성 팩토리 메서드
     *
     * @param uuid        사용자 UUID
     * @param taskId      태스크 ID
     * @param taskType    태스크 타입
     * @param prompt      사용된 프롬프트
     * @param taskDetails 태스크 세부 정보
     * @return 새로운 MeshyLogData 인스턴스
     */
    @NotNull
    @Contract("_, _, _, _, _ -> new")
    public static MeshyLogData completed(String uuid, String taskId, String taskType,
                                         String prompt, String taskDetails) {
        return new MeshyLogData(uuid, taskId, taskType, "SUCCESS", prompt, 100, taskDetails);
    }

    /**
     * 실패한 Meshy 태스크 로그 생성 팩토리 메서드
     *
     * @param uuid         사용자 UUID
     * @param taskId       태스크 ID
     * @param taskType     태스크 타입
     * @param prompt       사용된 프롬프트
     * @param errorDetails 에러 세부 정보
     * @return 새로운 MeshyLogData 인스턴스
     */
    @NotNull
    @Contract("_, _, _, _, _ -> new")
    public static MeshyLogData error(String uuid, String taskId, String taskType,
                                     String prompt, String errorDetails) {
        return new MeshyLogData(uuid, taskId, taskType, "ERROR", prompt, 0, errorDetails);
    }

    @Override
    @NotNull
    public JsonObject toJson() {
        JsonObject json = createBaseJson();
        json.addProperty("taskId", taskId);
        json.addProperty("taskType", taskType);
        json.addProperty("prompt", prompt);
        json.addProperty("progress", progress);
        json.addProperty("taskDetails", taskDetails);
        return json;
    }

}