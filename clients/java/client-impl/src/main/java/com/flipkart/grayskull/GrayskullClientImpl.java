package com.flipkart.grayskull;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import com.flipkart.grayskull.auth.GrayskullAuthHeaderProvider;
import com.flipkart.grayskull.constants.GrayskullHeaders;
import com.flipkart.grayskull.constants.MDCKeys;
import com.flipkart.grayskull.hooks.DefaultRefreshHandlerRef;
import com.flipkart.grayskull.hooks.RefreshHandlerRef;
import com.flipkart.grayskull.hooks.SecretRefreshHook;
import com.flipkart.grayskull.hooks.SecretState;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Implementation of the Grayskull client.
 * <p>
 * Fetches secrets on demand and, for registered {@link SecretRefreshHook hooks},
 * polls the batch-refresh endpoint on a fixed cadence and delivers updates on a
 * dedicated dispatcher pool.
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

    private static final int MAX_BATCH_SECRETS = 50;

    private final String baseUrl;
    private final GrayskullAuthHeaderProvider authHeaderProvider;
    private final GrayskullClientConfiguration grayskullClientConfiguration;
    private final GrayskullHttpClient httpClient;
    private final ObjectMapper objectMapper;

    private final ConcurrentHashMap<String, SecretState> registeredSecrets = new ConcurrentHashMap<>();
    private final ScheduledExecutorService poller;
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

        // Resolve workload identity once and pin it as a default header.
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

        this.poller = Executors.newSingleThreadScheduledExecutor(
                namedDaemonFactory("grayskull-poller-"));
        this.dispatcher = Executors.newFixedThreadPool(
                DISPATCHER_THREADS, namedDaemonFactory("grayskull-hook-dispatcher-"));

        // initialDelay 0 runs the first poll as soon as the poller thread starts 
        int intervalSeconds = grayskullClientConfiguration.getPollingIntervalSeconds();
        this.poller.scheduleWithFixedDelay(
                this::pollOnce, 0, intervalSeconds, TimeUnit.SECONDS);
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

            // Parse secretRef format: "projectId:secretName"
            String[] parts = secretRef.split(":", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException(
                        "Invalid secretRef format. Expected 'projectId:secretName', got: " + secretRef);
            }

            String projectId = parts[0];
            String secretName = parts[1];

            if (projectId.isEmpty() || secretName.isEmpty()) {
                throw new IllegalArgumentException(
                        "projectId and secretName cannot be empty in secretRef: " + secretRef);
            }

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
            parseSecretRef(secretRef); // fail fast on malformed refs

            // compute() is atomic per-key: keeps register/unregister from interleaving.
            SecretState state = registeredSecrets.compute(secretRef, (k, existing) -> {
                SecretState s = existing != null ? existing : new SecretState();
                s.hooks.add(hook);
                return s;
            });

            log.debug("[RequestId:{}] Registered refresh hook for secretRef:{} (totalHooks:{})",
                    requestId, secretRef, state.hooks.size());

            return new DefaultRefreshHandlerRef(secretRef, hook, registeredSecrets);
        } finally {
            MDC.remove(MDCKeys.GRAYSKULL_REQUEST_ID);
        }
    }

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

    void pollOnce() {
        if (registeredSecrets.isEmpty()) {
            return;
        }

        String requestId = generateRequestId();
        MDC.put(MDCKeys.GRAYSKULL_REQUEST_ID, requestId);

        long startTime = System.nanoTime();
        int statusCode = 0;

        try {
            // Snapshot the registry so concurrent register/unregister can't tear the request.
            List<SecretVersionEntry> entries = new ArrayList<>(registeredSecrets.size());
            for (Map.Entry<String, SecretState> e : registeredSecrets.entrySet()) {
                String[] parts = parseSecretRefSafe(e.getKey());
                if (parts == null) {
                    continue;
                }
                entries.add(new SecretVersionEntry(
                        parts[0], parts[1], e.getValue().lastKnownVersion.get()));
            }
            if (entries.isEmpty()) {
                return;
            }

            String url = buildUrl("v1", "secrets", "batch");
            int totalSecrets = entries.size();
            boolean pollFailed = false;
            boolean anySecretUpdated = false;

            for (int from = 0; from < entries.size(); from += MAX_BATCH_SECRETS) {
                int to = Math.min(from + MAX_BATCH_SECRETS, entries.size());
                List<SecretVersionEntry> chunk = entries.subList(from, to);

                try {
                    BatchGetSecretsRequest request = new BatchGetSecretsRequest(chunk);
                    String body = objectMapper.writeValueAsString(request);

                    if (totalSecrets > MAX_BATCH_SECRETS) {
                        log.debug("[RequestId:{}] Polling batch refresh chunk {}-{} of {} secret(s)",
                                requestId, from + 1, to, totalSecrets);
                    } else {
                        log.debug("[RequestId:{}] Polling batch refresh for {} secret(s)",
                                requestId, totalSecrets);
                    }

                    HttpResponse httpResponse = httpClient.doPostWithRetry(url, body);
                    if (!pollFailed) {
                        statusCode = httpResponse.getStatusCode();
                    }

                    Response<BatchGetSecretsResponse> parsed =
                            objectMapper.readValue(httpResponse.getBody(), BATCH_RESPONSE_TYPE_REFERENCE);
                    BatchGetSecretsResponse payload = parsed.getData();
                    if (payload == null || payload.getUpdatedSecrets() == null
                            || payload.getUpdatedSecrets().isEmpty()) {
                        continue;
                    }

                    for (BatchSecretItem item : payload.getUpdatedSecrets()) {
                        handleUpdatedSecret(item);
                        anySecretUpdated = true;
                    }
                } catch (GrayskullException ex) {
                    pollFailed = true;
                    statusCode = ex.getStatusCode();
                    log.warn("[RequestId:{}] Batch refresh failed for secrets {}..{}: {}",
                            requestId, from + 1, to, ex.getMessage(), ex);
                } catch (Exception ex) {
                    pollFailed = true;
                    statusCode = 500;
                    log.warn("[RequestId:{}] Batch refresh failed for secrets {}..{}: {}",
                            requestId, from + 1, to, ex.getMessage(), ex);
                }
            }

            if (!pollFailed && !anySecretUpdated) {
                log.debug("[RequestId:{}] No secret versions advanced this cycle", requestId);
            }
        } catch (GrayskullException ex) {
            statusCode = ex.getStatusCode();
            log.warn("[RequestId:{}] Batch refresh poll failed: {}", requestId, ex.getMessage(), ex);
        } catch (Exception ex) {
            // Swallow: the scheduled executor must survive transient failures.
            statusCode = 500;
            log.warn("[RequestId:{}] Batch refresh poll failed: {}", requestId, ex.getMessage(), ex);
        } finally {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
            MetricsPublisher.getInstance().recordRequest("batchGetSecrets", statusCode, durationMs);
            MDC.remove(MDCKeys.GRAYSKULL_REQUEST_ID);
        }
    }

    private void handleUpdatedSecret(BatchSecretItem item) {
        String secretRef = item.getSecretRef();
        SecretState state = registeredSecrets.get(secretRef);
        if (state == null) {
            return; // unregistered while the request was in flight
        }
        // Latest-wins: an in-flight dispatch task will pick this value up before exiting.
        state.pendingUpdate.set(item.toSecretValue());
        dispatcher.submit(() -> runHooksFor(secretRef, state));
    }

    /**
     * Drains {@link SecretState#pendingUpdate} and invokes every hook, sequentially.
     * Non-reentrant per-secret: if another task is already draining, returns immediately.
     */
    private void runHooksFor(String secretRef, SecretState state) {
        if (!state.isExecuting.compareAndSet(false, true)) {
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
            // Catch the update that slipped in between getAndSet(null) and isExecuting=false.
            if (state.pendingUpdate.get() != null) {
                dispatcher.submit(() -> runHooksFor(secretRef, state));
            }
        }
    }

    /**
     * Invokes every hook registered for this secret, in {@link SecretState#hooks registration order}.
     */
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

}
