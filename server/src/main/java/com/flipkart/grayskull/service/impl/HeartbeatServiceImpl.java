package com.flipkart.grayskull.service.impl;

import com.flipkart.grayskull.models.dto.request.HeldSecret;
import com.flipkart.grayskull.service.interfaces.HeartbeatService;
import com.flipkart.grayskull.service.utils.AuthnUtil;
import com.flipkart.grayskull.spi.models.WorkloadHeartbeat;
import com.flipkart.grayskull.spi.repositories.WorkloadHeartbeatRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class HeartbeatServiceImpl implements HeartbeatService {

    static final int MAX_SDK_VERSION_LENGTH = 64;
    static final int MAX_WORKLOAD_LENGTH = 512;
    /** A fetch of the same workload and secret version is written at most once per this interval, per server. */
    static final Duration FETCH_RECORD_INTERVAL = Duration.ofHours(1);
    /** Bounds the throttle map; it is cleared when full, which only costs a few extra writes. */
    static final int MAX_TRACKED_FETCHES = 100_000;

    private final WorkloadHeartbeatRepository heartbeatRepository;
    private final AuthnUtil authnUtil;
    private final Clock clock;
    private final ExecutorService fetchRecorder;
    private final Map<String, Instant> lastFetchRecorded = new ConcurrentHashMap<>();

    @Autowired
    public HeartbeatServiceImpl(WorkloadHeartbeatRepository heartbeatRepository, AuthnUtil authnUtil) {
        // One background thread; when it falls behind, new fetch records are dropped rather than queued forever.
        this(heartbeatRepository, authnUtil, Clock.systemUTC(), new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(10_000), r -> {
                    Thread t = new Thread(r, "fetch-sighting-recorder");
                    t.setDaemon(true);
                    return t;
                }, new ThreadPoolExecutor.DiscardPolicy()));
    }

    HeartbeatServiceImpl(WorkloadHeartbeatRepository heartbeatRepository, AuthnUtil authnUtil, Clock clock,
                         ExecutorService fetchRecorder) {
        this.heartbeatRepository = heartbeatRepository;
        this.authnUtil = authnUtil;
        this.clock = clock;
        this.fetchRecorder = fetchRecorder;
    }

    @PreDestroy
    void shutdown() {
        fetchRecorder.shutdown();
    }

    @Override
    public void recordFetch(String workload, String sdkVersion, String projectId, String secretName, int version) {
        if (workload == null || workload.isBlank() || workload.length() > MAX_WORKLOAD_LENGTH) {
            return;
        }
        Instant now = clock.instant();
        String key = workload + '\n' + projectId + '\n' + secretName + '\n' + version;
        Instant last = lastFetchRecorded.get(key);
        if (last != null && now.isBefore(last.plus(FETCH_RECORD_INTERVAL))) {
            return;
        }
        if (lastFetchRecorded.size() >= MAX_TRACKED_FETCHES) {
            lastFetchRecorded.clear();
        }
        lastFetchRecorded.put(key, now);

        // Built on the request thread: the caller's identity lives in the request's security context.
        WorkloadHeartbeat record = WorkloadHeartbeat.builder()
                .workload(workload)
                .projectId(projectId)
                .secretName(secretName)
                .secretVersion(version)
                .sdkVersion(truncate(sdkVersion, MAX_SDK_VERSION_LENGTH))
                .userId(authnUtil.getCurrentUsername())
                .lastSeen(now)
                .build(); // refreshHook stays null: a fetch does not know it, so the stored value is kept
        fetchRecorder.execute(() -> {
            try {
                heartbeatRepository.upsertAll(List.of(record));
            } catch (RuntimeException e) {
                lastFetchRecorded.remove(key); // let the next fetch try again
                log.warn("Could not record fetch of {}:{} v{} by {}", projectId, secretName, version, workload, e);
            }
        });
    }

    @Override
    public void recordHeartbeat(String workload, String sdkVersion, List<HeldSecret> secrets) {
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
                        .lastSeen(now)
                        .build())
                .toList();

        heartbeatRepository.upsertAll(records);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
