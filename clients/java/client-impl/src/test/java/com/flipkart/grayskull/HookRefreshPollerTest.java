package com.flipkart.grayskull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link HookRefreshPoller} package-private helpers exercised via reflection so production
 * code does not need test-only hooks. For behaviour coverage of polling and hooks, see
 * {@link HookRefreshPollerConcurrencyTest} and {@link GrayskullClientImplTest}.
 */
@ExtendWith(MockitoExtension.class)
class HookRefreshPollerTest {

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
}
