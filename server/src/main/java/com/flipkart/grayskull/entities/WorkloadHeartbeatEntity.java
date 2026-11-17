package com.flipkart.grayskull.entities;

import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** MongoDB mapping for WorkloadHeartbeat; records are written by upsert. */
@Getter
@NoArgsConstructor
@Document(collection = WorkloadHeartbeatEntity.COLLECTION)
@CompoundIndex(name = "workload_secret_version_idx",
        def = "{'workload': 1, 'projectId': 1, 'secretName': 1, 'secretVersion': 1}", unique = true)
@CompoundIndex(name = "project_secret_idx", def = "{'projectId': 1, 'secretName': 1}")
public class WorkloadHeartbeatEntity {

    public static final String COLLECTION = "workloadHeartbeat";

    @Id
    private String id;
    private String workload;
    private String projectId;
    private String secretName;
    private Integer secretVersion;
    private Integer refreshHookAppliedVersion;
    private String sdkVersion;
    private String userId;
    private Instant firstSeen;

    @Indexed(name = "last_seen_ttl_idx", expireAfter = "3d")
    private Instant lastSeen;
}
