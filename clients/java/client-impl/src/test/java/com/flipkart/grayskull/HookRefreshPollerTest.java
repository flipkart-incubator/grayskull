package com.flipkart.grayskull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flipkart.grayskull.hooks.SecretState;
import com.flipkart.grayskull.models.SecretValue;
import com.flipkart.grayskull.models.response.BatchGetSecretsResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link HookRefreshPoller} package-private helpers exercised via reflection so production
 * code does not need test-only hooks. For behaviour coverage of polling and hooks, see
 * {@link HookRefreshPollerConcurrencyTest} and {@link GrayskullClientImplTest}. The
 * {@code finally} resubmit path is covered in
 * {@link #scheduleFollowUpIfPending_whenPending_submitsRunHooksFor()}.
 */
@ExtendWith(MockitoExtension.class)
class HookRefreshPollerTest {

    private HookRefreshPoller poller;

    @AfterEach
    void tearDown() {
        if (poller != null) {
            poller.close();
        }
    }

    @Test
    void shutdownExecutor_secondAwaitTimeout_reachesStillRunningWarnBranch() throws Exception {
        ExecutorService exec = mock(ExecutorService.class);
        when(exec.awaitTermination(anyLong(), any(TimeUnit.class))).thenReturn(false);

        Method m = HookRefreshPoller.class.getDeclaredMethod(
                "shutdownExecutor", ExecutorService.class, String.class);
        m.setAccessible(true);
        m.invoke(null, exec, "dispatcher");

        verify(exec, atLeastOnce()).shutdown();
        verify(exec, atLeastOnce()).shutdownNow();
        verify(exec, atLeast(2)).awaitTermination(anyLong(), any(TimeUnit.class));
    }

    // -----------------------------------------------------------------------
    // Tests for pollOnce() early returns (registry vs entries)
    // -----------------------------------------------------------------------

    /**
     * Covers: {@code if (registry.isEmpty()) { return; }} (first guard only), not
     * {@code entries.isEmpty()}.
     * <p>
     * When no hooks have been registered the registry is empty, so {@code pollOnce()}
     * returns immediately before building the entries list or making any HTTP call.
     */
    @Test
    void pollOnce_emptyRegistry_returnsWithoutMakingHttpCall() throws Exception {
        GrayskullHttpClient mockHttpClient = mock(GrayskullHttpClient.class);
        poller = new HookRefreshPoller(mockHttpClient, new ObjectMapper(),
                "http://localhost:9999", 60);

        // Registry is empty – pollOnce() should return at the first isEmpty() guard
        poller.pollOnce();

        verify(mockHttpClient, never()).doPostWithRetry(anyString(), anyString());
    }

    /**
     * Like {@link #pollOnce_emptyRegistry_returnsWithoutMakingHttpCall()}: the registry
     * is empty, so the first {@code registry.isEmpty()} guard applies — not
     * {@code entries.isEmpty()}.
     */
    @Test
    void pollOnce_afterAllHooksUnregistered_returnsWithoutMakingHttpCall() throws Exception {
        GrayskullHttpClient mockHttpClient = mock(GrayskullHttpClient.class);
        poller = new HookRefreshPoller(mockHttpClient, new ObjectMapper(),
                "http://localhost:9999", 60);

        // Register a hook then immediately unregister it
        poller.register("proj", "sec", value -> {}, 0).unRegister();

        poller.pollOnce();

        verify(mockHttpClient, never()).doPostWithRetry(anyString(), anyString());
    }

    /**
     * Covers: {@code if (entries.isEmpty()) { return; }} (both the condition and the
     * {@code return}).
     * <p>
     * Production hits this when another thread unregisters every secret between
     * {@code registry.isEmpty()} and the iteration that populates {@code entries}.
     * A test-only {@link ConcurrentHashMap} subclass recreates that snapshot without
     * relying on a timing race: {@code isEmpty()} is false so the first guard passes,
     * but {@link java.util.Map#values()} is empty so no request entries are added.
     */
    @Test
    void pollOnce_nonEmptyRegistryButEmptyEntryList_returnsAtEntriesGuard() throws Exception {
        GrayskullHttpClient mockHttpClient = mock(GrayskullHttpClient.class);
        poller = new HookRefreshPoller(mockHttpClient, new ObjectMapper(),
                "http://localhost:9999", 60);

        ConcurrentHashMap<String, SecretState> lyingRegistry = new RegistryNonEmptyButYieldsNoValues();
        setRegistryField(poller, lyingRegistry);

        poller.pollOnce();

        verify(mockHttpClient, never()).doPostWithRetry(anyString(), anyString());
    }

    /**
     * Inconsistent (test-only) map: {@code isEmpty()} is false to pass the first
     * {@code pollOnce} guard, while {@code values()} is empty so the batch entry list
     * stays empty — the situation guarded at {@code if (entries.isEmpty())} in production.
     */
    private static final class RegistryNonEmptyButYieldsNoValues extends ConcurrentHashMap<String, SecretState> {
        @Override
        public boolean isEmpty() {
            return false;
        }

        @Override
        public int size() {
            return 0;
        }

        @Override
        public java.util.Collection<SecretState> values() {
            return Collections.emptyList();
        }
    }

    // -----------------------------------------------------------------------
    // Tests for pendingUpdate re-submission guard in runHooksFor()
    // -----------------------------------------------------------------------

    /**
     * Covers the {@code while ((value = pendingUpdate.getAndSet(null)) != null)} drain
     * when a second value is staged while the hook is still running: the second
     * delivery is taken by the next {@code getAndSet} in the same {@code runHooksFor}
     * invocation — not the {@code finally} resubmit branch.
     * <p>
     * Verifies that when a second update is staged in {@link com.flipkart.grayskull.hooks.SecretState#pendingUpdate}
     * while {@code runHooksFor} is already executing (i.e., {@code isExecuting} is
     * {@code true}), the second update is still eventually delivered.  The scenario:
     * <ol>
     *   <li>First call to {@code handleUpdatedSecret} submits {@code runHooksFor} to
     *       the dispatcher.</li>
     *   <li>The hook blocks, simulating slow consumer work.</li>
     *   <li>A concurrent second call to {@code handleUpdatedSecret} stages a new
     *       value; its own dispatcher submission is a no-op because
     *       {@code isExecuting} is {@code true}.</li>
     *   <li>When the hook unblocks, the {@code while} loop in {@code runHooksFor}
     *       drains the newly staged update in a further iteration. The
     *       {@code finally} re-submit path (when a value lands in the
     *       post-drain window) is covered by
     *       {@link #scheduleFollowUpIfPending_whenPending_submitsRunHooksFor()}.</li>
     * </ol>
     */
    @Test
    @SuppressWarnings("unchecked")
    void runHooksFor_secondUpdateArrivesWhileExecuting_bothUpdatesDelivered() throws Exception {
        GrayskullHttpClient mockHttpClient = mock(GrayskullHttpClient.class);
        poller = new HookRefreshPoller(mockHttpClient, new ObjectMapper(),
                "http://localhost:9999", 60);

        CountDownLatch firstHookStarted    = new CountDownLatch(1);
        CountDownLatch allowFirstToFinish  = new CountDownLatch(1);
        CountDownLatch allDelivered        = new CountDownLatch(2);
        AtomicInteger  deliveryCount       = new AtomicInteger(0);

        poller.register("proj", "sec", value -> {
            int n = deliveryCount.incrementAndGet();
            if (n == 1) {
                firstHookStarted.countDown();
                // Block until the test has staged the second update
                try { allowFirstToFinish.await(3, TimeUnit.SECONDS); }
                catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }
            allDelivered.countDown();
        }, 0);

        // Retrieve the private handleUpdatedSecret method to trigger updates
        Method handleUpdated = HookRefreshPoller.class.getDeclaredMethod(
                "handleUpdatedSecret", BatchGetSecretsResponse.UpdatedSecret.class);
        handleUpdated.setAccessible(true);

        // Stage first update – this submits runHooksFor to the dispatcher
        BatchGetSecretsResponse.UpdatedSecret update1 =
                new BatchGetSecretsResponse.UpdatedSecret("proj", "sec", 1, "pub1", "priv1");
        handleUpdated.invoke(poller, update1);

        // Wait for the first hook invocation to start, then stage a second update
        assertTrue(firstHookStarted.await(2, TimeUnit.SECONDS),
                "First hook invocation should start within 2 s");

        // pendingUpdate is set while isExecuting == true; the while-loop drains
        // it in a further getAndSet iteration.
        BatchGetSecretsResponse.UpdatedSecret update2 =
                new BatchGetSecretsResponse.UpdatedSecret("proj", "sec", 2, "pub2", "priv2");
        handleUpdated.invoke(poller, update2);

        // Release the blocked hook so execution can proceed
        allowFirstToFinish.countDown();

        // Both updates must be delivered
        assertTrue(allDelivered.await(3, TimeUnit.SECONDS),
                "Both updates should be delivered within 3 s");
        assertEquals(2, deliveryCount.get(),
                "Hook should be invoked exactly twice – once per update");
    }

    /**
     * The post-drain {@code finally} path calls {@code scheduleFollowUpIfPending} (exercise the
     * private method directly). A same-thread {@link ExecutorService} makes
     * {@code dispatcher.submit(() -> runHooksFor(...))} run the nested
     * {@code runHooksFor} inline so the hook is invoked in-test without a race on the tiny
     * inter-instruction window between the drain and {@code get()}.
     */
    @Test
    void scheduleFollowUpIfPending_whenPending_submitsRunHooksFor() throws Exception {
        GrayskullHttpClient mockHttpClient = mock(GrayskullHttpClient.class);
        poller = new HookRefreshPoller(mockHttpClient, new ObjectMapper(),
                "http://localhost:9999", 60);

        setFinalField(poller, "dispatcher", newSameThreadExecutor());

        AtomicInteger hookInvocations = new AtomicInteger(0);
        poller.register("p", "s", v -> hookInvocations.incrementAndGet(), 0);

        Field registryF = HookRefreshPoller.class.getDeclaredField("registry");
        registryF.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, SecretState> registry =
                (ConcurrentHashMap<String, SecretState>) registryF.get(poller);
        SecretState state = registry.get("p:s");
        if (state == null) {
            throw new IllegalStateException("register should have created SecretState for p:s");
        }

        state.isExecuting.set(false);
        state.pendingUpdate.set(new SecretValue(42, "a", "b"));

        Method scheduleFollowUpIfPending = HookRefreshPoller.class.getDeclaredMethod(
                "scheduleFollowUpIfPending", String.class, SecretState.class);
        scheduleFollowUpIfPending.setAccessible(true);
        scheduleFollowUpIfPending.invoke(poller, "p:s", state);

        assertEquals(1, hookInvocations.get());
    }

    private static AbstractExecutorService newSameThreadExecutor() {
        return new AbstractExecutorService() {
            private final AtomicBoolean shut = new AtomicBoolean();

            @Override
            public void shutdown() {
                shut.set(true);
            }

            @Override
            public List<Runnable> shutdownNow() {
                shut.set(true);
                return Collections.emptyList();
            }

            @Override
            public boolean isShutdown() {
                return shut.get();
            }

            @Override
            public boolean isTerminated() {
                return shut.get();
            }

            @Override
            public boolean awaitTermination(long timeout, TimeUnit unit) {
                return true;
            }

            @Override
            public void execute(Runnable r) {
                r.run();
            }
        };
    }

    private static void setRegistryField(HookRefreshPoller poller, ConcurrentHashMap<String, SecretState> map)
            throws Exception {
        setFinalField(poller, "registry", map);
    }

    private static void setFinalField(HookRefreshPoller poller, String name, Object value) throws Exception {
        Field f = HookRefreshPoller.class.getDeclaredField(name);
        f.setAccessible(true);
        Field modifiers = Field.class.getDeclaredField("modifiers");
        modifiers.setAccessible(true);
        modifiers.setInt(f, f.getModifiers() & ~Modifier.FINAL);
        f.set(poller, value);
    }
}
