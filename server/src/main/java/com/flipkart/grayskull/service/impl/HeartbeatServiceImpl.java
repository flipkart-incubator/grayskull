package com.flipkart.grayskull.service.impl;

import com.flipkart.grayskull.models.dto.request.HeldSecret;
import com.flipkart.grayskull.service.interfaces.HeartbeatService;
import com.flipkart.grayskull.service.utils.AuthnUtil;
import com.flipkart.grayskull.spi.models.WorkloadHeartbeat;
import com.flipkart.grayskull.spi.repositories.WorkloadHeartbeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class HeartbeatServiceImpl implements HeartbeatService {

    static final int MAX_SDK_VERSION_LENGTH = 64;

    private final WorkloadHeartbeatRepository heartbeatRepository;
    private final AuthnUtil authnUtil;
    private final Clock clock = Clock.systemUTC();

    @Override
    public void recordHeartbeat(String workload, String sdkVersion, List<HeldSecret> secrets) {
        Instant now = clock.instant();
        String userId = authnUtil.getCurrentUsername();
        String sdk = truncate(sdkVersion, MAX_SDK_VERSION_LENGTH);

        // One record per secret version; duplicates keep the highest applied hook version.
        Map<String, HeldSecret> unique = new LinkedHashMap<>();
        for (HeldSecret secret : secrets) {
            unique.merge(secret.getProjectId() + ":" + secret.getSecretName() + ":" + secret.getVersion(), secret,
                    (a, b) -> {
                        a.setRefreshHookAppliedVersion(maxApplied(a.getRefreshHookAppliedVersion(), b.getRefreshHookAppliedVersion()));
                        return a;
                    });
        }

        List<WorkloadHeartbeat> records = unique.values().stream()
                .<WorkloadHeartbeat>map(secret -> WorkloadHeartbeat.builder()
                        .workload(workload)
                        .projectId(secret.getProjectId())
                        .secretName(secret.getSecretName())
                        .secretVersion(secret.getVersion())
                        .refreshHookAppliedVersion(secret.getRefreshHookAppliedVersion())
                        .sdkVersion(sdk)
                        .userId(userId)
                        .lastSeen(now)
                        .build())
                .toList();

        heartbeatRepository.upsertAll(records);
    }

    private static Integer maxApplied(Integer a, Integer b) {
        return a == null ? b : b == null ? a : Math.max(a, b);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
