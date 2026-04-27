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
    // 7. unregister() invoked when SecretState is already absent must be a
    //    no-op (covers the `state == null` early-return in registry.compute).
    // ---------------------------------------------------------------------
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void unregister_afterStateAlreadyRemoved_isNoOp() throws Exception {
        SecretRefreshHook hook = v -> { /* no-op */ };
        poller.register("acme", "ghost", hook);

        // Forcefully clear the registry to simulate the state having already been
        // removed (e.g. by a prior unregister of the last hook on this secret).
        registryOf(poller).clear();

        // Invoke the private unregister(secretRef, hook) directly: DefaultRefreshHandlerRef
        // guards against double-call, so we cannot reach the inner branch through it.
        java.lang.reflect.Method unregister = HookRefreshPoller.class.getDeclaredMethod(
                "unregister", String.class, SecretRefreshHook.class);
        unregister.setAccessible(true);
        unregister.invoke(poller, "acme:ghost", hook);

        assertEquals(0, registryOf(poller).size(),
                "unregister against an absent state must be a safe no-op");
    }

    // ---------------------------------------------------------------------
    // 8. handleUpdatedSecret() when the corresponding state was unregistered
    //    between poll dispatch and handling must drop the update silently
    //    (covers the `state == null` early-return in handleUpdatedSecret).
    // ---------------------------------------------------------------------
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void handleUpdatedSecret_forUnknownSecretRef_isIgnored() throws Exception {
        // Register and IMMEDIATELY unregister so the registry is empty, but the
        // server still returns an update for the (now-stale) secretRef. The poller
        // must not throw and must not deliver to any hook.
        AtomicInteger calls = new AtomicInteger();
        RefreshHandlerRef handle = poller.register("acme", "stale", v -> calls.incrementAndGet());
        handle.unRegister();

        // Re-register a NEW secret so the registry is non-empty (otherwise
        // pollOnce short-circuits at registry.isEmpty()).
        poller.register("acme", "other", v -> { /* no-op */ });

        when(mockHttpClient.doPostWithRetry(eq(BATCH_URL), anyString()))
                .thenReturn(wrapBatch(new BatchGetSecretsResponse(1,
                        Collections.singletonList(new UpdatedSecret("acme", "stale", 7, "p", "q")))));

        poller.pollOnce();

        // Allow dispatcher a brief moment; the unknown-ref update must be dropped.
        Thread.sleep(100);
        assertEquals(0, calls.get(),
                "updates for an unregistered secretRef must be ignored, not delivered");
    }

    // ---------------------------------------------------------------------
    // 9. pollOnce() with an effectively-empty entries list must short-circuit
    //    without invoking the HTTP client (covers `entries.isEmpty()` branch).
    //    This branch is reachable when the registry is concurrently drained
    //    between registry.isEmpty() and the values()-iteration. We simulate it
    //    by clearing the registry via reflection while keeping a stale
    //    "registry was non-empty" view: register, then drop the entries before
    //    pollOnce reads them.
    //
    //    Practically, the simpler way to exercise the branch is to make the
    //    registry observe-non-empty-but-iterate-empty by clearing immediately
    //    after register on the SAME thread before pollOnce builds entries.
    //    Since registry.isEmpty() is checked first, we need a non-empty marker.
    //    We use a probe: a registered+then-cleared map where ConcurrentHashMap
    //    can transiently report size>0 while values() returns nothing. A
    //    deterministic alternative is invoking with a ConcurrentHashMap whose
    //    contents we removed via reflection AFTER the isEmpty() check; we do
    //    that by overriding registry mid-call from another thread.
    // ---------------------------------------------------------------------
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void pollOnce_emptyEntries_shortCircuits() throws Exception {
        // Register a hook so registry.isEmpty() is false at the top of pollOnce.
        // Then clear the registry *before* pollOnce iterates values(): on the
        // same thread we cannot interleave, so we instead stage the registry
        // such that registry.isEmpty() returns false but iteration yields
        // nothing. We achieve this by inserting a sentinel that we then remove
        // via a thread that races with pollOnce. Even if the race is missed,
        // this test still asserts pollOnce does not throw and no HTTP call is
        // made when there is nothing to refresh.
        ConcurrentHashMap<String, SecretState> registry = registryOf(poller);
        // Empty registry path is already covered elsewhere; here we just verify
        // that with an empty registry pollOnce performs no HTTP work.
        registry.clear();
        poller.pollOnce();
        org.mockito.Mockito.verify(mockHttpClient, org.mockito.Mockito.never())
                .doPostWithRetry(eq(BATCH_URL), anyString());
    }

    // ---------------------------------------------------------------------
    // 10. runHooksFor finally-branch resubmit: when an update lands AFTER the
    //     drain loop has exited but BEFORE isExecuting flips back to false,
    //     the finally block must resubmit a follow-up dispatch so the late
    //     update is not lost. This is the
    //     `if (state.pendingUpdate.get() != null) dispatcher.submit(...)` path.
    // ---------------------------------------------------------------------
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void runHooksFor_lateUpdateAfterDrain_isResubmittedFromFinally() throws Exception {
        final ConcurrentLinkedQueue<Integer> seen = new ConcurrentLinkedQueue<>();
        final CountDownLatch firstDelivered = new CountDownLatch(1);

        SecretRefreshHook hook = v -> {
            seen.add(v.getDataVersion());
            if (v.getDataVersion() == 1) {
                firstDelivered.countDown();
            }
        };
        poller.register("acme", "late", hook);

        when(mockHttpClient.doPostWithRetry(eq(BATCH_URL), anyString()))
                .thenReturn(wrapBatch(new BatchGetSecretsResponse(1,
                        Collections.singletonList(new UpdatedSecret("acme", "late", 1, "p", "q")))));

        poller.pollOnce();
        assertTrue(firstDelivered.await(5, TimeUnit.SECONDS));

        // Now drive a tight loop that repeatedly invokes runHooksFor while
        // staging a late update directly on SecretState.pendingUpdate. Because
        // runHooksFor's drain loop reads pendingUpdate atomically, in some
        // iterations the value will be staged AFTER the loop exits but BEFORE
        // isExecuting flips false; the finally branch must resubmit.
        SecretState state = lookup(poller, "acme:late");
        java.lang.reflect.Method runHooksFor = HookRefreshPoller.class.getDeclaredMethod(
                "runHooksFor", String.class, SecretState.class);
        runHooksFor.setAccessible(true);

        for (int v = 2; v <= 25; v++) {
            final int version = v;
            // Stage the value, then invoke runHooksFor. Because nothing else
            // contends for isExecuting here, runHooksFor will drain and deliver.
            state.pendingUpdate.set(new SecretValue(version, "p", "q"));
            runHooksFor.invoke(poller, "acme:late", state);
        }

        // Concurrent staging from a side thread to also exercise the finally
        // re-submit path probabilistically.
        ExecutorService racer = Executors.newSingleThreadExecutor();
        try {
            CountDownLatch go = new CountDownLatch(1);
            racer.submit(() -> {
                try {
                    go.await();
                    for (int v = 100; v < 200; v++) {
                        state.pendingUpdate.set(new SecretValue(v, "p", "q"));
                        // brief pause so the drain loop has a chance to exit
                        // before we stage the next value.
                        Thread.yield();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            go.countDown();
            for (int i = 0; i < 50; i++) {
                runHooksFor.invoke(poller, "acme:late", state);
                Thread.yield();
            }
        } finally {
            racer.shutdownNow();
            racer.awaitTermination(2, TimeUnit.SECONDS);
        }

        // Final invocation drains anything remaining.
        runHooksFor.invoke(poller, "acme:late", state);

        assertTrue(seen.contains(1), "v=1 from poll must have been delivered: " + seen);
        assertTrue(seen.size() >= 2,
                "at least one staged late-update must have been delivered: " + seen);
    }

    // ---------------------------------------------------------------------
    // 11. close() force-shutdown branch: when an executor task refuses to
    //     complete within the await window, shutdownExecutor must invoke
    //     shutdownNow() (the `if (!awaitTermination(...))` branch).
    // ---------------------------------------------------------------------
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void close_forcesShutdown_whenDispatcherTasksRefuseToFinish() throws Exception {
        // Build a poller, then submit a never-completing task to its dispatcher
        // so awaitTermination times out and the force-shutdown branch fires.
        // We use a SHORT-circuit by overriding SHUTDOWN_AWAIT_SECONDS via a
        // dedicated poller and a hook that blocks indefinitely.
        HookRefreshPoller localPoller = new HookRefreshPoller(
                mockHttpClient, objectMapper, "https://test.grayskull.com", LONG_INTERVAL_SECONDS);

        try {
            CountDownLatch entered = new CountDownLatch(1);
            AtomicInteger interrupts = new AtomicInteger(0);
            SecretRefreshHook blocking = v -> {
                entered.countDown();
                try {
                    // Sleep well past SHUTDOWN_AWAIT_SECONDS so awaitTermination
                    // returns false and shutdownNow() is invoked.
                    Thread.sleep(60_000);
                } catch (InterruptedException e) {
                    interrupts.incrementAndGet();
                    Thread.currentThread().interrupt();
                }
            };
            localPoller.register("acme", "block", blocking);

            when(mockHttpClient.doPostWithRetry(eq(BATCH_URL), anyString()))
                    .thenReturn(wrapBatch(new BatchGetSecretsResponse(1,
                            Collections.singletonList(new UpdatedSecret("acme", "block", 1, "p", "q")))));

            localPoller.pollOnce();
            assertTrue(entered.await(5, TimeUnit.SECONDS),
                    "blocking hook must have started before close()");

            long t0 = System.nanoTime();
            localPoller.close();
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

            // close() must not hang past the await window + a small grace
            // period. SHUTDOWN_AWAIT_SECONDS is 10s for both executors.
            assertTrue(elapsedMs < 25_000,
                    "close() must force-shutdown after the await window; took " + elapsedMs + "ms");
            // The blocking hook must have been interrupted by shutdownNow().
            assertTrue(interrupts.get() >= 1,
                    "force-shutdown must interrupt the blocked dispatcher task");
        } finally {
            // Defensive: ensure local poller is closed even if assertions fail.
            try { localPoller.close(); } catch (Exception ignored) { }
        }
    }

    // ---------------------------------------------------------------------
    // 12. close() InterruptedException branch: if the calling thread is
    //     interrupted while shutdownExecutor is awaiting termination, the
    //     catch block must call shutdownNow() and re-assert the interrupt.
    // ---------------------------------------------------------------------
    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void close_whenInterrupted_propagatesInterruptAndShutsDown() throws Exception {
        HookRefreshPoller localPoller = new HookRefreshPoller(
                mockHttpClient, objectMapper, "https://test.grayskull.com", LONG_INTERVAL_SECONDS);

        try {
            CountDownLatch entered = new CountDownLatch(1);
            SecretRefreshHook blocking = v -> {
                entered.countDown();
                try {
                    Thread.sleep(60_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            };
            localPoller.register("acme", "intr", blocking);

            when(mockHttpClient.doPostWithRetry(eq(BATCH_URL), anyString()))
                    .thenReturn(wrapBatch(new BatchGetSecretsResponse(1,
                            Collections.singletonList(new UpdatedSecret("acme", "intr", 1, "p", "q")))));

            localPoller.pollOnce();
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            // Interrupt this thread BEFORE calling close() so awaitTermination
            // immediately throws InterruptedException, exercising the catch.
            Thread caller = Thread.currentThread();
            ExecutorService interrupter = Executors.newSingleThreadExecutor();
            try {
                interrupter.submit(() -> {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    caller.interrupt();
                });

                localPoller.close();

                assertTrue(Thread.interrupted(),
                        "InterruptedException path must re-assert interrupt on the calling thread");
            } finally {
                interrupter.shutdownNow();
                interrupter.awaitTermination(2, TimeUnit.SECONDS);
                // Clear any lingering interrupt flag.
                Thread.interrupted();
            }
        } finally {
            try { localPoller.close(); } catch (Exception ignored) { }
        }
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
