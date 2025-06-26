package com.febrie.eroom.service.room;

import com.febrie.eroom.model.RoomCreationRequest;
import com.febrie.eroom.model.RoomCreationResponse;

public interface RoomService {
    RoomCreationResponse createRoom(RoomCreationRequest request, String ruid);
}