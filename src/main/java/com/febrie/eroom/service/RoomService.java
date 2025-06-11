package com.febrie.eroom.service;

import com.febrie.eroom.model.RoomCreationRequest;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public interface RoomService {

    Logger log = LoggerFactory.getLogger(RoomService.class);

    JsonObject createRoom(RoomCreationRequest request, String ruid);
}