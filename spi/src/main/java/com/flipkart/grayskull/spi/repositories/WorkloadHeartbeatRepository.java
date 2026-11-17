package com.flipkart.grayskull.spi.repositories;

import com.flipkart.grayskull.spi.models.WorkloadHeartbeat;

import java.util.List;

/**
 * Data access contract for heartbeat records.
 */
public interface WorkloadHeartbeatRepository {

    /**
     * Inserts or refreshes one record per {@code (workload, projectId, secretName, secretVersion)}.
     * Existing records get the reported hook flag, SDK version, caller and {@code lastSeen};
     * no new record is created for a key that already exists.
     *
     * @param heartbeats the records to upsert, must not be null.
     */
    void upsertAll(List<WorkloadHeartbeat> heartbeats);
}
