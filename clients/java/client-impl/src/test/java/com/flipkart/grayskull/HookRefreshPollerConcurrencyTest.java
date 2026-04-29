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
    //    without invoking the HTTP client (covers the `entries.isEmpty()`
    //    `return;` branch). This branch is reachable when the registry is
    //    concurrently drained between registry.isEmpty() and the values()-
    //    iteration. We simulate it deterministically by replacing the registry
    //    field with a custom ConcurrentHashMap whose isEmpty() returns false
    //    but values() returns an empty collection -- mimicking exactly the
    //    transient state visible across a concurrent removal.
    // ---------------------------------------------------------------------
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void pollOnce_emptyEntriesAfterIsEmptyCheck_shortCircuits() throws Exception {
        // Custom registry: reports non-empty for the gate check at the top of
        // pollOnce, but yields no values when iterated. This is the exact
        // scenario the `entries.isEmpty() -> return;` guard exists to handle.
        ConcurrentHashMap<String, SecretState> fakeRegistry = new ConcurrentHashMap<String, SecretState>() {
            @Override
            public boolean isEmpty() {
                return false;
            }

            @Override
            public java.util.Collection<SecretState> values() {
                return Collections.emptyList();
            }

            @Override
            public int size() {
                return 0;
            }
        };

        Field registryField = HookRefreshPoller.class.getDeclaredField("registry");
        registryField.setAccessible(true);
        registryField.set(poller, fakeRegistry);

        poller.pollOnce();

        // No HTTP work must have been issued because entries was empty.
        org.mockito.Mockito.verify(mockHttpClient, org.mockito.Mockito.never())
                .doPostWithRetry(eq(BATCH_URL), anyString());
    }

    // ---------------------------------------------------------------------
    // 10. runHooksFor finally-branch resubmit: when an update lands AFTER the
    //     drain loop has exited but BEFORE isExecuting flips back to false,
    //     the finally block must resubmit a follow-up dispatch so the late
    //     update is not lost. This is the
    //     `if (state.pendingUpdate.get() != null) dispatcher.submit(...)` path.
    //
    //     The race window is microseconds. We make the test reliable by:
    //       (a) calling runHooksFor directly via reflection (so the test thread
    //           drives the drain loop -- no dispatcher latency), and
    //       (b) running a tight side-thread that *waits for the drain loop to
    //           exit* (state.pendingUpdate observed null after a non-null
    //           snapshot) and then stages a new value, hoping to land before
    //           isExecuting flips false. With many iterations this hits the
    //           target branch with probability ~1.
    //     We additionally wrap `dispatcher` in an instrumented executor so we
    //     can assert the resubmit submission was issued from the finally block.
    // ---------------------------------------------------------------------
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void runHooksFor_lateUpdateAfterDrain_isResubmittedFromFinally() throws Exception {
        final ConcurrentLinkedQueue<Integer> seen = new ConcurrentLinkedQueue<>();

        SecretRefreshHook hook = v -> seen.add(v.getDataVersion());
        poller.register("acme", "late", hook);
        SecretState state = lookup(poller, "acme:late");

        // Wrap the dispatcher to count submissions issued WHILE the test is
        // driving runHooksFor on the test thread. Any submission seen here
        // came from the finally-branch resubmit (handleUpdatedSecret is not
        // exercised in this test).
        Field dispatcherField = HookRefreshPoller.class.getDeclaredField("dispatcher");
        dispatcherField.setAccessible(true);
        ExecutorService originalDispatcher = (ExecutorService) dispatcherField.get(poller);
        AtomicInteger resubmitCount = new AtomicInteger(0);
        ExecutorService countingDispatcher = new java.util.concurrent.AbstractExecutorService() {
            @Override public void shutdown() { originalDispatcher.shutdown(); }
            @Override public java.util.List<Runnable> shutdownNow() { return originalDispatcher.shutdownNow(); }
            @Override public boolean isShutdown() { return originalDispatcher.isShutdown(); }
            @Override public boolean isTerminated() { return originalDispatcher.isTerminated(); }
            @Override public boolean awaitTermination(long t, TimeUnit u) throws InterruptedException {
                return originalDispatcher.awaitTermination(t, u);
            }
            @Override public void execute(Runnable command) {
                resubmitCount.incrementAndGet();
                originalDispatcher.execute(command);
            }
        };
        dispatcherField.set(poller, countingDispatcher);

        java.lang.reflect.Method runHooksFor = HookRefreshPoller.class.getDeclaredMethod(
                "runHooksFor", String.class, SecretState.class);
        runHooksFor.setAccessible(true);

        // Drive the race in a tight loop. The "racer" thread waits until
        // state.isExecuting is observed true (drain in progress) and then
        // stages a fresh pendingUpdate; with many iterations it eventually
        // lands AFTER the while loop exits and BEFORE the if-check, hitting
        // the resubmit branch.
        final java.util.concurrent.atomic.AtomicBoolean stop =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        AtomicInteger raceVersion = new AtomicInteger(1_000_000);

        ExecutorService racer = Executors.newSingleThreadExecutor();
        try {
            racer.submit(() -> {
                while (!stop.get()) {
                    if (state.isExecuting.get()) {
                        // Stage a value while a drain is in progress. Many of
                        // these are caught by the while loop and consumed
                        // benignly; some land in the post-drain pre-release
                        // window we are targeting.
                        state.pendingUpdate.set(new SecretValue(
                                raceVersion.incrementAndGet(), "p", "q"));
                    }
                    Thread.yield();
                }
            });

            // Drive many runHooksFor invocations from the test thread.
            for (int i = 0; i < 5_000 && resubmitCount.get() == 0; i++) {
                state.pendingUpdate.set(new SecretValue(i, "p", "q"));
                runHooksFor.invoke(poller, "acme:late", state);
                if ((i & 0xff) == 0xff) {
                    Thread.yield();
                }
            }
        } finally {
            stop.set(true);
            racer.shutdownNow();
            racer.awaitTermination(2, TimeUnit.SECONDS);
            // Drain anything residual so we don't leak state across tests.
            state.pendingUpdate.set(null);
            // Restore the original dispatcher before tearDown closes the poller.
            dispatcherField.set(poller, originalDispatcher);
        }

        assertTrue(resubmitCount.get() >= 1,
                "finally-branch resubmit must have fired at least once across the race; "
                        + "delivered=" + seen.size() + ", resubmits=" + resubmitCount.get());
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
    // 13. close() second-await branch: even shutdownNow() does not stop a
    //     dispatcher task that swallows interrupts. shutdownExecutor must
    //     await once more and emit the "still has running tasks after
    //     force-shutdown" warning. Covers the inner
    //     `if (!awaitTermination(...)) log.warn(...)` line.
    // ---------------------------------------------------------------------
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void close_logsWarning_whenForceShutdownAlsoTimesOut() throws Exception {
        // To exercise the inner await-and-warn branch we need a task that
        // refuses to honor interruption. We hold the dispatcher thread in a
        // tight CAS-spin until our test releases it AFTER both await windows
        // have elapsed. We then verify, via a captured logback ListAppender,
        // that the warning line was emitted.
        ch.qos.logback.classic.Logger pollerLogger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(HookRefreshPoller.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        pollerLogger.addAppender(appender);

        HookRefreshPoller localPoller = new HookRefreshPoller(
                mockHttpClient, objectMapper, "https://test.grayskull.com", LONG_INTERVAL_SECONDS);

        final java.util.concurrent.atomic.AtomicBoolean release =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        final CountDownLatch entered = new CountDownLatch(1);

        try {
            SecretRefreshHook unkillable = v -> {
                entered.countDown();
                // Spin loop that ignores interrupts. The interrupt flag will be
                // set by shutdownNow(); we explicitly clear it and keep
                // spinning until the test signals release.
                while (!release.get()) {
                    if (Thread.interrupted()) {
                        // Swallow the interrupt -- this is exactly what causes
                        // the inner awaitTermination to time out.
                    }
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ignored) {
                        // Swallow and keep going.
                    }
                }
            };
            localPoller.register("acme", "unkillable", unkillable);

            when(mockHttpClient.doPostWithRetry(eq(BATCH_URL), anyString()))
                    .thenReturn(wrapBatch(new BatchGetSecretsResponse(1,
                            Collections.singletonList(new UpdatedSecret(
                                    "acme", "unkillable", 1, "p", "q")))));

            localPoller.pollOnce();
            assertTrue(entered.await(5, TimeUnit.SECONDS),
                    "unkillable hook must have started before close()");

            // Run close() on a side thread because it will block for ~20s
            // (two SHUTDOWN_AWAIT_SECONDS=10s windows for the dispatcher).
            ExecutorService closer = Executors.newSingleThreadExecutor();
            try {
                java.util.concurrent.Future<?> f = closer.submit(localPoller::close);
                // Wait long enough for both await windows to elapse on the
                // dispatcher executor (10s + 10s + slack). The scheduler shuts
                // down quickly so its windows do not contribute.
                try {
                    f.get(45, TimeUnit.SECONDS);
                } catch (java.util.concurrent.TimeoutException te) {
                    // Should not happen; if it does, release the hook to free
                    // resources before failing.
                    release.set(true);
                    throw te;
                }
            } finally {
                release.set(true);
                closer.shutdownNow();
                closer.awaitTermination(5, TimeUnit.SECONDS);
            }

            boolean warnEmitted = appender.list.stream().anyMatch(e ->
                    e.getLevel() == ch.qos.logback.classic.Level.WARN
                            && e.getFormattedMessage() != null
                            && e.getFormattedMessage().contains("still has running tasks after force-shutdown"));
            assertTrue(warnEmitted,
                    "expected the 'still has running tasks after force-shutdown' warning; events="
                            + appender.list);
        } finally {
            release.set(true);
            pollerLogger.detachAppender(appender);
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
