package com.flipkart.grayskull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flipkart.grayskull.constants.GrayskullHeaders;
import com.flipkart.grayskull.models.GrayskullClientConfiguration;
import com.flipkart.grayskull.models.request.BatchGetSecretsRequest;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link HeartbeatScheduler}; private methods are reached through reflection. */
class HeartbeatSchedulerTest {

    private static final String OK_BODY = "{\"data\":{\"updatedCount\":0,\"updatedSecrets\":[]}}";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockWebServer server;
    private GrayskullHttpClient httpClient;
    private HeartbeatScheduler scheduler;
    private String baseUrl;

    /** Arrival time of every batch POST; the first {@code failFirst} get a 503. */
    private final List<Long> arrivalNanos = new CopyOnWriteArrayList<>();
    private final AtomicInteger failFirst = new AtomicInteger();

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                arrivalNanos.add(System.nanoTime());
                if (failFirst.getAndDecrement() > 0) {
                    return new MockResponse().setResponseCode(503).setBody("unavailable");
                }
                return new MockResponse().setResponseCode(200)
                        .setHeader("Content-Type", "application/json").setBody(OK_BODY);
            }
        });
        server.start();
        baseUrl = server.url("/").toString().replaceAll("/$", "");

        GrayskullClientConfiguration config = new GrayskullClientConfiguration();
        config.setHost(baseUrl);
        config.setMetricsEnabled(false);
        httpClient = new GrayskullHttpClient(() -> "Bearer test-token", config);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (scheduler != null) {
            scheduler.close();
        }
        httpClient.close();
        server.shutdown();
        MDC.clear();
    }

    private static List<BatchGetSecretsRequest.Entry> entries(int n) {
        List<BatchGetSecretsRequest.Entry> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new BatchGetSecretsRequest.Entry("p", "s" + i, i + 1));
        }
        return out;
    }

    private HeartbeatScheduler newScheduler(int intervalSeconds, Supplier<List<BatchGetSecretsRequest.Entry>> held) {
        return new HeartbeatScheduler(httpClient, objectMapper, baseUrl, intervalSeconds, held);
    }

    private static boolean beatOnce(HeartbeatScheduler s) throws Exception {
        Method m = HeartbeatScheduler.class.getDeclaredMethod("beatOnce");
        m.setAccessible(true);
        return (boolean) m.invoke(s);
    }

    private static long nextDelayMillis(HeartbeatScheduler s, int failures) throws Exception {
        Method m = HeartbeatScheduler.class.getDeclaredMethod("nextDelayMillis", int.class);
        m.setAccessible(true);
        return (long) m.invoke(s, failures);
    }

    @Test
    void beatOnce_nothingFetched_sendsNothing() throws Exception {
        scheduler = newScheduler(60, Collections::emptyList);

        assertTrue(beatOnce(scheduler));
        assertEquals(0, server.getRequestCount());
    }

    @Test
    void beatOnce_sendsAllSecretsWithHeartbeatHeader() throws Exception {
        scheduler = newScheduler(60, () -> entries(3));

        assertTrue(beatOnce(scheduler));

        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertEquals("POST", request.getMethod());
        assertEquals("/v1/secrets/batch", request.getPath());
        assertEquals("true", request.getHeader(GrayskullHeaders.HEARTBEAT));
        assertEquals("Bearer test-token", request.getHeader("Authorization"));
        JsonNode secrets = objectMapper.readTree(request.getBody().readUtf8()).get("secrets");
        assertEquals(3, secrets.size());
        assertEquals("s0", secrets.get(0).get("secretName").asText());
        assertEquals(1, secrets.get(0).get("lastKnownVersion").asInt());
    }

    @Test
    void beatOnce_chunksAt50() throws Exception {
        scheduler = newScheduler(60, () -> entries(120));

        assertTrue(beatOnce(scheduler));

        assertEquals(3, server.getRequestCount());
        int[] sizes = new int[3];
        for (int i = 0; i < 3; i++) {
            sizes[i] = objectMapper.readTree(server.takeRequest().getBody().readUtf8()).get("secrets").size();
        }
        assertEquals(50, sizes[0]);
        assertEquals(50, sizes[1]);
        assertEquals(20, sizes[2]);
    }

    @Test
    void beatOnce_serverErrorReturnsFalseWithoutInlineRetry() throws Exception {
        failFirst.set(1);
        scheduler = newScheduler(60, () -> entries(1));

        assertFalse(beatOnce(scheduler));
        assertEquals(1, server.getRequestCount(), "a failed heartbeat is not retried inline");
    }

    @Test
    void nextDelay_successIsIntervalWithJitter() throws Exception {
        scheduler = newScheduler((int) TimeUnit.HOURS.toSeconds(24), Collections::emptyList);

        for (int i = 0; i < 200; i++) {
            long d = nextDelayMillis(scheduler, 0);
            assertTrue(d >= TimeUnit.HOURS.toMillis(23) && d <= TimeUnit.HOURS.toMillis(25), "delay " + d);
        }
    }

    @Test
    void nextDelay_failureBacksOffExponentiallyToCap() throws Exception {
        scheduler = newScheduler((int) TimeUnit.HOURS.toSeconds(24), Collections::emptyList);

        long[] minutes = {5, 10, 20, 40, 60, 60};
        for (int i = 0; i < minutes.length; i++) {
            long base = TimeUnit.MINUTES.toMillis(minutes[i]);
            long d = nextDelayMillis(scheduler, i + 1);
            assertTrue(d >= base - base / 10 && d <= base + base / 10, "failure " + (i + 1) + " delay " + d);
        }
    }

    @Test
    void nextDelay_backoffNeverExceedsInterval() throws Exception {
        scheduler = newScheduler(30, Collections::emptyList);

        for (int failures = 1; failures <= 5; failures++) {
            assertTrue(nextDelayMillis(scheduler, failures) <= 33_000);
        }
    }

    @Test
    void schedule_keepsSendingAfterFailures() throws Exception {
        // With a 1 s interval the backoff is capped at 1 s, so failures are retried and then succeed.
        failFirst.set(2);
        scheduler = newScheduler(1, () -> entries(1));

        scheduler.start();
        waitForRequests(4, 8_000);

        for (int i = 1; i < 4; i++) {
            long gapMs = TimeUnit.NANOSECONDS.toMillis(arrivalNanos.get(i) - arrivalNanos.get(i - 1));
            assertTrue(gapMs >= 800 && gapMs <= 1_500, "gap " + i + " was " + gapMs + " ms");
        }
    }

    @Test
    void close_stopsHeartbeat() throws Exception {
        scheduler = newScheduler(1, () -> entries(1));

        scheduler.start();
        waitForRequests(1, 3_000);
        scheduler.close();
        scheduler.close(); // idempotent

        int sent = arrivalNanos.size();
        Thread.sleep(1_500);
        assertEquals(sent, arrivalNanos.size(), "no heartbeat after close");
    }

    private void waitForRequests(int count, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (arrivalNanos.size() < count && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(arrivalNanos.size() >= count, "expected " + count + " heartbeats, got " + arrivalNanos.size());
    }
}
