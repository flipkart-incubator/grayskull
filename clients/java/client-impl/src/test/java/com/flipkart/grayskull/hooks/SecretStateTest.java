package com.flipkart.grayskull.hooks;

import com.flipkart.grayskull.models.SecretValue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link SecretState} default field values. */
class SecretStateTest {

    @Test
    void newState_storesIdentityFields() {
        SecretState s = new SecretState("p", "s");
        assertEquals("p", s.projectId);
        assertEquals("s", s.secretName);
    }

    @Test
    void newState_lastKnownVersionStartsAtZero() {
        SecretState s = new SecretState("p", "s");
        assertEquals(0, s.lastKnownVersion.get(),
                "lastKnownVersion must start at 0 so the first poll asks the server for the current value");
    }

    @Test
    void newState_isExecutingStartsFalse() {
        SecretState s = new SecretState("p", "s");
        assertFalse(s.isExecuting.get());
    }

    @Test
    void newState_pendingUpdateStartsNull_andHooksEmpty() {
        SecretState s = new SecretState("p", "s");
        assertNull(s.pendingUpdate.get());
        assertNotNull(s.hooks);
        assertTrue(s.hooks.isEmpty());
    }

    @Test
    void hooksList_supportsConcurrentIteration_isCopyOnWrite() {
        SecretState s = new SecretState("p", "s");
        SecretRefreshHook a = v -> { /* no-op */ };
        SecretRefreshHook b = v -> { /* no-op */ };
        s.hooks.add(a);
        // Iterating while removing must not throw -- this is the contract that
        // HookRefreshPoller.deliverToHooks relies on.
        for (SecretRefreshHook h : s.hooks) {
            s.hooks.add(b);
            s.hooks.remove(h);
        }
        // Just touch the SecretValue type to keep the import meaningful for
        // future mutation tests.
        SecretValue v = new SecretValue(0, "", "");
        assertNotNull(v);
    }
}
