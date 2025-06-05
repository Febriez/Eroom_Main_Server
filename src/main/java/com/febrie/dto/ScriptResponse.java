package com.febrie.dto;

import com.google.gson.JsonObject;
import lombok.Data;

/**
 * 스크립트 생성 응답 DTO
 */
@Data
public class ScriptResponse {

    /**
     * 프로젝트 고유 식별자
     */
    private String puid;

    /**
     * 처리 상태 (success, error, not_found 등)
     */
    private String status;

    /**
     * 생성된 스크립트 데이터
     */
    private JsonObject scriptData;

    /**
     * 처리 시간 (밀리초)
     */
    private long processingTimeMs;

    /**
     * 생성 시간 (yyyy-MM-dd HH:mm:ss 형식)
     */
    private String createdAt;

    /**
     * 오류 메시지 (실패 시)
     */
    private String errorMessage;
}
