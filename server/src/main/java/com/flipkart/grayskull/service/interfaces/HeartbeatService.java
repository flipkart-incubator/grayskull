package com.flipkart.grayskull.service.interfaces;

import com.flipkart.grayskull.models.dto.request.HeldSecret;

import java.util.List;

/**
 * Records which secrets, at which versions, running workload instances hold.
 */
public interface HeartbeatService {

    /**
     * Upserts one record per held secret version for the calling workload and refreshes its {@code lastSeen}.
     * Reads no secret data and writes no audit entries.
     *
     * @param workload   workload identity ({@code Grayskull-Workload})
     * @param sdkVersion SDK name and version ({@code User-Agent}); may be null
     * @param secrets    the held secrets
     */
    void recordHeartbeat(String workload, String sdkVersion, List<HeldSecret> secrets);

    /**
     * Records that the calling workload was just served a secret version (a fetch sighting), so a workload is known
     * from the moment it boots and fetches, not only from its first heartbeat. Best effort: the write runs off the
     * request thread, happens at most once an hour per workload and secret version on this server, and never
     * fails the fetch. Does nothing when {@code workload} is missing.
     *
     * @param workload   workload identity ({@code Grayskull-Workload}); may be null
     * @param sdkVersion SDK name and version ({@code User-Agent}); may be null
     * @param projectId  project of the served secret
     * @param secretName name of the served secret
     * @param version    the served version
     */
    void recordFetch(String workload, String sdkVersion, String projectId, String secretName, int version);
}
