package com.flipkart.grayskull.spimpl.repositories;

import com.flipkart.grayskull.entities.WorkloadHeartbeatEntity;
import com.flipkart.grayskull.spi.models.WorkloadHeartbeat;
import com.flipkart.grayskull.spi.repositories.WorkloadHeartbeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * MongoDB implementation of {@link WorkloadHeartbeatRepository}: one unordered bulk of upserts per call.
 */
@Repository
@RequiredArgsConstructor
public class WorkloadHeartbeatRepositoryImpl implements WorkloadHeartbeatRepository {

    private final MongoTemplate mongoTemplate;

    @Override
    public void upsertAll(List<WorkloadHeartbeat> heartbeats) {
        if (heartbeats.isEmpty()) {
            return;
        }
        BulkOperations bulk = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, WorkloadHeartbeatEntity.class);
        for (WorkloadHeartbeat heartbeat : heartbeats) {
            bulk.upsert(keyOf(heartbeat), updateOf(heartbeat));
        }
        bulk.execute();
    }

    private static Query keyOf(WorkloadHeartbeat heartbeat) {
        return Query.query(Criteria.where("workload").is(heartbeat.getWorkload())
                .and("projectId").is(heartbeat.getProjectId())
                .and("secretName").is(heartbeat.getSecretName())
                .and("secretVersion").is(heartbeat.getSecretVersion()));
    }

    private static Update updateOf(WorkloadHeartbeat heartbeat) {
        Update update = new Update()
                .set("sdkVersion", heartbeat.getSdkVersion())
                .set("userId", heartbeat.getUserId())
                .set("lastSeen", heartbeat.getLastSeen())
                .setOnInsert("firstSeen", heartbeat.getLastSeen());
        // Only heartbeats know whether a refresh hook is registered; a fetch record (null) keeps the stored value.
        if (heartbeat.getRefreshHook() != null) {
            update.set("refreshHook", heartbeat.getRefreshHook());
        }
        return update;
    }
}
