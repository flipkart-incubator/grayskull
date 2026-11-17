package com.flipkart.grayskull;

import com.flipkart.grayskull.models.request.HeartbeatRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsageLedgerTest {

    private final UsageLedger ledger = new UsageLedger();

    private HeartbeatRequest.HeldSecret only() {
        List<HeartbeatRequest.HeldSecret> snapshot = ledger.snapshot();
        assertEquals(1, snapshot.size());
        return snapshot.get(0);
    }

    @Test
    void emptyLedger_hasEmptySnapshot() {
        assertTrue(ledger.snapshot().isEmpty());
        assertEquals(0, ledger.version("p", "s"));
    }

    @Test
    void fetchedSecret_withoutHook_hasNoRefreshHookAppliedVersion() {
        ledger.recordFetch("p", "s", 3);

        HeartbeatRequest.HeldSecret e = only();
        assertEquals("p", e.getProjectId());
        assertEquals("s", e.getSecretName());
        assertEquals(3, e.getVersion());
        assertNull(e.getRefreshHookAppliedVersion());
    }

    @Test
    void version_isNewestHeldFromFetchOrPoller() {
        ledger.recordFetch("p", "s", 5);
        ledger.recordVersion("p", "s", 8);
        ledger.recordFetch("p", "s", 6); // a stale read never lowers the held version
        assertEquals(8, ledger.version("p", "s"));
        assertEquals(8, only().getVersion());
    }

    @Test
    void registeredHook_reportsZeroUntilItSucceeds() {
        ledger.setHookRegistered("p", "s", true);
        HeartbeatRequest.HeldSecret e = only();
        assertEquals(0, e.getVersion());
        assertEquals(0, e.getRefreshHookAppliedVersion());
    }

    @Test
    void hookApplied_advancesOnlyForward() {
        ledger.setHookRegistered("p", "s", true);
        ledger.recordHookApplied("p", "s", 7);
        ledger.recordHookApplied("p", "s", 6); // an older delivery never lowers it
        assertEquals(7, only().getRefreshHookAppliedVersion());
    }

    @Test
    void hookRegistration_togglesPresenceAndKeepsAppliedVersion() {
        ledger.setHookRegistered("p", "s", true);
        ledger.recordHookApplied("p", "s", 4);
        assertEquals(4, only().getRefreshHookAppliedVersion());
        ledger.setHookRegistered("p", "s", false);
        assertNull(only().getRefreshHookAppliedVersion(), "no hook: field is absent");
        ledger.setHookRegistered("p", "s", true);
        assertEquals(4, only().getRefreshHookAppliedVersion(), "applied version survives re-registration");
    }

    @Test
    void secretsAreKeptApart() {
        ledger.recordFetch("p", "a", 1);
        ledger.setHookRegistered("p", "b", true);
        ledger.recordFetch("q", "a", 9);
        assertEquals(3, ledger.snapshot().size());
        assertEquals(1, ledger.version("p", "a"));
        assertEquals(0, ledger.version("p", "b"));
        assertEquals(9, ledger.version("q", "a"));
    }

    @Test
    void concurrentRecording_keepsNewestVersion() throws Exception {
        int threads = 8;
        int perThread = 2_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        ledger.recordFetch("p", "s", i);
                        ledger.recordHookApplied("p", "s", i);
                        ledger.snapshot();
                    }
                    return null;
                });
            }
            start.countDown();
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        }
        ledger.setHookRegistered("p", "s", true);
        HeartbeatRequest.HeldSecret e = only();
        assertEquals(perThread - 1, e.getVersion());
        assertEquals(perThread - 1, e.getRefreshHookAppliedVersion());
    }
}
