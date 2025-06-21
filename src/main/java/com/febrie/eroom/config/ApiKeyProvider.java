package com.febrie.eroom.config;

public interface ApiKeyProvider {
    String getAnthropicKey();

    String getMeshyKey(int index);
}