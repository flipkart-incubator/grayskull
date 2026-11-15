package com.flipkart.grayskull.spi.models;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.Instant;

/**
 * Delivery ledger entry: one version of a secret held by one client (pod or VM) of a workload, as last seen in a
 * secret fetch, a refresh-hook poll or an SDK heartbeat. Unique per
 * {@code (workload, projectId, secretName, secretVersion, address)}.
 * <p>
 * This is a plain POJO contract that the server module will implement with framework-specific annotations.
 */
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

    /** Whether the reporting instance has a refresh hook registered for the secret. */
    private Boolean refreshHook;

    /** SDK name and version from the {@code User-Agent} header. */
    private String sdkVersion;

    /** Authenticated caller (principal). */
    private String userId;

    /** Client address (pod or VM IP) as seen by the server. */
    private String address;

    /** Time of the latest sighting or heartbeat that reported this secret version. */
    private Instant lastSeen;
}
