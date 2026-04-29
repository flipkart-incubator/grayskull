package com.flipkart.grayskull.hooks;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link DefaultRefreshHandlerRef}. */
class DefaultRefreshHandlerRefTest {

    @Test
    void newHandle_isActive_andExposesSecretRef() {
        DefaultRefreshHandlerRef ref = new DefaultRefreshHandlerRef("p:s", () -> { /* no-op */ });
        assertTrue(ref.isActive());
        assertEquals("p:s", ref.getSecretRef());
    }

    @Test
    void unRegister_isIdempotent_callbackInvokedExactlyOnce() {
        AtomicInteger calls = new AtomicInteger(0);
        DefaultRefreshHandlerRef ref =
                new DefaultRefreshHandlerRef("p:s", calls::incrementAndGet);

        ref.unRegister();
        ref.unRegister();
        ref.unRegister();

        assertFalse(ref.isActive());
        assertEquals(1, calls.get(), "onUnregister must run exactly once across repeated unRegister() calls");
    }
}
