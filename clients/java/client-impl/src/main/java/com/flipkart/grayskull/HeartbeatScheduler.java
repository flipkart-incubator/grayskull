package com.flipkart.grayskull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flipkart.grayskull.constants.GrayskullHeaders;
import com.flipkart.grayskull.constants.MDCKeys;
import com.flipkart.grayskull.metrics.MetricsPublisher;
import com.flipkart.grayskull.models.exceptions.GrayskullException;
import com.flipkart.grayskull.models.exceptions.RetryableException;
import com.flipkart.grayskull.models.request.BatchGetSecretsRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Reports the secrets and versions this instance holds to {@code POST /v1/secrets/batch} (with the
 * {@code Grayskull-Heartbeat} header) once per interval, so long-lived processes stay visible to rotation.
 * <p>
 * After a success the next heartbeat waits the interval ±1/24 (24 h ±1 h). After a failure it retries with
 * backoff: 5, 10, 20, 40, then every 60 minutes (±10%, never longer than the interval). No inline retries;
 * runs on its own daemon thread and never touches {@code getSecret}.
 * </p>
 */
final class HeartbeatScheduler {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatScheduler.class);

    private static final long BACKOFF_BASE_MILLIS = TimeUnit.MINUTES.toMillis(5);
    private static final long BACKOFF_CAP_MILLIS = TimeUnit.MINUTES.toMillis(60);
    private static final int MAX_BATCH_SECRETS = 50;
    private static final Map<String, String> HEADERS = Collections.singletonMap(GrayskullHeaders.HEARTBEAT, "true");

    private final GrayskullHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String batchUrl;
    private final Supplier<List<BatchGetSecretsRequest.Entry>> heldSecrets;
    private final long intervalMillis;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "grayskull-heartbeat");
        t.setDaemon(true);
        return t;
    });

    private int failures;

    HeartbeatScheduler(GrayskullHttpClient httpClient, ObjectMapper objectMapper, String baseUrl,
                       int intervalSeconds, Supplier<List<BatchGetSecretsRequest.Entry>> heldSecrets) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.batchUrl = HookRefreshPoller.buildBatchUrl(baseUrl);
        this.heldSecrets = heldSecrets;
        this.intervalMillis = TimeUnit.SECONDS.toMillis(intervalSeconds);
    }

    /** The first heartbeat goes one interval from now: the fetches at startup are already visible. */
    void start() {
        scheduleNext();
    }

    /** Stops the heartbeat, interrupting an in-flight call. Idempotent. */
    void close() {
        scheduler.shutdownNow();
        try {
            scheduler.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void scheduleNext() {
        try {
            scheduler.schedule(this::run, nextDelayMillis(failures), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException closed) {
            // close() raced with this run; nothing to schedule.
        }
    }

    private void run() {
        try {
            failures = beatOnce() ? 0 : failures + 1;
        } catch (RuntimeException e) {
            failures++;
            log.warn("heartbeat failed", e);
        } finally {
            scheduleNext();
        }
    }

    /** Sends every held secret in chunks of 50; true when all chunks succeeded (or nothing is held). */
    private boolean beatOnce() {
        List<BatchGetSecretsRequest.Entry> held = heldSecrets.get();
        boolean ok = true;
        for (int from = 0; from < held.size(); from += MAX_BATCH_SECRETS) {
            ok &= send(held.subList(from, Math.min(from + MAX_BATCH_SECRETS, held.size())));
        }
        return ok;
    }

    private boolean send(List<BatchGetSecretsRequest.Entry> chunk) {
        MDC.put(MDCKeys.GRAYSKULL_REQUEST_ID, UUID.randomUUID().toString());
        long start = System.nanoTime();
        int status = 500;
        try {
            String body = objectMapper.writeValueAsString(new BatchGetSecretsRequest(chunk));
            status = httpClient.doPost(batchUrl, body, HEADERS).getStatusCode();
            return true;
        } catch (Exception e) {
            if (e instanceof RetryableException) {
                status = ((RetryableException) e).getStatusCode();
            } else if (e instanceof GrayskullException) {
                status = ((GrayskullException) e).getStatusCode();
            }
            log.warn("heartbeat for {} secret(s) failed (status {}); retrying with backoff: {}",
                    chunk.size(), status, e.getMessage());
            return false;
        } finally {
            MetricsPublisher.getInstance().recordRequest("heartbeat", status,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
            MDC.remove(MDCKeys.GRAYSKULL_REQUEST_ID);
        }
    }

    /** Interval ±1/24 after a success; capped exponential backoff ±10% after {@code failures} failures. */
    private long nextDelayMillis(int failures) {
        long delay = failures == 0
                ? intervalMillis
                : Math.min(BACKOFF_BASE_MILLIS << Math.min(failures - 1, 20), Math.min(BACKOFF_CAP_MILLIS, intervalMillis));
        long spread = failures == 0 ? delay / 24 : delay / 10;
        long jitter = spread > 0 ? ThreadLocalRandom.current().nextLong(-spread, spread + 1) : 0;
        return Math.max(1, delay + jitter);
    }
}
