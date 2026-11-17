package com.flipkart.grayskull;

import com.flipkart.grayskull.models.request.HeartbeatRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsageLedgerTest {

    private final AtomicLong now = new AtomicLong(1_000);
    private final UsageLedger ledger = new UsageLedger(now::get);

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
    void recordGet_countsSuccessAndFailureWithTimestamps() {
        ledger.recordGet("p", "s", 3);
        now.set(2_000);
        ledger.recordGet("p", "s", null);
        now.set(3_000);
        ledger.recordGet("p", "s", 4);

        HeartbeatRequest.HeldSecret e = only();
        assertEquals("p", e.getProjectId());
        assertEquals("s", e.getSecretName());
        assertEquals(2, e.getGetCount());
        assertEquals(1, e.getErrorCount());
        assertEquals(1_000, e.getFirstAccessAt());
        assertEquals(3_000, e.getLastAccessAt());
        assertEquals(4, e.getVersion());
        assertFalse(e.isRefreshHook());
    }

    @Test
    void failedGet_doesNotChangeVersion() {
        ledger.recordGet("p", "s", null);
        assertEquals(0, ledger.version("p", "s"));
        assertEquals(1, only().getErrorCount());
    }

    @Test
    void version_isNewestHeldFromFetchOrPoller() {
        ledger.recordGet("p", "s", 5);
        ledger.recordVersion("p", "s", 8);
        ledger.recordGet("p", "s", 6); // a stale read never lowers the held version
        assertEquals(8, ledger.version("p", "s"));
        assertEquals(8, only().getVersion());
    }

    @Test
    void hookOnly_entryIsReportedWithRefreshHook() {
        ledger.recordHookRegistered("p", "s");
        HeartbeatRequest.HeldSecret e = only();
        assertTrue(e.isRefreshHook());
        assertEquals(0, e.getGetCount());
        assertEquals(0, e.getFirstAccessAt());
    }

    @Test
    void hookCount_tracksRegisterAndUnregister_andEntryIsKept() {
        ledger.recordHookRegistered("p", "s");
        ledger.recordHookRegistered("p", "s");
        ledger.recordHookUnregistered("p", "s");
        assertTrue(only().isRefreshHook());
        ledger.recordHookUnregistered("p", "s");
        ledger.recordHookUnregistered("p", "s"); // never below zero
        assertFalse(only().isRefreshHook());
        ledger.recordHookRegistered("p", "s");
        assertTrue(only().isRefreshHook());
    }

    @Test
    void hookOutcome_recordsAppliedVersionAndFailures() {
        ledger.recordHookApplied("p", "s", 7);
        ledger.recordHookFailed("p", "s", new IllegalStateException("boom"));
        ledger.recordHookApplied("p", "s", 6); // older delivery never lowers it

        HeartbeatRequest.HeldSecret e = only();
        assertEquals(7, e.getHookAppliedVersion());
        assertEquals(1, e.getHookFailureCount());
        assertEquals("java.lang.IllegalStateException: boom", e.getLastHookError());
    }

    @Test
    void describe_truncatesAndHandlesNullMessage() {
        assertNull(UsageLedger.describe(null));
        assertEquals("java.lang.RuntimeException", UsageLedger.describe(new RuntimeException()));
        StringBuilder longMessage = new StringBuilder();
        for (int i = 0; i < 1_000; i++) {
            longMessage.append('x');
        }
        String described = UsageLedger.describe(new RuntimeException(longMessage.toString()));
        assertEquals(UsageLedger.MAX_ERROR_LENGTH, described.length());
        assertTrue(described.startsWith("java.lang.RuntimeException: xxx"));
    }

    @Test
    void secretsAreKeptApart() {
        ledger.recordGet("p", "a", 1);
        ledger.recordHookRegistered("p", "b");
        ledger.recordGet("q", "a", 9);
        assertEquals(3, ledger.snapshot().size());
        assertEquals(1, ledger.version("p", "a"));
        assertEquals(0, ledger.version("p", "b"));
        assertEquals(9, ledger.version("q", "a"));
    }

    @Test
    void concurrentRecording_losesNoCounts() throws Exception {
        int threads = 8;
        int perThread = 2_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            for (int t = 0; t < threads; t++) {
                final int id = t;
                pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        ledger.recordGet("p", "s", id % 2 == 0 ? Integer.valueOf(i) : null);
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
        HeartbeatRequest.HeldSecret e = only();
        assertEquals((long) threads / 2 * perThread, e.getGetCount());
        assertEquals((long) threads / 2 * perThread, e.getErrorCount());
        assertEquals(perThread - 1, e.getVersion());
    }
}
