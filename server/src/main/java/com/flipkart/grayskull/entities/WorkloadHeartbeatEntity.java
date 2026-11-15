package com.flipkart.grayskull.entities;

import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * MongoDB mapping for {@link com.flipkart.grayskull.spi.models.WorkloadHeartbeat}.
 * Declares the collection and its indexes; records are written by upsert in
 * {@link com.flipkart.grayskull.spimpl.repositories.WorkloadHeartbeatRepositoryImpl}.
 * <ul>
 *   <li>Unique key {@code (workload, projectId, secretName, secretVersion)}: one record per secret version a
 *       workload holds; a workload mid-rotation has one record per version.</li>
 *   <li>{@code (projectId, secretName)}: which workloads hold a secret, and at which version.</li>
 *   <li>TTL on {@code lastSeen}: a version no instance reports any more expires after 3 days
 *       (heartbeats are daily, so one missed heartbeat does not drop a record).</li>
 * </ul>
 */
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
    private Boolean refreshHook;
    private String sdkVersion;
    private String userId;
    private Instant firstSeen;

    @Indexed(name = "last_seen_ttl_idx", expireAfter = "3d")
    private Instant lastSeen;
}
