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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
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
 * {@link HookRefreshPollerConcurrencyTest} and {@link GrayskullClientImplTest}.
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
    // Tests for entries.isEmpty() early-return guard in pollOnce()
    // -----------------------------------------------------------------------

    /**
     * Covers: {@code if (entries.isEmpty()) { return; }}
     * <p>
     * When no hooks have been registered the registry is empty, so {@code pollOnce()}
     * returns immediately (first guard at {@code registry.isEmpty()}) without building
     * an entries list or making any HTTP call.  The {@code entries.isEmpty()} guard at
     * line 177 is a defensive check for the concurrent-removal race (registry
     * non-empty at the first check but drained before the iteration completes); the
     * single-threaded case that exercises the same "nothing to do → return" semantics
     * is captured here.
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
     * Covers: {@code if (entries.isEmpty()) { return; }}
     * <p>
     * After a hook is registered and then fully unregistered the registry is again
     * empty.  A subsequent {@code pollOnce()} must return without making any HTTP
     * call, exercising the same early-return path as the always-empty case above.
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

    // -----------------------------------------------------------------------
    // Tests for pendingUpdate re-submission guard in runHooksFor()
    // -----------------------------------------------------------------------

    /**
     * Covers: {@code if (state.pendingUpdate.get() != null) { dispatcher.submit(...); }}
     * <p>
     * Verifies that when a second update is staged in {@link SecretState#pendingUpdate}
     * while {@code runHooksFor} is already executing (i.e., {@code isExecuting} is
     * {@code true}), the second update is still eventually delivered.  The scenario:
     * <ol>
     *   <li>First call to {@code handleUpdatedSecret} submits {@code runHooksFor} to
     *       the dispatcher.</li>
     *   <li>The hook blocks, simulating slow consumer work.</li>
     *   <li>A concurrent second call to {@code handleUpdatedSecret} stages a new
     *       {@code pendingUpdate}; its own dispatcher submission is a no-op because
     *       {@code isExecuting} is {@code true}.</li>
     *   <li>When the hook unblocks, the {@code while} loop in {@code runHooksFor}
     *       drains the newly staged update <em>or</em> the {@code finally} block
     *       re-submits the task — either way the second delivery must occur.</li>
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

        // Retrieve internal state for direct manipulation
        Field registryField = HookRefreshPoller.class.getDeclaredField("registry");
        registryField.setAccessible(true);
        ConcurrentHashMap<String, SecretState> registry =
                (ConcurrentHashMap<String, SecretState>) registryField.get(poller);
        SecretState state = registry.get("proj:sec");

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

        // pendingUpdate is set while isExecuting == true; the currently running
        // runHooksFor will pick this up either via the while-loop or the finally re-submit.
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
}
