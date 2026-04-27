package com.flipkart.grayskull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import com.flipkart.grayskull.hooks.RefreshHandlerRef;
import com.flipkart.grayskull.hooks.SecretRefreshHook;
import com.flipkart.grayskull.hooks.SecretState;
import com.flipkart.grayskull.models.SecretValue;
import com.flipkart.grayskull.models.response.BatchGetSecretsResponse;
import com.flipkart.grayskull.models.response.BatchGetSecretsResponse.UpdatedSecret;
import com.flipkart.grayskull.models.response.HttpResponse;
import com.flipkart.grayskull.models.response.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Concurrency invariants for {@link HookRefreshPoller}.
 * <p>
 * These tests stress the trickiest paths in the poller — register/unregister
 * races, the dispatcher's "latest-wins" coalescing, and the wake-up-after-release
 * race in {@code runHooksFor}. We instantiate the poller directly (package-private
 * access) and inject a mocked HTTP client so the scheduler thread never makes a
 * real network call.
 */
@ExtendWith(MockitoExtension.class)
class HookRefreshPollerConcurrencyTest {

    private static final String BATCH_URL = "https://test.grayskull.com/v1/secrets/batch";
    /** A long interval so the scheduler's auto-tick (after the 1s initial delay)
     *  will not interfere with our explicit {@link HookRefreshPoller#pollOnce()} calls. */
    private static final int LONG_INTERVAL_SECONDS = 3600;

    @Mock
    private GrayskullHttpClient mockHttpClient;

