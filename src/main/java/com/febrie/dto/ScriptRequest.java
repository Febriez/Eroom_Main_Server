package com.febrie.dto;

import com.google.gson.JsonObject;
import lombok.Data;

/**
 * 스크립트 생성 요청 DTO
 */
@Data
public class ScriptRequest {

    /**
     * 프로젝트 고유 식별자
     */
    private String puid;

    /**
     * 스크립트 생성을 위한 퍼즐 데이터
     */
    private JsonObject puzzleData;

    /**
     * 방 프리팹 URL
     */
    private String roomPrefabUrl;
}
