package com.flipkart.grayskull;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import com.flipkart.grayskull.auth.GrayskullAuthHeaderProvider;
import com.flipkart.grayskull.constants.GrayskullHeaders;
import com.flipkart.grayskull.constants.MDCKeys;
import com.flipkart.grayskull.hooks.RefreshHandlerRef;
import com.flipkart.grayskull.hooks.SecretRefreshHook;
import com.flipkart.grayskull.metrics.MetricsPublisher;
import com.flipkart.grayskull.models.GrayskullClientConfiguration;
import com.flipkart.grayskull.models.SecretValue;
import com.flipkart.grayskull.models.exceptions.GrayskullException;
import com.flipkart.grayskull.models.request.BatchGetSecretsRequest;
import com.flipkart.grayskull.models.request.SecretVersionEntry;
import com.flipkart.grayskull.models.response.BatchGetSecretsResponse;
import com.flipkart.grayskull.models.response.BatchSecretItem;
import com.flipkart.grayskull.models.response.HttpResponse;
import com.flipkart.grayskull.models.response.Response;
import okhttp3.HttpUrl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Implementation of the Grayskull client.
 * <p>
 * This class provides the main functionality for interacting with the Grayskull
 * secret management service, including fetching secrets on demand and delivering
 * server-driven refresh updates via registered {@link SecretRefreshHook hooks}.
 * </p>
 * <h2>Refresh hooks — runtime model</h2>
 * <p>
 * Each client runs two cooperating executors:
 * </p>
 * <ul>
 *   <li>A single-threaded <b>poller</b> that fires every
 *       {@link GrayskullClientConfiguration#getPollingIntervalSeconds()} seconds,
 *       gathers the {@code (secretRef, lastKnownVersion)} pairs of every
 *       registered hook, and issues a single {@code POST /v1/secrets/batch}.</li>
 *   <li>A small <b>dispatcher</b> thread pool that actually invokes consumer
 *       hooks. Per-secret execution is serialized by a non-blocking
 *       {@link AtomicBoolean} guard and conflated through an
 *       {@link AtomicReference} so that bursts of updates to the same secret
 *       collapse to a single delivery of the most recent value.</li>
 * </ul>
 * <p>
 * The design keeps the hot path (HTTP I/O and consumer callbacks) off the
 * scheduler thread, bounds the number of concurrent consumer invocations,
 * and prevents a slow or broken hook from blocking the poller.
 * </p>
 */
public final class GrayskullClientImpl implements GrayskullClient {
    private static final Logger log = LoggerFactory.getLogger(GrayskullClientImpl.class);
    private static final TypeReference<Response<SecretValue>> SECRET_VALUE_TYPE_REFERENCE =
            new TypeReference<Response<SecretValue>>() {};
    private static final TypeReference<Response<BatchGetSecretsResponse>> BATCH_RESPONSE_TYPE_REFERENCE =
            new TypeReference<Response<BatchGetSecretsResponse>>() {};

    private static final int DISPATCHER_THREADS = 5;
    private static final long SHUTDOWN_AWAIT_SECONDS = 10L;

    private final String baseUrl;
    private final GrayskullAuthHeaderProvider authHeaderProvider;
    private final GrayskullClientConfiguration grayskullClientConfiguration;
    private final GrayskullHttpClient httpClient;
    private final ObjectMapper objectMapper;

    /** Central registry of secrets the application has a hook on. */
    private final ConcurrentHashMap<String, SecretState> registeredSecrets = new ConcurrentHashMap<>();

    /** Fires the batch refresh probe at a fixed cadence. */
    private final ScheduledExecutorService poller;

    /** Runs the actual consumer hook callbacks, off the poller thread. */
    private final ExecutorService dispatcher;

    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Creates a new Grayskull client implementation.
     *
     * @param authHeaderProvider provider for authentication headers (must not be null)
     * @param grayskullClientConfiguration configuration properties (must not be null)
     * @throws IllegalArgumentException if authHeaderProvider or grayskullClientConfiguration is null
     */
    public GrayskullClientImpl(GrayskullAuthHeaderProvider authHeaderProvider, GrayskullClientConfiguration grayskullClientConfiguration) {

        if (authHeaderProvider == null) {
            throw new IllegalArgumentException("authHeaderProvider cannot be null");
        }
        if (grayskullClientConfiguration == null) {
            throw new IllegalArgumentException("grayskullClientConfiguration cannot be null");
        }

        // Resolve workload identity once and add as default header.
        String identity = grayskullClientConfiguration.getWorkloadIdentityResolver().resolve();
        grayskullClientConfiguration.addDefaultHeader(GrayskullHeaders.WORKLOAD, identity);

        this.baseUrl = grayskullClientConfiguration.getHost();
        this.authHeaderProvider = authHeaderProvider;
        this.grayskullClientConfiguration = grayskullClientConfiguration;
        this.httpClient = new GrayskullHttpClient(authHeaderProvider, grayskullClientConfiguration);

        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new ParameterNamesModule());

        // Configure metrics based on client configuration
        MetricsPublisher.configure(grayskullClientConfiguration.isMetricsEnabled());

        // Build the two executors that drive refresh-hook delivery.
        this.poller = Executors.newSingleThreadScheduledExecutor(
                namedDaemonFactory("grayskull-poller-"));
        this.dispatcher = Executors.newFixedThreadPool(
                DISPATCHER_THREADS, namedDaemonFactory("grayskull-hook-dispatcher-"));

        int intervalSeconds = grayskullClientConfiguration.getPollingIntervalSeconds();
        // scheduleWithFixedDelay (not scheduleAtFixedRate) — if a poll cycle runs long
        // (server slow, long hook fan-out on the same thread), we want the *next* cycle
        // to start `intervalSeconds` AFTER the previous one finishes
        this.poller.scheduleWithFixedDelay(
                this::pollOnce, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    /**
     * Retrieves a secret from the Grayskull server.
     * <p>
     * The secretRef should be in the format: "projectId:secretName"
     * For example: "my-project:database-password"
     * </p>
     *
     * @param secretRef the secret reference in format "projectId:secretName"
     * @return the secret value
     * @throws IllegalArgumentException if secretRef format is invalid
     * @throws RuntimeException if the secret cannot be retrieved
     */
    @Override
    public SecretValue getSecret(String secretRef) {
        String requestId = generateRequestId();
        MDC.put(MDCKeys.GRAYSKULL_REQUEST_ID, requestId);

        long startTime = System.nanoTime();

        int statusCode = 0;

        try {
            if (secretRef == null || secretRef.isEmpty()) {
                throw new IllegalArgumentException("secretRef cannot be null or empty");
            }

            String[] parts = parseSecretRef(secretRef);
            String projectId = parts[0];
            String secretName = parts[1];

            // Put context in MDC for automatic inclusion in all log statements
            MDC.put(MDCKeys.PROJECT_ID, projectId);
            MDC.put(MDCKeys.SECRET_NAME, secretName);

            log.debug("[RequestId:{}] Fetching secret for secretRef: {}", requestId, secretRef);

            String url = buildUrl("v1", "projects", projectId, "secrets", secretName, "data");

            // Fetch the secret with automatic retry logic
            HttpResponse httpResponse = httpClient.doGetWithRetry(url);
            statusCode = httpResponse.getStatusCode();

            Response<SecretValue> response = objectMapper.readValue(httpResponse.getBody(), SECRET_VALUE_TYPE_REFERENCE);
            SecretValue secretValue = response.getData();

            if (secretValue == null) {
                throw new GrayskullException(500, "No data in response");
            }

            return secretValue;

        } catch (JsonProcessingException e) {
            // JSON parsing errors are not retryable - they indicate a permanent problem
            throw new GrayskullException("Failed to parse response: ", e);
        } catch (GrayskullException e) {
            statusCode = e.getStatusCode();
            throw e;
        } finally {
            long duration = System.nanoTime() - startTime;
            long durationMs = TimeUnit.NANOSECONDS.toMillis(duration);
            MetricsPublisher.getInstance().recordRequest("getSecret." + secretRef, statusCode, durationMs);

            // Clean up MDC context
            MDC.remove(MDCKeys.PROJECT_ID);
            MDC.remove(MDCKeys.SECRET_NAME);
            MDC.remove(MDCKeys.GRAYSKULL_REQUEST_ID);
        }
    }

    /**
     * Registers a refresh hook for a secret.
     * <p>
     * The hook is added to the in-memory registry. On every poll cycle the
     * client asks the server whether any registered secret has advanced past
     * the client's last-known version; if so, the dispatcher thread pool
     * invokes every hook registered against that {@code secretRef}.
     * </p>
     *
     * @param secretRef the secret reference to monitor, in format {@code "projectId:secretName"}
     * @param hook the hook to invoke when the secret is refreshed
     * @return a handle that can be used to unregister the hook
     */
    @Override
    public RefreshHandlerRef registerRefreshHook(String secretRef, SecretRefreshHook hook) {
        String requestId = generateRequestId();
        MDC.put(MDCKeys.GRAYSKULL_REQUEST_ID, requestId);
        try {
            if (secretRef == null || secretRef.isEmpty()) {
                throw new IllegalArgumentException("secretRef cannot be null or empty");
            }
            if (hook == null) {
                throw new IllegalArgumentException("hook cannot be null");
            }
            // Fail fast on malformed refs rather than discovering them the first time we poll.
            parseSecretRef(secretRef);

            // Add inside compute() so register/unregister on the same secretRef are
            // serialized by the map's per-key lock. Doing `computeIfAbsent` + `hooks.add`
            // separately leaves a tiny window in which a concurrent unRegister can drop
            // the entry between the two calls, orphaning the newly added hook.
            SecretState state = registeredSecrets.compute(secretRef, (k, existing) -> {
                SecretState s = existing != null ? existing : new SecretState();
                s.hooks.add(hook);
                return s;
            });

            log.debug("[RequestId:{}] Registered refresh hook for secretRef:{} (totalHooks:{})",
                    requestId, secretRef, state.hooks.size());

            return new RealRefreshHandlerRef(secretRef, hook);
        } finally {
            MDC.remove(MDCKeys.GRAYSKULL_REQUEST_ID);
        }
    }

    /**
     * Closes the client and releases resources.
     * <p>
     * Shuts down the poller and dispatcher executors (giving in-flight hook
     * invocations a short grace period) before tearing down the HTTP client.
     * Idempotent.
     * </p>
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        log.info("Closing Grayskull client");

        shutdownExecutor(poller, "poller");
        shutdownExecutor(dispatcher, "dispatcher");

        if (httpClient != null) {
            httpClient.close();
        }
    }

    // ---------------------------------------------------------------------
    // Poller
    // ---------------------------------------------------------------------

    /**
     * One iteration of the background poller. Snapshots the registry, issues a
     * single batch request, and hands off work to the dispatcher.
     * <p>
     * Failures here are intentionally swallowed: a transient server outage
     * should not stop the scheduled executor from retrying on the next tick.
     * </p>
     */
    void pollOnce() {
        if (registeredSecrets.isEmpty()) {
            return;
        }

        String requestId = generateRequestId();
        MDC.put(MDCKeys.GRAYSKULL_REQUEST_ID, requestId);
        try {
            // Snapshot the registry. Work off a point-in-time copy so concurrent
            // register/unregister calls don't tear the request we're about to send.
            List<SecretVersionEntry> entries = new ArrayList<>(registeredSecrets.size());
            for (Map.Entry<String, SecretState> e : registeredSecrets.entrySet()) {
                String[] parts = parseSecretRefSafe(e.getKey());
                if (parts == null) {
                    continue; // shouldn't happen: validated at registration time
                }
                entries.add(new SecretVersionEntry(
                        parts[0], parts[1], e.getValue().lastKnownVersion.get()));
            }
            if (entries.isEmpty()) {
                return;
            }

            BatchGetSecretsRequest request = new BatchGetSecretsRequest(entries);
            String body = objectMapper.writeValueAsString(request);
            String url = buildUrl("v1", "secrets", "batch");

            log.debug("[RequestId:{}] Polling batch refresh for {} secret(s)", requestId, entries.size());
            HttpResponse httpResponse = httpClient.doPostWithRetry(url, body);

            Response<BatchGetSecretsResponse> parsed =
                    objectMapper.readValue(httpResponse.getBody(), BATCH_RESPONSE_TYPE_REFERENCE);
            BatchGetSecretsResponse payload = parsed.getData();
            if (payload == null || payload.getUpdatedSecrets() == null || payload.getUpdatedSecrets().isEmpty()) {
                log.debug("[RequestId:{}] No secret versions advanced this cycle", requestId);
                return;
            }

            for (BatchSecretItem item : payload.getUpdatedSecrets()) {
                handleUpdatedSecret(item);
            }
        } catch (Exception ex) {
            // Don't let any failure kill the scheduled executor. We'll try again next tick.
            log.warn("[RequestId:{}] Batch refresh poll failed: {}", requestId, ex.getMessage(), ex);
        } finally {
            MDC.remove(MDCKeys.GRAYSKULL_REQUEST_ID);
        }
    }

    private void handleUpdatedSecret(BatchSecretItem item) {
        String secretRef = item.getSecretRef();
        SecretState state = registeredSecrets.get(secretRef);
        if (state == null) {
            // Hook was unregistered between the poll request going out and the response coming back.
            return;
        }

        // Stash the newest value; latest update wins. Submit a dispatch task; if another
        // task is already draining pendingUpdate for this secret, it will pick this value up.
        state.pendingUpdate.set(item.toSecretValue());
        dispatcher.submit(() -> runHooksFor(secretRef, state));
    }

    // ---------------------------------------------------------------------
    // Dispatcher
    // ---------------------------------------------------------------------

    /**
     * Drains {@link SecretState#pendingUpdate} for a single secret and invokes
     * every registered hook, sequentially. Non-blocking: if another dispatcher
     * thread is already draining this state, we simply return — the in-flight
     * drain will observe the latest {@code pendingUpdate} before exiting.
     */
    private void runHooksFor(String secretRef, SecretState state) {
        if (!state.isExecuting.compareAndSet(false, true)) {
            // Another dispatcher task is already draining updates for this secret.
            // It will observe the pendingUpdate we just set before it clears isExecuting.
            return;
        }
        try {
            SecretValue value;
            while ((value = state.pendingUpdate.getAndSet(null)) != null) {
                deliverToHooks(secretRef, state, value);
                state.lastKnownVersion.set(value.getDataVersion());
            }
        } finally {
            state.isExecuting.set(false);
            // Race-recovery: if a poller thread set pendingUpdate *after* we cleared it
            // above but *before* we released isExecuting, a concurrent dispatcher task
            // would have seen isExecuting=true and returned immediately. Re-check here
            // so the update isn't orphaned.
            if (state.pendingUpdate.get() != null) {
                dispatcher.submit(() -> runHooksFor(secretRef, state));
            }
        }
    }

    private void deliverToHooks(String secretRef, SecretState state, SecretValue value) {
        for (SecretRefreshHook hook : state.hooks) {
            long startTime = System.nanoTime();
            int status = 200;
            try {
                hook.onUpdate(value);
            } catch (Exception e) {
                status = 500;
                log.error("Consumer hook failed for {}", secretRef, e);
            } finally {
                long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
                // Reuse the existing MetricsPublisher so hook SLOs land in the same pipeline
                // as the HTTP metrics; dashboards can filter on the "hook.execute." prefix.
                MetricsPublisher.getInstance().recordRequest(
                        "hook.execute." + secretRef, status, durationMs);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private String buildUrl(String... pathSegments) {
        HttpUrl parsedBaseUrl = HttpUrl.parse(baseUrl);
        if (parsedBaseUrl == null) {
            throw new IllegalStateException("Invalid baseUrl: " + baseUrl);
        }

        HttpUrl.Builder builder = parsedBaseUrl.newBuilder();
        for (String segment : pathSegments) {
            builder.addPathSegment(segment);
        }

        return builder.build().toString();
    }

    private String generateRequestId() {
        return UUID.randomUUID().toString();
    }

    private static String[] parseSecretRef(String secretRef) {
        String[] parts = secretRef.split(":", 2);
        if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            throw new IllegalArgumentException(
                    "Invalid secretRef format. Expected 'projectId:secretName', got: " + secretRef);
        }
        return parts;
    }

    private static String[] parseSecretRefSafe(String secretRef) {
        try {
            return parseSecretRef(secretRef);
        } catch (IllegalArgumentException e) {
            log.warn("Skipping malformed secretRef in registry: {}", secretRef);
            return null;
        }
    }

    private static ThreadFactory namedDaemonFactory(String prefix) {
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
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------------
    // Nested types
    // ---------------------------------------------------------------------

    /**
     * Per-secret reactive state kept in {@link #registeredSecrets}.
     * <p>
     * All mutators are concurrency-safe:
     * </p>
     * <ul>
     *   <li>{@link #lastKnownVersion} — atomic int, published to the server on every poll.</li>
     *   <li>{@link #hooks} — copy-on-write list so iteration inside the dispatcher is
     *       lock-free and consistent even while hooks register/unregister.</li>
     *   <li>{@link #isExecuting} — single-slot non-blocking mutex that serializes
     *       dispatcher tasks for the same secret.</li>
     *   <li>{@link #pendingUpdate} — "latest value wins" slot that the dispatcher
     *       drains; decouples poller tick rate from consumer hook latency.</li>
     * </ul>
     */
    static final class SecretState {
        final AtomicInteger lastKnownVersion = new AtomicInteger(0);
        final List<SecretRefreshHook> hooks = new CopyOnWriteArrayList<>();
        final AtomicBoolean isExecuting = new AtomicBoolean(false);
        final AtomicReference<SecretValue> pendingUpdate = new AtomicReference<>();
    }

    /**
     * Real {@link RefreshHandlerRef} returned by
     * {@link #registerRefreshHook(String, SecretRefreshHook)}.
     * <p>
     * Holds a reference to the exact hook instance that was registered so the
     * caller can revoke it without affecting other hooks on the same secret.
     * </p>
     */
    final class RealRefreshHandlerRef implements RefreshHandlerRef {
        private final String secretRef;
        private final SecretRefreshHook hook;
        private final AtomicBoolean active = new AtomicBoolean(true);

        RealRefreshHandlerRef(String secretRef, SecretRefreshHook hook) {
            this.secretRef = secretRef;
            this.hook = hook;
        }

        @Override
        public String getSecretRef() {
            return secretRef;
        }

        @Override
        public boolean isActive() {
            return active.get();
        }

        @Override
        public void unRegister() {
            if (!active.compareAndSet(true, false)) {
                return; // Idempotent: already unregistered
            }
            // Atomic w.r.t. other register/unregister calls on the same key:
            // compute() holds the ConcurrentHashMap's per-bin lock while we decide
            // whether to drop the entry entirely. This avoids the classic TOCTOU
            // where a concurrent registerRefreshHook could slot a new hook between
            // our "is the list empty?" check and "remove the key" call and have
            // its hook silently discarded.
            registeredSecrets.compute(secretRef, (key, state) -> {
                if (state == null) {
                    return null;
                }
                state.hooks.remove(hook);
                // Drop the whole entry when no hooks remain; the poller should stop
                // spending bandwidth asking about a secret nobody listens to.
                return state.hooks.isEmpty() ? null : state;
            });
            log.debug("Unregistered refresh hook for secretRef:{}", secretRef);
        }
    }
}
