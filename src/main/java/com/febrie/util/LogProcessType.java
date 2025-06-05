package com.febrie.util;

/**
 * 로그 처리 유형을 정의하는 열거형
 */
public enum LogProcessType {
    INITIAL_REQUEST,
    SCENARIO_GENERATION,
    SCENARIO_API_REQUEST,
    SCENARIO_API_RESPONSE,
    GAME_MANAGER_GENERATION,
    INDIVIDUAL_SCRIPT_GENERATION,
    MODEL_GENERATION,
    COMPLETION,
    CALLBACK_SUCCESS,
    CALLBACK_FAILURE;

    @Override
    public String toString() {
        return switch (this) {
            case INITIAL_REQUEST -> "초기 요청";
            case SCENARIO_GENERATION -> "시나리오 생성";
            case SCENARIO_API_REQUEST -> "시나리오 API 요청";
            case SCENARIO_API_RESPONSE -> "시나리오 API 응답";
            case GAME_MANAGER_GENERATION -> "GameManager 생성";
            case INDIVIDUAL_SCRIPT_GENERATION -> "개별 스크립트 생성";
            case MODEL_GENERATION -> "3D 모델 생성";
            case COMPLETION -> "완료";
            case CALLBACK_SUCCESS -> "콜백 성공";
            case CALLBACK_FAILURE -> "콜백 실패";
        };
    }
}
