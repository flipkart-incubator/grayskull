package com.flipkart.grayskull;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flipkart.grayskull.hooks.SecretRefreshHook;
import com.flipkart.grayskull.metrics.MetricsPublisher;
import com.flipkart.grayskull.models.SecretValue;
import com.flipkart.grayskull.models.request.BatchGetSecretsRequest;
import com.flipkart.grayskull.models.request.SecretVersionEntry;
import com.flipkart.grayskull.models.response.BatchGetSecretsResponse;
import com.flipkart.grayskull.models.response.BatchSecretItem;
import com.flipkart.grayskull.models.response.HttpResponse;
import com.flipkart.grayskull.models.response.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages registered secret refresh hooks and polls the server for changes.
 * <p>
 * <b>Invariants / design:</b>
 * <ul>
 *   <li>Any number of hooks may be registered for the same {@code secretRef};
 *       they are invoked in registration order on update.</li>
 *   <li>Each hook carries its own {@code lastKnownVersion} so late-registered
 *       hooks (which start at the current version) don't cause re-invocation
 *       of hooks that are already caught up.</li>
 *   <li>The polling thread is started lazily on first {@link #register} and
 *       left running until {@link #shutdown()}. This is the in-memory
 *       {@code registeredHooks} flag in practice: "scheduler != null" ≡ "at
 *       least one hook has ever been registered".</li>
 *   <li>Poll batches are capped at {@value #MAX_BATCH_SIZE}, matching the
 *       server's batch-get limit.</li>
 *   <li>A single transient poll failure must not break future polls; errors
 *       and hook exceptions are caught and logged, not propagated.</li>
 * </ul>
 */
class SecretRefreshManager {

    private static final Logger log = LoggerFactory.getLogger(SecretRefreshManager.class);
    static final int MAX_BATCH_SIZE = 50;
    private static final TypeReference<Response<BatchGetSecretsResponse>> BATCH_RESPONSE_TYPE =
            new TypeReference<Response<BatchGetSecretsResponse>>() {};

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<HookRegistration>> hooksBySecret =
            new ConcurrentHashMap<>();
    private final AtomicLong hookIdSeq = new AtomicLong(0);

    private final GrayskullHttpClient httpClient;
    private final String batchUrl;
    private final ObjectMapper objectMapper;
    private final int pollIntervalSeconds;

    private volatile ScheduledExecutorService scheduler;
    private final Object schedulerLock = new Object();

    SecretRefreshManager(GrayskullHttpClient httpClient, String batchUrl,
                         ObjectMapper objectMapper, int pollIntervalSeconds) {
        this.httpClient = httpClient;
        this.batchUrl = batchUrl;
        this.objectMapper = objectMapper;
        this.pollIntervalSeconds = pollIntervalSeconds;
    }

    /**
     * Registers a hook and returns a stable id that can be passed to
     * {@link #unregister(String, long)} to remove precisely this registration
     * (without affecting other hooks for the same secret).
     */
    long register(String secretRef, SecretRefreshHook hook, int lastKnownVersion) {
        long id = hookIdSeq.incrementAndGet();
        HookRegistration registration = new HookRegistration(id, hook, lastKnownVersion);
        hooksBySecret
                .computeIfAbsent(secretRef, k -> new CopyOnWriteArrayList<>())
                .add(registration);
        ensureSchedulerStarted();
        return id;
    }

    /**
     * Removes the hook with the given id from the given secret's list, if present.
     * <p>
     * Idempotent: calling with an unknown id, or after the hook has already been
     * removed, is a no-op. If the secret's hook list becomes empty, the entry
     * is pruned from the map so subsequent polls no longer include it.
     *
     * @return {@code true} iff a registration was actually removed.
     */
    boolean unregister(String secretRef, long hookId) {
        CopyOnWriteArrayList<HookRegistration> list = hooksBySecret.get(secretRef);
        if (list == null) {
            return false;
        }
        boolean removed = false;
        Iterator<HookRegistration> it = list.iterator();
        while (it.hasNext()) {
            HookRegistration reg = it.next();
            if (reg.id == hookId) {
                removed = list.remove(reg);
                break;
            }
        }
        if (list.isEmpty()) {
            // Compute-with-remove race guard: only remove if still empty.
            hooksBySecret.computeIfPresent(secretRef, (k, v) -> v.isEmpty() ? null : v);
        }
        return removed;
    }

    void poll() {
        try {
            List<Map.Entry<String, CopyOnWriteArrayList<HookRegistration>>> entries =
                    new ArrayList<>(hooksBySecret.entrySet());
            if (entries.isEmpty()) {
                return;
            }
            for (int i = 0; i < entries.size(); i += MAX_BATCH_SIZE) {
                List<Map.Entry<String, CopyOnWriteArrayList<HookRegistration>>> batch =
                        entries.subList(i, Math.min(i + MAX_BATCH_SIZE, entries.size()));
                pollBatch(batch);
            }
        } catch (Exception e) {
            // Top-level safety net: poll() runs on a ScheduledExecutor; an
            // uncaught exception would silently cancel future polls.
            log.warn("Error during secret refresh poll", e);
        }
    }

    void shutdown() {
        synchronized (schedulerLock) {
            if (scheduler != null) {
                scheduler.shutdownNow();
                log.info("Stopped secret refresh poller");
            }
        }
    }

    int hookCount() {
        int total = 0;
        for (CopyOnWriteArrayList<HookRegistration> list : hooksBySecret.values()) {
            total += list.size();
        }
        return total;
    }

    int secretCount() {
        return hooksBySecret.size();
    }

    private void ensureSchedulerStarted() {
        if (scheduler == null) {
            synchronized (schedulerLock) {
                if (scheduler == null) {
                    scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
                        @Override
                        public Thread newThread(Runnable r) {
                            Thread t = new Thread(r, "grayskull-poll");
                            t.setDaemon(true);
                            return t;
                        }
                    });
                    long jitterMs = ThreadLocalRandom.current().nextLong(pollIntervalSeconds * 1000L);
                    scheduler.scheduleWithFixedDelay(
                            this::poll,
                            jitterMs,
                            pollIntervalSeconds * 1000L,
                            TimeUnit.MILLISECONDS);
                    log.info("Started secret refresh poller (interval={}s, initialDelay={}ms)",
                            pollIntervalSeconds, jitterMs);
                }
            }
        }
    }

    private void pollBatch(List<Map.Entry<String, CopyOnWriteArrayList<HookRegistration>>> batch) {
        long startTime = System.nanoTime();
        int statusCode = 0;

        try {
            List<SecretVersionEntry> secretEntries = new ArrayList<>();
            for (Map.Entry<String, CopyOnWriteArrayList<HookRegistration>> entry : batch) {
                String[] parts = entry.getKey().split(":", 2);
                if (parts.length != 2) {
                    // A malformed secretRef should never reach here (the client
                    // validates before registering), but if it does we skip
                    // the entry rather than 400 the whole batch.
                    log.warn("Skipping malformed secretRef in poll: {}", entry.getKey());
                    continue;
                }
                int minVersion = minLastKnownVersion(entry.getValue());
                secretEntries.add(new SecretVersionEntry(parts[0], parts[1], minVersion));
            }
            if (secretEntries.isEmpty()) {
                return;
            }

            BatchGetSecretsRequest request = new BatchGetSecretsRequest(secretEntries);
            String json = objectMapper.writeValueAsString(request);

            HttpResponse httpResponse = httpClient.doPostWithRetry(batchUrl, json);
            statusCode = httpResponse.getStatusCode();

            Response<BatchGetSecretsResponse> response = objectMapper.readValue(
                    httpResponse.getBody(), BATCH_RESPONSE_TYPE);
            BatchGetSecretsResponse batchResponse = response.getData();

            if (batchResponse == null || batchResponse.getUpdatedSecrets() == null) {
                return;
            }

            for (BatchSecretItem updated : batchResponse.getUpdatedSecrets()) {
                String secretRef = updated.getProjectId() + ":" + updated.getSecretName();
                CopyOnWriteArrayList<HookRegistration> registrations = hooksBySecret.get(secretRef);
                if (registrations == null) {
                    continue;
                }
                // SecretValue is a public, immutable DTO in client-api; construct
                // it from the flat batch item so the SecretRefreshHook contract
                // (onUpdate(SecretValue)) stays stable.
                SecretValue secretValue = new SecretValue(
                        updated.getDataVersion(),
                        updated.getPublicPart(),
                        updated.getPrivatePart());
                invokeHooksInOrder(secretRef, registrations, updated.getDataVersion(), secretValue);
            }

        } catch (Exception e) {
            log.warn("Failed to poll batch of {} secrets", batch.size(), e);
        } finally {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
            MetricsPublisher.getInstance().recordRequest("batchGetSecrets", statusCode, durationMs);
        }
    }

    private void invokeHooksInOrder(String secretRef,
                                    CopyOnWriteArrayList<HookRegistration> registrations,
                                    int newVersion,
                                    SecretValue value) {
        for (HookRegistration reg : registrations) {
            if (reg.lastKnownVersion >= newVersion) {
                // This hook is already caught up (can happen when a late
                // register() picked up a version newer than other hooks').
                continue;
            }
            try {
                reg.hook.onUpdate(value);
                reg.lastKnownVersion = newVersion;
                log.debug("Hook {} invoked for {} (new version={})",
                        reg.id, secretRef, newVersion);
            } catch (Exception e) {
                // Per-hook failure isolation: we do NOT advance lastKnownVersion
                // on failure so the hook will be retried on the next poll. We
                // continue to the next hook in registration order.
                log.warn("Hook {} threw for secret {}", reg.id, secretRef, e);
            }
        }
    }

    private static int minLastKnownVersion(CopyOnWriteArrayList<HookRegistration> registrations) {
        int min = Integer.MAX_VALUE;
        for (HookRegistration reg : registrations) {
            if (reg.lastKnownVersion < min) {
                min = reg.lastKnownVersion;
            }
        }
        // If the list somehow became empty between iteration and read (races
        // with unregister), treat it as "nothing to poll" by returning MAX,
        // which the server will accept and return no updates for.
        return min == Integer.MAX_VALUE ? 0 : min;
    }

    static final class HookRegistration {
        final long id;
        final SecretRefreshHook hook;
        volatile int lastKnownVersion;

        HookRegistration(long id, SecretRefreshHook hook, int lastKnownVersion) {
            this.id = id;
            this.hook = hook;
            this.lastKnownVersion = lastKnownVersion;
        }
    }
}
