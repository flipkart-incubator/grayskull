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
}
