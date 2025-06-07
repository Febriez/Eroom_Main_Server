package com.febrie.eroom.service;

import com.febrie.eroom.model.RoomCreationRequest;
import com.google.gson.JsonObject;

public interface RoomService {

    JsonObject createRoom(RoomCreationRequest request);
}
