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

/** Sends the usage-ledger snapshot to {@code POST /v1/client/heartbeat} on an interval; a failed beat is retried once after about an hour. */
final class HeartbeatScheduler {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatScheduler.class);

    private static final String HEARTBEAT_PATH = "v1/client/heartbeat";

    private static final long RETRY_DELAY_MILLIS = TimeUnit.HOURS.toMillis(1);
    private static final int MAX_BATCH_SECRETS = 50;

    /** Result of one beat. */
    private enum Outcome { SENT, FAILED, UNSUPPORTED }

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
    private boolean unsupportedLogged;

    public HeartbeatScheduler(GrayskullHttpClient httpClient, ObjectMapper objectMapper, String baseUrl,
                              int intervalSeconds,
                              Supplier<List<HeartbeatRequest.HeldSecret>> heldSecrets) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.heartbeatUrl = buildHeartbeatUrl(baseUrl);
        this.heldSecrets = heldSecrets;
        this.intervalMillis = TimeUnit.SECONDS.toMillis(intervalSeconds);
    }

    /** First heartbeat after one interval. */
    public void start() {
        scheduleNext();
    }

    /** Idempotent. */
    public void close() {
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
            // closed
        }
    }

    private void run() {
        try {
            failures = beatOnce() == Outcome.FAILED ? nextFailures(failures) : 0;
        } catch (RuntimeException e) {
            failures = nextFailures(failures);
            log.warn("heartbeat failed", e);
        } finally {
            scheduleNext();
        }
    }

    /** Sends the snapshot in chunks of {@value #MAX_BATCH_SECRETS}; nothing when empty. */
    private Outcome beatOnce() {
        List<HeartbeatRequest.HeldSecret> held = heldSecrets.get();
        if (held.isEmpty()) {
            return Outcome.SENT;
        }
        Outcome result = Outcome.SENT;
        for (int from = 0; from < held.size(); from += MAX_BATCH_SECRETS) {
            Outcome chunk = send(held.subList(from, Math.min(from + MAX_BATCH_SECRETS, held.size())));
            if (chunk == Outcome.UNSUPPORTED) {
                return Outcome.UNSUPPORTED;
            }
            if (chunk == Outcome.FAILED) {
                result = Outcome.FAILED;
            }
        }
        return result;
    }

    private Outcome send(List<HeartbeatRequest.HeldSecret> chunk) {
        MDC.put(MDCKeys.GRAYSKULL_REQUEST_ID, UUID.randomUUID().toString());
        long start = System.nanoTime();
        int status = 500;
        try {
            String body = objectMapper.writeValueAsString(new HeartbeatRequest(chunk));
            status = httpClient.doPost(heartbeatUrl, body).getStatusCode();
            return Outcome.SENT;
        } catch (Exception e) {
            if (e instanceof RetryableException) {
                status = ((RetryableException) e).getStatusCode();
            } else if (e instanceof GrayskullException) {
                status = ((GrayskullException) e).getStatusCode();
            }
            if (isUnsupported(status)) {
                if (!unsupportedLogged) {
                    unsupportedLogged = true;
                    log.info("Grayskull server does not support client heartbeats yet (status {}); "
                            + "retrying after the full interval", status);
                }
                return Outcome.UNSUPPORTED;
            }
            log.warn("heartbeat for {} secret(s) failed (status {}); retrying once in about an hour: {}",
                    chunk.size(), status, e.getMessage());
            return Outcome.FAILED;
        } finally {
            MetricsPublisher.getInstance().recordRequest("heartbeat", status,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
            MDC.remove(MDCKeys.GRAYSKULL_REQUEST_ID);
        }
    }

    private static boolean isUnsupported(int status) {
        return status == 404 || status == 405 || status == 501;
    }

    /** 1 after a failed beat (retry pending), 2 after the retry also failed (wait for the next interval). */
    private static int nextFailures(int failures) {
        return failures == 1 ? 2 : 1;
    }

    /** One retry after about an hour (± 10%) following a failure; otherwise the interval ± 1/24. */
    private long nextDelayMillis(int failures) {
        boolean retry = failures == 1;
        long delay = retry ? Math.min(RETRY_DELAY_MILLIS, intervalMillis) : intervalMillis;
        long spread = retry ? delay / 10 : delay / 24;
        long jitter = ThreadLocalRandom.current().nextLong(-spread, spread + 1);
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
