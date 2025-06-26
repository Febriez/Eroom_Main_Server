package com.febrie.eroom.service.queue;

import com.febrie.eroom.model.RoomCreationRequest;

public interface QueueManager {
    String submitRequest(RoomCreationRequest request);

    QueueStatus getQueueStatus();

    void shutdown();

    record QueueStatus(int queued, int active, int completed, int maxConcurrent) {
    }
}