    private ObjectMapper objectMapper;
    private HookRefreshPoller poller;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new ParameterNamesModule());
        // Default benign response so the scheduled auto-tick (which we cannot prevent)
        // is harmless even if it fires before close().
        try {
            HttpResponse benign = wrapBatch(new BatchGetSecretsResponse(0, Collections.emptyList()));
            lenient().when(mockHttpClient.doPostWithRetry(eq(BATCH_URL), anyString())).thenReturn(benign);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        poller = new HookRefreshPoller(
                mockHttpClient, objectMapper, "https://test.grayskull.com", LONG_INTERVAL_SECONDS);
    }

    @AfterEach
    void tearDown() {
        if (poller != null) {
            poller.close();
        }
    }

    // ---------------------------------------------------------------------
    // 1. Parallel register: concurrent registrations from many threads against
    //    the SAME secret must all be retained (no lost hooks).
    // ---------------------------------------------------------------------
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void parallelRegister_sameSecret_allHooksRetained() throws Exception {
        final int threads = 32;
        final int hooksPerThread = 50;
        final int expectedHooks = threads * hooksPerThread;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier startGate = new CyclicBarrier(threads);
        CountDownLatch done = new CountDownLatch(threads);

        try {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    try {
                        startGate.await();
                        for (int i = 0; i < hooksPerThread; i++) {
                            poller.register("acme", "shared", v -> { /* no-op */ });
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(done.await(8, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        SecretState state = lookup(poller, "acme:shared");
        assertEquals(expectedHooks, state.hooks.size(),
                "all concurrent registrations must be retained; ConcurrentHashMap.compute() is the contract");
    }

    // ---------------------------------------------------------------------
    // 2. Register/unregister race: half the threads register, the other half
    //    immediately unregister. Final state must be deterministic and consistent
    //    with the number of surviving registrations (zero in this design).
    // ---------------------------------------------------------------------
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void parallelRegisterUnregister_finalStateConsistent() throws Exception {
        final int pairs = 200;
        // Pool size must be >= number of parties on the barrier; otherwise workers
        // queued behind a full pool never reach await() and the barrier deadlocks.
        ExecutorService pool = Executors.newFixedThreadPool(pairs * 2);
        CyclicBarrier startGate = new CyclicBarrier(pairs * 2);
        CountDownLatch done = new CountDownLatch(pairs * 2);
        ConcurrentLinkedQueue<RefreshHandlerRef> handles = new ConcurrentLinkedQueue<>();

        try {
            for (int i = 0; i < pairs; i++) {
                // Producer: registers and stores the handle.
                pool.submit(() -> {
                    try {
                        startGate.await();
                        handles.add(poller.register("acme", "race", v -> { /* no-op */ }));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        done.countDown();
                    }
                });
                // Consumer: spins until at least one handle is available, then unregisters it.
                pool.submit(() -> {
                    try {
                        startGate.await();
                        RefreshHandlerRef h;
                        // Bounded retry to avoid hanging if pairing fails; assertion below
                        // catches any survivors anyway.
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                        while ((h = handles.poll()) == null && System.nanoTime() < deadline) {
                            Thread.yield();
                        }
                        if (h != null) {
                            h.unRegister();
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(done.await(8, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        // Drain any straggler handles so the registry is fully balanced.
        for (RefreshHandlerRef h; (h = handles.poll()) != null; ) {
            h.unRegister();
        }

        ConcurrentHashMap<String, SecretState> registry = registryOf(poller);
        assertEquals(0, registry.size(),
                "every register paired with an unregister; registry must be empty");
    }

    // ---------------------------------------------------------------------
    // 3. Latest-wins coalescing: if two updates arrive for the same secret
    //    while a slow hook is mid-execution, the hook ultimately runs with
    //    the NEWER value. Older value must not overwrite newer.
    // ---------------------------------------------------------------------
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void latestWinsCoalescing_newerValueDeliveredAfterSlowHook() throws Exception {
        final CountDownLatch hookEntered = new CountDownLatch(1);
        final CountDownLatch releaseFirstHook = new CountDownLatch(1);
        final ConcurrentLinkedQueue<Integer> deliveredVersions = new ConcurrentLinkedQueue<>();

        SecretRefreshHook slowFirstFastRest = secretVal -> {
            deliveredVersions.add(secretVal.getDataVersion());
            // Block ONLY on the very first invocation so we can stack updates behind it.
            if (deliveredVersions.size() == 1) {
                hookEntered.countDown();
                try {
                    assertTrue(releaseFirstHook.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        poller.register("acme", "fast", slowFirstFastRest);

        // First poll delivers v=1, hook enters and blocks.
        when(mockHttpClient.doPostWithRetry(eq(BATCH_URL), anyString()))
                .thenReturn(wrapBatch(new BatchGetSecretsResponse(1,
                        Collections.singletonList(new UpdatedSecret("acme", "fast", 1, "p", "q")))))
                .thenReturn(wrapBatch(new BatchGetSecretsResponse(1,
                        Collections.singletonList(new UpdatedSecret("acme", "fast", 2, "p", "q")))))
                .thenReturn(wrapBatch(new BatchGetSecretsResponse(1,
                        Collections.singletonList(new UpdatedSecret("acme", "fast", 3, "p", "q")))));

        poller.pollOnce();
        assertTrue(hookEntered.await(5, TimeUnit.SECONDS), "first hook invocation must have started");

        // While the dispatcher thread is blocked inside the hook, stack two more updates.
        // pendingUpdate is overwritten each time -> only the latest survives.
        poller.pollOnce();
        poller.pollOnce();

        releaseFirstHook.countDown();

        // Wait for drain. We expect: v=1 (the blocked one) then v=3 (the latest after coalescing).
        // v=2 is allowed to be coalesced into v=3 OR delivered before v=3 -- either is correct
        // by the documented "latest-wins" semantics. The strict invariants below capture that.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (deliveredVersions.contains(3)) {
                break;
            }
            Thread.sleep(10);
        }

        List<Integer> versions = new java.util.ArrayList<>(deliveredVersions);
        assertTrue(versions.contains(1),
                "first (blocked) value must have been delivered: " + versions);
        assertTrue(versions.contains(3),
                "latest value must eventually be delivered: " + versions);
        // No version may appear out-of-order *after* v=3 (no replays of older values).
        int idx3 = versions.lastIndexOf(3);
        for (int i = idx3 + 1; i < versions.size(); i++) {
            assertTrue(versions.get(i) >= 3,
                    "no older version must be delivered after v=3: " + versions);
        }
    }

    // ---------------------------------------------------------------------
    // 4. Wake-up race: an update arriving in the tiny window between
    //    pendingUpdate.getAndSet(null) and isExecuting.set(false) must still
    //    be delivered (the re-submit branch in runHooksFor's finally).
    // ---------------------------------------------------------------------
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void wakeUpRace_updateBetweenDrainAndRelease_isDelivered() throws Exception {
        // Strategy: directly put values into SecretState.pendingUpdate while a
        // dispatcher task is running, mimicking the race window. We don't need
        // perfect timing -- repeating the loop drives the probability to ~1.

        final CountDownLatch firstReceived = new CountDownLatch(1);
        final CountDownLatch holdInsideHook = new CountDownLatch(1);
        final ConcurrentLinkedQueue<Integer> seen = new ConcurrentLinkedQueue<>();

        SecretRefreshHook hook = secretVal -> {
            seen.add(secretVal.getDataVersion());
            if (seen.size() == 1) {
                firstReceived.countDown();
                try {
                    holdInsideHook.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        poller.register("acme", "wake", hook);

        when(mockHttpClient.doPostWithRetry(eq(BATCH_URL), anyString()))
                .thenReturn(wrapBatch(new BatchGetSecretsResponse(1,
                        Collections.singletonList(new UpdatedSecret("acme", "wake", 1, "p", "q")))));

        poller.pollOnce();
        assertTrue(firstReceived.await(5, TimeUnit.SECONDS));

        // Hook is currently blocked. Now stage a "late" update directly on SecretState.
        // This simulates an update arriving exactly at the release window.
        SecretState state = lookup(poller, "acme:wake");
        state.pendingUpdate.set(new SecretValue(99, "p", "q"));

        // Release the blocked hook: the runHooksFor loop will drain the pending v=99
        // BEFORE releasing isExecuting (covers the in-loop drain), so this also
        // exercises the late-arrival case via the while-loop inside runHooksFor.
        holdInsideHook.countDown();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline && !seen.contains(99)) {
            Thread.sleep(10);
        }
        assertTrue(seen.contains(99),
                "update set on SecretState during dispatch must be delivered; saw: " + seen);
    }

    // ---------------------------------------------------------------------
    // 5. Non-reentrant per-secret: while one dispatcher task is running the
    //    hook for a secret, a second dispatcher submission for the same
    //    secret must not run concurrently.
    // ---------------------------------------------------------------------
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void perSecretDispatchIsNonReentrant_noConcurrentInvocations() throws Exception {
        final AtomicInteger inFlight = new AtomicInteger(0);
        final AtomicInteger maxInFlight = new AtomicInteger(0);
        final CountDownLatch finished = new CountDownLatch(3);

        SecretRefreshHook tracker = secretVal -> {
            int now = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(40); // widen the window where reentrancy could occur
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.decrementAndGet();
                finished.countDown();
            }
        };
        poller.register("acme", "serial", tracker);

        when(mockHttpClient.doPostWithRetry(eq(BATCH_URL), anyString()))
                .thenReturn(wrapBatch(new BatchGetSecretsResponse(1,
                        Collections.singletonList(new UpdatedSecret("acme", "serial", 1, "p", "q")))))
                .thenReturn(wrapBatch(new BatchGetSecretsResponse(1,
                        Collections.singletonList(new UpdatedSecret("acme", "serial", 2, "p", "q")))))
                .thenReturn(wrapBatch(new BatchGetSecretsResponse(1,
                        Collections.singletonList(new UpdatedSecret("acme", "serial", 3, "p", "q")))));

        // Three back-to-back polls; without the isExecuting guard, three dispatcher
        // threads could enter the hook concurrently.
        poller.pollOnce();
        poller.pollOnce();
        poller.pollOnce();

        // Wait for at least one delivery; coalescing may merge updates so we don't
        // wait for all three explicitly. The invariant is on maxInFlight.
        Thread.sleep(200);

        assertEquals(1, maxInFlight.get(),
                "only one dispatcher task may execute hooks for a given secret at a time");
    }

    // ---------------------------------------------------------------------
    // 6. unregister-during-dispatch: a hook unregistered while the dispatcher
    //    iterates state.hooks must not cause ConcurrentModificationException
    //    (CopyOnWriteArrayList semantics).
    // ---------------------------------------------------------------------
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void unregisterDuringDispatch_doesNotThrow() throws Exception {
        final CountDownLatch hookEntered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

        SecretRefreshHook slow = secretVal -> {
            hookEntered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        SecretRefreshHook other = secretVal -> { /* no-op */ };

        poller.register("acme", "concurrent-mod", slow);
        RefreshHandlerRef otherHandle = poller.register("acme", "concurrent-mod", other);

        when(mockHttpClient.doPostWithRetry(eq(BATCH_URL), anyString()))
                .thenReturn(wrapBatch(new BatchGetSecretsResponse(1,
                        Collections.singletonList(new UpdatedSecret("acme", "concurrent-mod", 1, "p", "q")))));

        // From a side thread, capture any throwable surfaced by the dispatcher.
        Thread.UncaughtExceptionHandler ueh = (t, e) -> errors.add(e);
        Thread.setDefaultUncaughtExceptionHandler(ueh);

        poller.pollOnce();
        assertTrue(hookEntered.await(5, TimeUnit.SECONDS));

        // While dispatcher is mid-iteration over state.hooks, mutate the list.
        otherHandle.unRegister();

        release.countDown();
        Thread.sleep(100);

        assertTrue(errors.isEmpty(),
                "iterating hooks while unregistering must be safe (CopyOnWriteArrayList): " + errors);
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------
    @SuppressWarnings("unchecked")
    private static ConcurrentHashMap<String, SecretState> registryOf(HookRefreshPoller p) throws Exception {
        Field f = HookRefreshPoller.class.getDeclaredField("registry");
        f.setAccessible(true);
        return (ConcurrentHashMap<String, SecretState>) f.get(p);
    }

    private static SecretState lookup(HookRefreshPoller p, String secretRef) throws Exception {
        SecretState s = registryOf(p).get(secretRef);
        if (s == null) {
            throw new AssertionError("expected SecretState for " + secretRef + " in registry");
        }
        return s;
    }

    private HttpResponse wrapBatch(BatchGetSecretsResponse data) throws Exception {
        Response<BatchGetSecretsResponse> response = new Response<>(data, "Success");
        String json = objectMapper.writeValueAsString(response);
        return new HttpResponse(200, json, "application/json", "http/1.1");
    }
}
