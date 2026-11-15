package com.flipkart.grayskull.service.impl;

import com.flipkart.grayskull.models.dto.request.HeldSecret;
import com.flipkart.grayskull.service.interfaces.HeartbeatService;
import com.flipkart.grayskull.service.utils.AuthnUtil;
import com.flipkart.grayskull.spi.models.WorkloadHeartbeat;
import com.flipkart.grayskull.spi.repositories.WorkloadHeartbeatRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class HeartbeatServiceImpl implements HeartbeatService {

    static final int MAX_SDK_VERSION_LENGTH = 64;
    static final int MAX_WORKLOAD_LENGTH = 512;
    /** The same sighting (client and secret version) is written at most once per this interval, per server. */
    static final Duration SIGHTING_DEDUP_INTERVAL = Duration.ofHours(1);
    /** Bounds the dedup map; it is cleared when full, which only costs a few extra writes. */
    static final int MAX_TRACKED_SIGHTINGS = 100_000;

    private final WorkloadHeartbeatRepository heartbeatRepository;
    private final AuthnUtil authnUtil;
    private final Clock clock;
    private final Map<String, Instant> lastSightingWritten = new ConcurrentHashMap<>();

    @Autowired
    public HeartbeatServiceImpl(WorkloadHeartbeatRepository heartbeatRepository, AuthnUtil authnUtil) {
        this(heartbeatRepository, authnUtil, Clock.systemUTC());
    }

    HeartbeatServiceImpl(WorkloadHeartbeatRepository heartbeatRepository, AuthnUtil authnUtil, Clock clock) {
        this.heartbeatRepository = heartbeatRepository;
        this.authnUtil = authnUtil;
        this.clock = clock;
    }

    @Override
    public void recordFetch(String workload, String sdkVersion, String address, String projectId, String secretName,
                            int version) {
        // refreshHook null: a fetch does not know whether a hook is registered, so the stored value is kept
        recordSighting(workload, sdkVersion, address, projectId, secretName, version, null);
    }

    @Override
    public void recordHookPoll(String workload, String sdkVersion, String address, String projectId,
                               String secretName, int version) {
        recordSighting(workload, sdkVersion, address, projectId, secretName, version, true);
    }

    private void recordSighting(String workload, String sdkVersion, String address, String projectId,
                                String secretName, int version, Boolean refreshHook) {
        if (workload == null || workload.isBlank() || workload.length() > MAX_WORKLOAD_LENGTH) {
            return;
        }
        Instant now = clock.instant();
        String key = workload + '\n' + address + '\n' + projectId + '\n' + secretName + '\n' + version;
        Instant last = lastSightingWritten.get(key);
        if (last != null && now.isBefore(last.plus(SIGHTING_DEDUP_INTERVAL))) {
            return;
        }
        if (lastSightingWritten.size() >= MAX_TRACKED_SIGHTINGS) {
            lastSightingWritten.clear();
        }
        lastSightingWritten.put(key, now);

        WorkloadHeartbeat record = WorkloadHeartbeat.builder()
                .workload(workload)
                .projectId(projectId)
                .secretName(secretName)
                .secretVersion(version)
                .refreshHook(refreshHook)
                .sdkVersion(truncate(sdkVersion, MAX_SDK_VERSION_LENGTH))
                .userId(authnUtil.getCurrentUsername())
                .address(address)
                .lastSeen(now)
                .build();
        try {
            heartbeatRepository.upsertAll(List.of(record));
        } catch (RuntimeException e) {
            lastSightingWritten.remove(key); // let the next request try again
            log.warn("Could not record sighting of {}:{} v{} by {} at {}", projectId, secretName, version, workload,
                    address, e);
        }
    }

    @Override
    public void recordHeartbeat(String workload, String sdkVersion, String address, List<HeldSecret> secrets) {
        Instant now = clock.instant();
        String userId = authnUtil.getCurrentUsername();
        String sdk = truncate(sdkVersion, MAX_SDK_VERSION_LENGTH);

        // One record per secret version: if the same version is listed twice, report a hook if either has one.
        Map<String, HeldSecret> unique = new LinkedHashMap<>();
        for (HeldSecret secret : secrets) {
            unique.merge(secret.getProjectId() + ":" + secret.getSecretName() + ":" + secret.getVersion(), secret,
                    (a, b) -> a.isRefreshHook() ? a : b);
        }

        List<WorkloadHeartbeat> records = unique.values().stream()
                .<WorkloadHeartbeat>map(secret -> WorkloadHeartbeat.builder()
                        .workload(workload)
                        .projectId(secret.getProjectId())
                        .secretName(secret.getSecretName())
                        .secretVersion(secret.getVersion())
                        .refreshHook(secret.isRefreshHook())
                        .sdkVersion(sdk)
                        .userId(userId)
                        .address(address)
                        .lastSeen(now)
                        .build())
                .toList();

        heartbeatRepository.upsertAll(records);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
