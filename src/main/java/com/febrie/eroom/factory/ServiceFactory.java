package com.febrie.eroom.factory;

import com.febrie.eroom.service.ai.AiService;
import com.febrie.eroom.service.mesh.MeshService;
import com.febrie.eroom.service.room.RoomService;

public interface ServiceFactory {
    AiService createAiService();
    MeshService createMeshService();
    RoomService createRoomService();
}