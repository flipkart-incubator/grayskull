package com.flipkart.grayskull.spi.models;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.Instant;

/** A secret version held by a workload, from its latest SDK heartbeats. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class WorkloadHeartbeat {

    /** Workload identity from the {@code Grayskull-Workload} header. */
    private String workload;

    private String projectId;

    private String secretName;

    /** A version of the secret the workload holds. */
    private Integer secretVersion;

    /** Newest version a refresh hook succeeded for; null when no hook is registered. */
    private Integer refreshHookAppliedVersion;

    /** SDK name and version from the {@code User-Agent} header. */
    private String sdkVersion;

    /** Authenticated caller. */
    private String userId;

    /** Time of the latest heartbeat that reported this secret version. */
    private Instant lastSeen;
}
