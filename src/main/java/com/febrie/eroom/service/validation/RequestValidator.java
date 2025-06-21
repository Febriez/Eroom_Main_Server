package com.febrie.eroom.service.validation;

import com.febrie.eroom.model.RoomCreationRequest;

public interface RequestValidator {
    void validate(RoomCreationRequest request) throws IllegalArgumentException;
}