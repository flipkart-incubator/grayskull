package com.flipkart.grayskull.service.interfaces;

import com.flipkart.grayskull.models.dto.request.HeldSecret;

import java.util.List;

/**
 * Writes the delivery ledger: which client (workload and address) holds which secret version.
 * <ul>
 *   <li>Sightings: telemetry recorded from the SDK's own requests (secret fetch, refresh-hook poll), using the
 *       workload headers sent with them (HLD: "Source headers ... are deduped and persisted").</li>
 *   <li>Heartbeat: the SDK's 24-hour report, so long-lived processes stay in the ledger.</li>
 * </ul>
 */
public interface HeartbeatService {

    /**
     * Upserts one record per held secret version for the calling client and refreshes its {@code lastSeen}.
     * Reads no secret data and writes no audit entries.
     *
     * @param workload   workload identity ({@code Grayskull-Workload})
     * @param sdkVersion SDK name and version ({@code User-Agent}); may be null
     * @param address    client address (pod or VM)
     * @param secrets    the held secrets
     */
    void recordHeartbeat(String workload, String sdkVersion, String address, List<HeldSecret> secrets);

    /**
     * Sighting of a secret fetch: the client was just served {@code version}. Synchronous, deduplicated (written at
     * most once an hour per client and secret version on this server) and best effort: a failed write is logged and
     * never fails the fetch. Does nothing when {@code workload} is missing.
     */
    void recordFetch(String workload, String sdkVersion, String address, String projectId, String secretName,
                     int version);

    /**
     * Sighting of a refresh-hook poll ({@code POST /v1/secrets/batch}): like {@link #recordFetch}, and the record
     * also gets {@code refreshHook = true}.
     */
    void recordHookPoll(String workload, String sdkVersion, String address, String projectId, String secretName,
                        int version);
}
