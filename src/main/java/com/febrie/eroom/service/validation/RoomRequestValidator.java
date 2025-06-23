package com.febrie.eroom.service.validation;

import com.febrie.eroom.model.RoomCreationRequest;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

public class RoomRequestValidator implements RequestValidator {

    // 유효한 난이도 값들
    private static final Set<String> VALID_DIFFICULTIES = Set.of("easy", "normal", "hard");

    // 오류 메시지 상수
    private static final String ERROR_EMPTY_UUID = "UUID가 비어있습니다";
    private static final String ERROR_EMPTY_THEME = "테마가 비어있습니다";
    private static final String ERROR_EMPTY_KEYWORDS = "키워드가 비어있습니다";
    private static final String ERROR_EMPTY_KEYWORD_ITEM = "빈 키워드가 포함되어 있습니다";
    private static final String ERROR_INVALID_DIFFICULTY = "유효하지 않은 난이도입니다. easy, normal, hard 중 하나를 선택하세요.";

    /**
     * 방 생성 요청을 검증합니다.
     * UUID, 테마, 키워드, 난이도를 순차적으로 검증합니다.
     */
    @Override
    public void validate(RoomCreationRequest request) throws IllegalArgumentException {
        validateUuid(request);
        validateTheme(request);
        validateKeywords(request);
        validateDifficulty(request);
    }

    /**
     * UUID를 검증합니다.
     * null이거나 빈 문자열인 경우 예외를 발생시킵니다.
     */
    private void validateUuid(@NotNull RoomCreationRequest request) {
        validateNotEmpty(request.getUuid(), ERROR_EMPTY_UUID);
    }

    /**
     * 테마를 검증합니다.
     * null이거나 빈 문자열인 경우 예외를 발생시킵니다.
     */
    private void validateTheme(@NotNull RoomCreationRequest request) {
        validateNotEmpty(request.getTheme(), ERROR_EMPTY_THEME);
    }

    /**
     * 키워드 배열을 검증합니다.
     * 배열이 null이거나 비어있는 경우, 또는 빈 키워드가 포함된 경우 예외를 발생시킵니다.
     */
    private void validateKeywords(@NotNull RoomCreationRequest request) {
        validateKeywordsNotEmpty(request);
        validateEachKeyword(request.getKeywords());
    }

    /**
     * 키워드 배열이 비어있지 않은지 검증합니다.
     */
    private void validateKeywordsNotEmpty(@NotNull RoomCreationRequest request) {
        if (request.getKeywords() == null || request.getKeywords().length == 0) {
            throw new IllegalArgumentException(ERROR_EMPTY_KEYWORDS);
        }
    }

    /**
     * 각 키워드가 유효한지 검증합니다.
     */
    private void validateEachKeyword(@NotNull String[] keywords) {
        for (String keyword : keywords) {
            validateNotEmpty(keyword, ERROR_EMPTY_KEYWORD_ITEM);
        }
    }

    /**
     * 난이도를 검증합니다.
     * null이 아닌 경우, 유효한 값인지 확인합니다.
     */
    private void validateDifficulty(@NotNull RoomCreationRequest request) {
        if (request.getDifficulty() != null) {
            String normalizedDifficulty = request.getDifficulty().trim().toLowerCase();
            if (!VALID_DIFFICULTIES.contains(normalizedDifficulty)) {
                throw new IllegalArgumentException(ERROR_INVALID_DIFFICULTY);
            }
        }
    }

    /**
     * 문자열이 비어있지 않은지 검증합니다.
     * null이거나 공백만 있는 경우 예외를 발생시킵니다.
     */
    private void validateNotEmpty(String value, String errorMessage) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(errorMessage);
        }
    }
}