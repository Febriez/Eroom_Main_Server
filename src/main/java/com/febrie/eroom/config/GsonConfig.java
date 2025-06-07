package com.febrie.eroom.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public class GsonConfig {

    public Gson createGson() {
        return new GsonBuilder()
                .setPrettyPrinting()
                .create();
    }
}
