package com.febrie.eroom.service.room;

import com.febrie.eroom.model.RoomCreationRequest;
import com.google.gson.JsonObject;

public interface RoomService {
    JsonObject createRoom(RoomCreationRequest request, String ruid);
}