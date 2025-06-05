package com.febrie.dto;

import com.google.gson.JsonObject;
import lombok.Data;

/**
 * 3D 모델 생성 요청 DTO
 */
@Data
public class ModelRequest {

    /**
     * 프로젝트 고유 식별자
     */
    private String puid;

    /**
     * 3D 모델 생성을 위한 시나리오 데이터
     * - keywords: 생성할 3D 모델의 키워드 배열
     */
    private JsonObject scenarioData;
}