package com.flipkart.grayskull.entities;

import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * MongoDB mapping for {@link com.flipkart.grayskull.spi.models.WorkloadHeartbeat} (the delivery ledger).
 * Declares the collection and its indexes; records are written by upsert in
 * {@link com.flipkart.grayskull.spimpl.repositories.WorkloadHeartbeatRepositoryImpl}.
 * <ul>
 *   <li>Unique key {@code (workload, projectId, secretName, secretVersion, address)}: one record per secret
 *       version per client (pod or VM) of a workload.</li>
 *   <li>Indexed by secret and version, by principal and by address (HLD: "index it by secret, version,
 *       principal and address").</li>
 *   <li>TTL on {@code lastSeen}: a version no client reports any more expires after 3 days
 *       (heartbeats are daily, so one missed heartbeat does not drop a record).</li>
 * </ul>
 */
@Getter
@NoArgsConstructor
@Document(collection = WorkloadHeartbeatEntity.COLLECTION)
@CompoundIndex(name = "workload_secret_version_address_idx",
        def = "{'workload': 1, 'projectId': 1, 'secretName': 1, 'secretVersion': 1, 'address': 1}", unique = true)
@CompoundIndex(name = "secret_version_idx", def = "{'projectId': 1, 'secretName': 1, 'secretVersion': 1}")
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

    @Indexed(name = "principal_idx")
    private String userId;

    @Indexed(name = "address_idx")
    private String address;

    private Instant firstSeen;

    @Indexed(name = "last_seen_ttl_idx", expireAfter = "3d")
    private Instant lastSeen;
}
