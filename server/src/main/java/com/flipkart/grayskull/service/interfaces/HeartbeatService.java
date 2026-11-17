package com.flipkart.grayskull.service.interfaces;

import com.flipkart.grayskull.models.dto.request.HeldSecret;

import java.util.List;

/** Records which secrets, at which versions, workload instances hold. */
public interface HeartbeatService {

    /** Upserts one record per held secret version and refreshes its lastSeen. */
    void recordHeartbeat(String workload, String sdkVersion, List<HeldSecret> secrets);
}
