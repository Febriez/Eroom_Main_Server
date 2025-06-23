package com.febrie.eroom.service.validation;

import com.febrie.eroom.model.RoomCreationRequest;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

public class RoomRequestValidator implements RequestValidator {

    @Override
    public void validate(RoomCreationRequest request) throws IllegalArgumentException {
        validateUuid(request);
        validateTheme(request);
        validateKeywords(request);
        validateDifficulty(request);
    }

    private void validateUuid(@NotNull RoomCreationRequest request) {
        if (request.getUuid() == null || request.getUuid().trim().isEmpty()) {
            throw new IllegalArgumentException("UUID가 비어있습니다");
        }
    }

    private void validateTheme(@NotNull RoomCreationRequest request) {
        if (request.getTheme() == null || request.getTheme().trim().isEmpty()) {
            throw new IllegalArgumentException("테마가 비어있습니다");
        }
    }

    private void validateKeywords(@NotNull RoomCreationRequest request) {
        if (request.getKeywords() == null || request.getKeywords().length == 0) {
            throw new IllegalArgumentException("키워드가 비어있습니다");
        }

        for (String keyword : request.getKeywords()) {
            if (keyword == null || keyword.trim().isEmpty()) {
                throw new IllegalArgumentException("빈 키워드가 포함되어 있습니다");
            }
        }
    }

    private void validateDifficulty(@NotNull RoomCreationRequest request) {
        if (request.getDifficulty() != null) {
            String difficulty = request.getDifficulty().trim().toLowerCase();
            if (!Arrays.asList("easy", "normal", "hard").contains(difficulty)) {
                throw new IllegalArgumentException("유효하지 않은 난이도입니다. easy, normal, hard 중 하나를 선택하세요.");
            }
        }
    }
}