package com.flipkart.grayskull;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.flipkart.grayskull.models.GrayskullClientConfiguration;
import com.flipkart.grayskull.models.request.HeartbeatRequest;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeartbeatSchedulerTest {



    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockWebServer server;
    private GrayskullHttpClient httpClient;
    private HeartbeatScheduler scheduler;
    private String baseUrl;

    /** The first {@code failFirst} requests get 503. */
    private final List<Long> arrivalNanos = new CopyOnWriteArrayList<>();
    private final AtomicInteger failFirst = new AtomicInteger();
    private volatile int failStatus = 503;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                arrivalNanos.add(System.nanoTime());
                if (failFirst.getAndDecrement() > 0) {
                    return new MockResponse().setResponseCode(failStatus).setBody("failed");
                }
                return new MockResponse().setResponseCode(204);
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

    private static List<HeartbeatRequest.HeldSecret> entries(int n) {
        List<HeartbeatRequest.HeldSecret> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new HeartbeatRequest.HeldSecret("p", "s" + i, i + 1, i % 2 == 0 ? i : null));
        }
        return out;
    }

    private HeartbeatScheduler newScheduler(int intervalSeconds, Supplier<List<HeartbeatRequest.HeldSecret>> held) {
        return new HeartbeatScheduler(httpClient, objectMapper, baseUrl, intervalSeconds, held);
    }

    /** {@code beatOnce()} and its {@code Outcome} enum are private; returns the outcome's name. */
    private static String beatOnce(HeartbeatScheduler s) {
        try {
            java.lang.reflect.Method m = HeartbeatScheduler.class.getDeclaredMethod("beatOnce");
            m.setAccessible(true);
            return String.valueOf(m.invoke(s));
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new IllegalStateException(cause);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean isUnsupported(int status) {
        try {
            java.lang.reflect.Method m = HeartbeatScheduler.class.getDeclaredMethod("isUnsupported", int.class);
            m.setAccessible(true);
            return (boolean) m.invoke(null, status);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new IllegalStateException(cause);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static long nextDelayMillis(HeartbeatScheduler s, int failures) throws Exception {
        Method m = HeartbeatScheduler.class.getDeclaredMethod("nextDelayMillis", int.class);
        m.setAccessible(true);
        return (long) m.invoke(s, failures);
    }

    @Test
    void beatOnce_nothingFetched_sendsNothing() throws Exception {
        scheduler = newScheduler(60, Collections::emptyList);

        assertEquals("SENT", beatOnce(scheduler));
        assertEquals(0, server.getRequestCount());
    }

    @Test
    void beatOnce_postsHeldSecretsToHeartbeatApi() throws Exception {
        scheduler = newScheduler(60, () -> entries(3));

        assertEquals("SENT", beatOnce(scheduler));

        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertEquals("POST", request.getMethod());
        assertEquals("/v1/client/heartbeat", request.getPath());
        assertEquals("Bearer test-token", request.getHeader("Authorization"));
        JsonNode secrets = objectMapper.readTree(request.getBody().readUtf8()).get("secrets");
        assertEquals(3, secrets.size());
        assertEquals("s0", secrets.get(0).get("secretName").asText());
        assertEquals(1, secrets.get(0).get("version").asInt());
        assertEquals(0, secrets.get(0).get("refreshHookAppliedVersion").asInt());
        assertFalse(secrets.get(1).has("refreshHookAppliedVersion"), "no hook: field is absent");
        assertFalse(secrets.get(0).has("lastKnownVersion"));
    }

    @Test
    void beatOnce_chunksAt50() throws Exception {
        scheduler = newScheduler(60, () -> entries(120));

        assertEquals("SENT", beatOnce(scheduler));

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

        assertEquals("FAILED", beatOnce(scheduler));
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
    void nextDelay_afterAFailure_retriesOnceInAboutAnHourThenWaitsTheInterval() throws Exception {
        scheduler = newScheduler((int) TimeUnit.HOURS.toSeconds(24), Collections::emptyList);
        long hour = TimeUnit.HOURS.toMillis(1);

        for (int i = 0; i < 200; i++) {
            long retry = nextDelayMillis(scheduler, 1);
            assertTrue(retry >= hour - hour / 10 && retry <= hour + hour / 10, "retry delay " + retry);
            long next = nextDelayMillis(scheduler, 2);
            assertTrue(next >= TimeUnit.HOURS.toMillis(23) && next <= TimeUnit.HOURS.toMillis(25), "after retry " + next);
        }
    }

    @Test
    void nextDelay_retryNeverExceedsInterval() throws Exception {
        scheduler = newScheduler(30, Collections::emptyList);

        assertTrue(nextDelayMillis(scheduler, 1) <= 33_000);
    }

    @Test
    void run_failedBeatsAlternateBetweenOneRetryAndTheNextInterval() throws Exception {
        failFirst.set(Integer.MAX_VALUE);
        scheduler = newScheduler(60, () -> entries(1));
        Method run = HeartbeatScheduler.class.getDeclaredMethod("run");
        run.setAccessible(true);
        Field failures = HeartbeatScheduler.class.getDeclaredField("failures");
        failures.setAccessible(true);

        int[] expected = {1, 2, 1, 2};
        for (int want : expected) {
            run.invoke(scheduler);
            assertEquals(want, failures.getInt(scheduler), "1 means a retry is pending, 2 means the retry was used");
        }
    }

    @Test
    void schedule_keepsSendingAfterFailures() throws Exception {
        // 1 s interval caps the retry delay at 1 s
        failFirst.set(2);
        scheduler = newScheduler(1, () -> entries(1));

        scheduler.start();
        waitForRequests(4, 8_000);

        for (int i = 1; i < 4; i++) {
            long gapMs = TimeUnit.NANOSECONDS.toMillis(arrivalNanos.get(i) - arrivalNanos.get(i - 1));
            assertTrue(gapMs >= 800 && gapMs <= 3_000, "gap " + i + " was " + gapMs + " ms");
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

    @Test
    void close_interruptedWhileAwaitingTermination_restoresInterruptFlag() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // The supplier ignores interrupts, so the executor cannot terminate until released.
        scheduler = newScheduler(1, () -> {
            entered.countDown();
            awaitUninterruptibly(release);
            return Collections.emptyList();
        });
        scheduler.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS), "a heartbeat should be in progress");

        Thread.currentThread().interrupt();
        try {
            scheduler.close();
            assertTrue(Thread.currentThread().isInterrupted(), "interrupt status must be restored");
        } finally {
            Thread.interrupted(); // clear so later tests are unaffected
            release.countDown();
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void beatOnce_serializationFailureReturnsFalseWithoutSending() throws Exception {
        SimpleModule failing = new SimpleModule().addSerializer(HeartbeatRequest.class,
                new JsonSerializer<HeartbeatRequest>() {
                    @Override
                    public void serialize(HeartbeatRequest value, JsonGenerator gen, SerializerProvider provider)
                            throws IOException {
                        throw new IOException("cannot serialize");
                    }
                });
        ObjectMapper failingMapper = new ObjectMapper().registerModule(failing);
        scheduler = new HeartbeatScheduler(httpClient, failingMapper, baseUrl, 60, () -> entries(1));

        assertEquals("FAILED", beatOnce(scheduler));
        assertEquals(0, server.getRequestCount());
    }

    @Test
    void start_afterClose_isIgnored() throws Exception {
        scheduler = newScheduler(1, () -> entries(1));
        scheduler.close();

        scheduler.start(); // scheduling on a shut-down executor is swallowed, not thrown

        Thread.sleep(1_200);
        assertEquals(0, server.getRequestCount());
    }

    @Test
    void run_unexpectedRuntimeException_countsFailureAndDoesNotPropagate() throws Exception {
        scheduler = newScheduler(60, () -> {
            throw new IllegalStateException("store unavailable");
        });
        Method run = HeartbeatScheduler.class.getDeclaredMethod("run");
        run.setAccessible(true);

        run.invoke(scheduler);

        Field failures = HeartbeatScheduler.class.getDeclaredField("failures");
        failures.setAccessible(true);
        assertEquals(1, failures.getInt(scheduler));
        assertEquals(0, server.getRequestCount());
    }

    @Test
    void beatOnce_clientErrorReturnsFalseAndIsNotRetried() throws Exception {
        failFirst.set(1);
        failStatus = 400;
        scheduler = newScheduler(60, () -> entries(1));

        assertEquals("FAILED", beatOnce(scheduler));
        assertEquals(1, server.getRequestCount());
    }

    @Test
    void constructor_invalidBaseUrl_throws() {
        assertThrows(IllegalStateException.class,
                () -> new HeartbeatScheduler(httpClient, objectMapper, "not a url", 60, Collections::emptyList));
    }

    @Test
    void beatOnce_bodyHoldsOnlySecrets() throws Exception {
        scheduler = newScheduler(60, () -> entries(2));
        assertEquals("SENT", beatOnce(scheduler));
        JsonNode body = objectMapper.readTree(server.takeRequest().getBody().readUtf8());
        assertEquals(1, body.size(), "body is just {secrets: [...]}");
        JsonNode first = body.get("secrets").get(0);
        assertEquals(4, first.size(), "projectId, secretName, version, refreshHookAppliedVersion");
        assertEquals(3, body.get("secrets").get(1).size(), "no hook: three fields");
    }

    @Test
    void beatOnce_unsupportedStatus_returnsUnsupportedAndStopsSending() throws Exception {
        for (int status : new int[]{404, 405, 501}) {
            arrivalNanos.clear();
            failFirst.set(Integer.MAX_VALUE);
            failStatus = status;
            HeartbeatScheduler s = newScheduler(60, () -> entries(120));
            try {
                assertEquals("UNSUPPORTED", beatOnce(s), "status " + status);
                assertEquals(1, arrivalNanos.size(), "status " + status + ": remaining chunks must not be sent");
            } finally {
                s.close();
            }
        }
    }

    @Test
    void run_unsupported_waitsFullIntervalAndLogsOnce() throws Exception {
        failFirst.set(Integer.MAX_VALUE);
        failStatus = 404;
        scheduler = newScheduler(60, () -> entries(1));
        Method run = HeartbeatScheduler.class.getDeclaredMethod("run");
        run.setAccessible(true);
        run.invoke(scheduler);
        run.invoke(scheduler);

        Field failures = HeartbeatScheduler.class.getDeclaredField("failures");
        failures.setAccessible(true);
        assertEquals(0, failures.getInt(scheduler), "unsupported is not a failure: no hourly retry");
        Field logged = HeartbeatScheduler.class.getDeclaredField("unsupportedLogged");
        logged.setAccessible(true);
        assertTrue(logged.getBoolean(scheduler));
        long d = nextDelayMillis(scheduler, failures.getInt(scheduler));
        assertTrue(d >= 57_500 && d <= 62_500, "full interval expected, got " + d);
    }

    @Test
    void isUnsupported_onlyFor404_405_501() {
        assertTrue(isUnsupported(404));
        assertTrue(isUnsupported(405));
        assertTrue(isUnsupported(501));
        assertFalse(isUnsupported(400));
        assertFalse(isUnsupported(500));
        assertFalse(isUnsupported(503));
    }
    private void waitForRequests(int count, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (arrivalNanos.size() < count && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(arrivalNanos.size() >= count, "expected " + count + " heartbeats, got " + arrivalNanos.size());
    }

    @Test
    void beatOnce_failedMiddleChunkStillSendsTheRestAndFailsTheRound() throws Exception {
        server.setDispatcher(new Dispatcher() {
            private final AtomicInteger n = new AtomicInteger();

            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse().setResponseCode(n.incrementAndGet() == 2 ? 503 : 204);
            }
        });
        scheduler = newScheduler(60, () -> entries(120));
        assertEquals("FAILED", beatOnce(scheduler));
        assertEquals(3, server.getRequestCount(), "every chunk is attempted; the next round resends all of them");
    }

    @Test
    void beatOnce_unsupportedThenSupportedServer_recovers() throws Exception {
        failFirst.set(1);
        failStatus = 404;
        scheduler = newScheduler(60, () -> entries(1));
        assertEquals("UNSUPPORTED", beatOnce(scheduler));
        assertEquals("SENT", beatOnce(scheduler), "once the server ships the endpoint, heartbeats go through");
    }

    @Test
    void close_returnsPromptlyWhileARequestHangs() throws Exception {
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                arrivalNanos.add(System.nanoTime());
                return new MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE);
            }
        });
        HeartbeatScheduler s = newScheduler(1, () -> entries(1));
        s.start();
        waitForRequests(1, 5_000);
        long start = System.nanoTime();
        s.close();
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) <= 11, "close is bounded");
    }
}
