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
 * Owns the refresh-hook registry and the background poller/dispatcher pair
 * that drives server-driven hook delivery.
 */
final class HookRefreshPoller {
    private static final Logger log = LoggerFactory.getLogger(HookRefreshPoller.class);
    private static final TypeReference<Response<BatchGetSecretsResponse>> RESPONSE_TYPE =
            new TypeReference<Response<BatchGetSecretsResponse>>() {};

    private static final int DISPATCHER_THREADS = 5;
    private static final long SHUTDOWN_AWAIT_SECONDS = 10L;
    private static final long INITIAL_DELAY_SECONDS = 1L;
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

        // fixed-delay (not fixed-rate): next tick starts N seconds AFTER the previous finishes.
        this.scheduler.scheduleWithFixedDelay(
                this::pollOnce, INITIAL_DELAY_SECONDS, intervalSeconds, TimeUnit.SECONDS);
    }

    RefreshHandlerRef register(String projectId, String secretName, SecretRefreshHook hook) {
        String secretRef = projectId + ":" + secretName;
        SecretState state = registry.compute(secretRef, (k, existing) -> {
            SecretState s = existing != null ? existing : new SecretState(projectId, secretName);
            s.hooks.add(hook);
            return s;
        });
        log.debug("Registered refresh hook for secretRef:{} (totalHooks:{})", secretRef, state.hooks.size());
        return new DefaultRefreshHandlerRef(secretRef, () -> unregister(secretRef, hook));
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
        } finally {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
            MetricsPublisher.getInstance().recordRequest("batchGetSecrets", statusCode, durationMs);
            MDC.remove(MDCKeys.GRAYSKULL_REQUEST_ID);
        }
    }

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
                MetricsPublisher.getInstance().recordRequest(
                        "hook.execute." + secretRef, status, durationMs);
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
