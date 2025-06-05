package com.febrie.dto;

import lombok.Data;

/**
 * 3D 모델 생성 응답 DTO
 */
@Data
public class ModelResponse {

    /**
     * 프로젝트 고유 식별자
     */
    private String puid;

    /**
     * 처리 상태 (PROCESSING, COMPLETED, FAILED 등)
     */
    private String status;

    /**
     * 총 모델 수
     */
    private int totalModels;

    /**
     * 예상 완료 시간 (yyyy-MM-dd HH:mm:ss 형식)
     */
    private String estimatedCompletionTime;
}