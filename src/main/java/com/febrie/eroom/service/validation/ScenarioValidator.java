package com.febrie.eroom.service.validation;

import com.google.gson.JsonObject;

public interface ScenarioValidator {
    void validate(JsonObject scenario) throws RuntimeException;
}