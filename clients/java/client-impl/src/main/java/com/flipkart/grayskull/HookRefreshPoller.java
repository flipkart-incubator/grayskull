package com.flipkart.grayskull;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flipkart.grayskull.constants.MDCKeys;
import com.flipkart.grayskull.hooks.DefaultRefreshHandlerRef;
import com.flipkart.grayskull.hooks.RefreshHandlerRef;
import com.flipkart.grayskull.hooks.SecretRefreshHook;
import com.flipkart.grayskull.hooks.SecretState;
import com.flipkart.grayskull.metrics.MetricsPublisher;
import com.flipkart.grayskull.models.SecretValue;
import com.flipkart.grayskull.models.exceptions.GrayskullException;
import com.flipkart.grayskull.models.request.BatchGetSecretsRequest;
import com.flipkart.grayskull.models.response.BatchGetSecretsResponse;
import com.flipkart.grayskull.models.response.HttpResponse;
import com.flipkart.grayskull.models.response.Response;
import okhttp3.HttpUrl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Owns the refresh-hook registry and the background poller/dispatcher pair that
 * drives server-driven hook delivery for the Java SDK.
 *
 * <h2>High-level flow</h2>
 * <ol>
 *   <li>Callers invoke
 *       {@link #register(String, String, SecretRefreshHook, int)} to attach a
 *       {@link SecretRefreshHook} for a {@code projectId:secretName}. State is kept in
 *       {@link #registry}, keyed by {@code secretRef}. The supplied
 *       {@code initialKnownVersion} (typically the version observed by a prior
 *       {@code getSecret} call on the same client) seeds {@link SecretState#lastKnownVersion}
 *       so the first batch poll does not redeliver a version the application has already
 *       received synchronously.</li>
 *   <li>A single-threaded {@link #scheduler} runs {@link #pollOnce()} every
 *       {@code intervalSeconds} (fixed-delay: the next tick starts only after the
 *       previous tick finishes). The first tick is delayed by {@code intervalSeconds}
 *       so the cadence is uniform from construction time. {@link #pollOnce()} returns
 *       immediately when the registry is empty, so a client that never registers a
 *       hook only pays a periodic no-op wakeup; callers that need an immediate
 *       materialized value should call {@code getSecret()} explicitly.</li>
 *   <li>{@link #pollOnce()} snapshots the current registry, builds a
 *       {@link BatchGetSecretsRequest} (chunked into requests of at most
 *       {@link #MAX_BATCH_SECRETS} secrets), and POSTs each chunk via
 *       {@link GrayskullHttpClient}. Each entry tells the server the
 *       caller's last-known version; the server replies with rows whose version is
 *       strictly greater.</li>
 *   <li>Each updated row is handed to {@link #handleUpdatedSecret} which stages the new
 *       value in {@link SecretState#pendingUpdate} and submits {@link #runHooksFor} to
 *       the {@link #dispatcher} thread pool.</li>
 *   <li>{@link #runHooksFor} is non-reentrant per-secret (guarded by
 *       {@link SecretState#isExecuting}). It drains the pending update, advances
 *       {@link SecretState#lastKnownVersion} <em>first</em> (at-most-once delivery —
 *       see method Javadoc for rationale; uses {@code Math.max} so a concurrent
 *       {@link #advanceLastKnownVersionIfPresent} call from {@code getSecret} cannot be
 *       regressed), then invokes every registered hook sequentially via
 *       {@link #deliverToHooks}. Hook exceptions are caught per-hook so one broken
 *       consumer cannot block delivery to the others.</li>
 * </ol>
 *
 * <p>{@link #advanceLastKnownVersionIfPresent} is the bridge between the synchronous
 * {@code getSecret} path (which observes a version directly from the server) and the
 * background hook delivery path: when the application reads a version synchronously
 * the poller's per-secret high-water mark is moved forward, suppressing redundant
 * first-poll deliveries for any hooks that were registered <em>before</em> that
 * {@code getSecret} call.</p>
 *
 * <h2>Thread-safety contract</h2>
 * The registry is a {@link ConcurrentHashMap} and per-secret hook lists are
 * {@link java.util.concurrent.CopyOnWriteArrayList}, so registration / unregistration
 * is safe to interleave with polling. {@link #close()} stops both executors and waits
 * up to {@link #SHUTDOWN_AWAIT_SECONDS} for in-flight work to finish.
 *
 * <h2>Failure isolation</h2>
 * Nothing thrown inside {@link #pollOnce()} escapes the runnable, so the recurring
 * schedule cannot be cancelled by a stray exception.
 */
final class HookRefreshPoller {
    private static final Logger log = LoggerFactory.getLogger(HookRefreshPoller.class);
    private static final TypeReference<Response<BatchGetSecretsResponse>> RESPONSE_TYPE =
            new TypeReference<Response<BatchGetSecretsResponse>>() {};

    private static final int DISPATCHER_THREADS = 5;
    private static final long SHUTDOWN_AWAIT_SECONDS = 10L;
    private static final int MAX_BATCH_SECRETS = 50;

    private final ConcurrentHashMap<String, SecretState> registry = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;
    private final ExecutorService dispatcher;
    private final GrayskullHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String batchUrl;

    HookRefreshPoller(GrayskullHttpClient httpClient,
                      ObjectMapper objectMapper,
                      String baseUrl,
                      int intervalSeconds) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.batchUrl = buildBatchUrl(baseUrl);

        this.scheduler = Executors.newSingleThreadScheduledExecutor(
                daemonFactory("grayskull-poller-"));
        this.dispatcher = Executors.newFixedThreadPool(
                DISPATCHER_THREADS, daemonFactory("grayskull-hook-dispatcher-"));

        // fixed-delay (not fixed-rate): next tick starts intervalSeconds AFTER the previous finishes.
        // pollOnce() returns immediately when the registry is empty, so a client that never
        // registers a hook only pays the cost of a wakeup-and-noop every interval.
        this.scheduler.scheduleWithFixedDelay(
                this::pollOnce, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    RefreshHandlerRef register(String projectId, String secretName, SecretRefreshHook hook) {
        return register(projectId, secretName, hook, 0);
    }

    RefreshHandlerRef register(String projectId, String secretName,
                               SecretRefreshHook hook, int initialKnownVersion) {
        String secretRef = projectId + ":" + secretName;
        SecretState state = registry.compute(secretRef, (k, existing) -> {
            SecretState s = existing != null ? existing : new SecretState(projectId, secretName);
            s.hooks.add(hook);
            // Never go backwards: if a prior getSecret advanced the version higher, keep that.
            s.lastKnownVersion.updateAndGet(curr -> Math.max(curr, initialKnownVersion));
            return s;
        });
        log.debug("Registered refresh hook for secretRef:{} (totalHooks:{}, initialKnownVersion:{})",
                secretRef, state.hooks.size(), state.lastKnownVersion.get());
        return new DefaultRefreshHandlerRef(secretRef, () -> unregister(secretRef, hook));
    }

    /**
     * Advances {@link SecretState#lastKnownVersion} for {@code secretRef} if a hook is registered;
     * no-op otherwise. Called by {@code GrayskullClientImpl.getSecret} so a synchronously-observed
     * version suppresses the otherwise-redundant first-poll delivery to hooks for the same secret.
     * Monotonic via {@code Math.max}.
     */
    void advanceLastKnownVersionIfPresent(String secretRef, int dataVersion) {
        SecretState state = registry.get(secretRef);
        if (state != null) {
            state.lastKnownVersion.updateAndGet(curr -> Math.max(curr, dataVersion));
        }
    }

    private void unregister(String secretRef, SecretRefreshHook hook) {
        registry.compute(secretRef, (k, state) -> {
            if (state == null) {
                return null;
            }
            state.hooks.remove(hook);
            return state.hooks.isEmpty() ? null : state;
        });
    }

    /**
     * One scheduled poll cycle. Runs on the single-threaded {@link #scheduler}.
     */
    void pollOnce() {
        if (registry.isEmpty()) {
            return;
        }

        String requestId = UUID.randomUUID().toString();
        MDC.put(MDCKeys.GRAYSKULL_REQUEST_ID, requestId);

        long startTime = System.nanoTime();
        int statusCode = 0;

        try {
            List<BatchGetSecretsRequest.Entry> entries = new ArrayList<>(registry.size());
            for (SecretState state : registry.values()) {
                entries.add(new BatchGetSecretsRequest.Entry(
                        state.projectId, state.secretName, state.lastKnownVersion.get()));
            }
            if (entries.isEmpty()) {
                return;
            }

            int totalSecrets = entries.size();
            boolean pollFailed = false;
            boolean anySecretUpdated = false;

            for (int from = 0; from < entries.size(); from += MAX_BATCH_SECRETS) {
                int to = Math.min(from + MAX_BATCH_SECRETS, entries.size());
                List<BatchGetSecretsRequest.Entry> chunk = entries.subList(from, to);

                try {
                    String body = objectMapper.writeValueAsString(new BatchGetSecretsRequest(chunk));
                    if (totalSecrets > MAX_BATCH_SECRETS) {
                        log.debug("Polling batch refresh chunk {}-{} of {} secret(s)", from + 1, to, totalSecrets);
                    } else {
                        log.debug("Polling batch refresh for {} secret(s)", totalSecrets);
                    }

                    HttpResponse httpResponse = httpClient.doPostWithRetry(batchUrl, body);
                    if (!pollFailed) {
                        statusCode = httpResponse.getStatusCode();
                    }

                    Response<BatchGetSecretsResponse> parsed =
                            objectMapper.readValue(httpResponse.getBody(), RESPONSE_TYPE);
                    BatchGetSecretsResponse payload = parsed.getData();
                    if (payload == null || payload.getUpdatedSecrets() == null
                            || payload.getUpdatedSecrets().isEmpty()) {
                        continue;
                    }

                    for (BatchGetSecretsResponse.UpdatedSecret item : payload.getUpdatedSecrets()) {
                        handleUpdatedSecret(item);
                        anySecretUpdated = true;
                    }
                } catch (GrayskullException ex) {
                    pollFailed = true;
                    statusCode = ex.getStatusCode();
                    log.error("Batch refresh failed for secrets {}..{}: {}", from + 1, to, ex.getMessage(), ex);
                } catch (Exception ex) {
                    pollFailed = true;
                    statusCode = 500;
                    log.error("Batch refresh failed for secrets {}..{}: {}", from + 1, to, ex.getMessage(), ex);
                }
            }

            if (!pollFailed && !anySecretUpdated) {
                log.debug("No secret versions advanced this cycle");
            }
        } catch (Throwable t) {
            log.error("Unhandled error in refresh poll cycle; suppressing to keep poller alive", t);
            statusCode = 500;
        } finally {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
            MetricsPublisher.getInstance().recordRequest("batchGetSecrets", statusCode, durationMs);
            MDC.remove(MDCKeys.GRAYSKULL_REQUEST_ID);
        }
    }

    /**
     * Stages a server-reported update for delivery and submits the per-secret runner.
     * <p>
     * Coalescing: {@link SecretState#pendingUpdate} holds at most one pending value;
     * if a newer update arrives before the previous one is drained, it overwrites the
     * pending slot. Subscribers therefore see the latest version, never an obsolete
     * intermediate one.
     * </p>
     */
    private void handleUpdatedSecret(BatchGetSecretsResponse.UpdatedSecret item) {
        String secretRef = item.getProjectId() + ":" + item.getSecretName();
        SecretState state = registry.get(secretRef);
        if (state == null) {
            return;
        }
        state.pendingUpdate.set(new SecretValue(
                item.getDataVersion(), item.getPublicPart(), item.getPrivatePart()));
        dispatcher.submit(() -> runHooksFor(secretRef, state));
    }

    /**
     * Drains {@link SecretState#pendingUpdate} and invokes every hook, sequentially.
     * <p>
     * Non-reentrant per-secret via {@link SecretState#isExecuting}: only one runner per
     * secret at a time. The {@code finally} block re-submits if a new update arrived
     * during the post-release window, so updates are not lost.
     * </p>
     * <p>
     * <strong>At-most-once delivery:</strong> {@link SecretState#lastKnownVersion} is
     * advanced <em>before</em> the hook runs (using {@code Math.max}, so it never
     * regresses even if {@link #advanceLastKnownVersionIfPresent} ran concurrently).
     * A hook that throws skips that version — the next bump from the server will still
     * be delivered; this avoids duplicate deliveries when hook execution outlasts the
     * polling interval.
     * </p>
     */
    private void runHooksFor(String secretRef, SecretState state) {
        if (!state.isExecuting.compareAndSet(false, true)) {
            return;
        }
        try {
            SecretValue value;
            while ((value = state.pendingUpdate.getAndSet(null)) != null) {
                // Math.max so a concurrent advanceLastKnownVersionIfPresent (from getSecret)
                // cannot be regressed by us writing a stale, lower version here.
                state.lastKnownVersion.updateAndGet(curr -> Math.max(curr, value.getDataVersion()));
                deliverToHooks(secretRef, state, value);
            }
        } finally {
            state.isExecuting.set(false);
            if (state.pendingUpdate.get() != null) {
                dispatcher.submit(() -> runHooksFor(secretRef, state));
            }
        }
    }

    private void deliverToHooks(String secretRef, SecretState state, SecretValue value) {
        for (SecretRefreshHook hook : state.hooks) {
            long startTime = System.nanoTime();
            boolean success = true;
            try {
                hook.onUpdate(value);
            } catch (Exception e) {
                success = false;
                log.error("Consumer hook failed for {}", secretRef, e);
            } finally {
                long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
                MetricsPublisher.getInstance().recordRequest(
                        "hook.execute." + secretRef, success ? 200 : 500, durationMs);
            }
        }
    }

    void close() {
        shutdownExecutor(scheduler, "poller");
        shutdownExecutor(dispatcher, "dispatcher");
    }

    private static String buildBatchUrl(String baseUrl) {
        HttpUrl parsed = HttpUrl.parse(baseUrl);
        if (parsed == null) {
            throw new IllegalStateException("Invalid baseUrl: " + baseUrl);
        }
        return parsed.newBuilder()
                .addPathSegment("v1")
                .addPathSegment("secrets")
                .addPathSegment("batch")
                .build().toString();
    }

    private static ThreadFactory daemonFactory(String prefix) {
        return new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, prefix + counter.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
    }

    private static void shutdownExecutor(ExecutorService executor, String name) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_AWAIT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("{} did not terminate cleanly within {}s; forcing shutdown",
                        name, SHUTDOWN_AWAIT_SECONDS);
                executor.shutdownNow();
                if (!executor.awaitTermination(SHUTDOWN_AWAIT_SECONDS, TimeUnit.SECONDS)) {
                    log.warn("{} still has running tasks after force-shutdown", name);
                }
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
