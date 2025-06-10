package com.febrie.eroom.model;

import com.google.gson.JsonObject;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RoomCreationResponse {
    private String uuid;
    private String puid;
    private String theme;
    private String[] keywords;
    private String difficulty; // 추가
    private String roomPrefab;
    private JsonObject scenario;
    private String gameManagerScript;
    private List<String> objectScripts;
    private JsonObject modelTracking;
    private boolean success;
    private String errorMessage;
}