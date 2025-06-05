package com.febrie.logging;

/**
 * 로그 처리 단계를 정의하는 Enum
 */
public enum LogProcessType {
    INITIAL_REQUEST("초기 요청 로깅 시작"),
    SCENARIO_GENERATION("시나리오 생성 시작"),
    SCENARIO_API_REQUEST("Claude API 요청 중..."),
    SCENARIO_API_RESPONSE("Claude API 응답 수신"),
    SCRIPT_GENERATION("스크립트 생성 시작"),
    GAME_MANAGER_GENERATION("GameManager 스크립트 생성 시작"),
    INDIVIDUAL_SCRIPT_GENERATION("개별 오브젝트 스크립트 생성 시작"),
    MODEL_GENERATION("3D 모델 생성 시작"),
    MODEL_PROCESSING("Meshy 키워드 처리 시작"),
    COMPLETION("처리 완료");

    private final String message;

    LogProcessType(String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }
}
