package com.flipkart.grayskull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flipkart.grayskull.constants.MDCKeys;
import com.flipkart.grayskull.metrics.MetricsPublisher;
import com.flipkart.grayskull.models.exceptions.GrayskullException;
import com.flipkart.grayskull.models.exceptions.RetryableException;
import com.flipkart.grayskull.models.request.HeartbeatRequest;
import okhttp3.HttpUrl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Sends a heartbeat to {@code POST /v1/heartbeat} every interval (default 24h, ± 1/24 jitter) listing the
 * secrets and versions this client holds, so the server knows which workloads run which versions.
 * The heartbeat only records presence: it never fetches secret values. On failure it retries later with
 * capped exponential backoff instead of retrying inline.
 */
final class HeartbeatScheduler {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatScheduler.class);

    static final String HEARTBEAT_PATH = "v1/heartbeat";

    private static final long BACKOFF_BASE_MILLIS = TimeUnit.MINUTES.toMillis(5);
    private static final long BACKOFF_CAP_MILLIS = TimeUnit.MINUTES.toMillis(60);
    private static final int MAX_BATCH_SECRETS = 50;

    private final GrayskullHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String heartbeatUrl;
    private final Supplier<List<HeartbeatRequest.HeldSecret>> heldSecrets;
    private final long intervalMillis;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "grayskull-heartbeat");
        t.setDaemon(true);
        return t;
    });

    private int failures;

    HeartbeatScheduler(GrayskullHttpClient httpClient, ObjectMapper objectMapper, String baseUrl,
                       int intervalSeconds, Supplier<List<HeartbeatRequest.HeldSecret>> heldSecrets) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.heartbeatUrl = buildHeartbeatUrl(baseUrl);
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
        List<HeartbeatRequest.HeldSecret> held = heldSecrets.get();
        boolean ok = true;
        for (int from = 0; from < held.size(); from += MAX_BATCH_SECRETS) {
            ok &= send(held.subList(from, Math.min(from + MAX_BATCH_SECRETS, held.size())));
        }
        return ok;
    }

    private boolean send(List<HeartbeatRequest.HeldSecret> chunk) {
        MDC.put(MDCKeys.GRAYSKULL_REQUEST_ID, UUID.randomUUID().toString());
        long start = System.nanoTime();
        int status = 500;
        try {
            String body = objectMapper.writeValueAsString(new HeartbeatRequest(chunk));
            status = httpClient.doPost(heartbeatUrl, body).getStatusCode();
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

    private static String buildHeartbeatUrl(String baseUrl) {
        HttpUrl parsed = HttpUrl.parse(baseUrl);
        if (parsed == null) {
            throw new IllegalStateException("Invalid baseUrl: " + baseUrl);
        }
        return parsed.newBuilder().addPathSegments(HEARTBEAT_PATH).build().toString();
    }
}
