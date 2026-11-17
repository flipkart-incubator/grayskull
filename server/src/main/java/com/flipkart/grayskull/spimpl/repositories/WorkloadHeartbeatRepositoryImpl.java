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

/** Upserts heartbeat records in one unordered bulk. */
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
        return new Update()
                .set("refreshHookAppliedVersion", heartbeat.getRefreshHookAppliedVersion())
                .set("sdkVersion", heartbeat.getSdkVersion())
                .set("userId", heartbeat.getUserId())
                .set("lastSeen", heartbeat.getLastSeen())
                .setOnInsert("firstSeen", heartbeat.getLastSeen());
    }
}
