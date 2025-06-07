package com.febrie.eroom.model;

import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RoomCreationRequest {
    private String uuid;
    private String theme;
    private String[] keywords;

    @SerializedName("room_prefab")
    private String roomPrefab;
}
