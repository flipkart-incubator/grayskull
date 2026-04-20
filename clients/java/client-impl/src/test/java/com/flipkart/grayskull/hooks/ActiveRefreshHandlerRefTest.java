package com.flipkart.grayskull.hooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.flipkart.grayskull.SecretRefreshManager;

@ExtendWith(MockitoExtension.class)
class ActiveRefreshHandlerRefTest {

    @Mock
    private SecretRefreshManager manager;

    @Test
    void getSecretRef_returnsCorrectRef() {
        ActiveRefreshHandlerRef ref = new ActiveRefreshHandlerRef("proj:secret", 1L, manager);
        assertEquals("proj:secret", ref.getSecretRef());
    }

    @Test
    void isActive_returnsTrueAfterConstruction() {
        ActiveRefreshHandlerRef ref = new ActiveRefreshHandlerRef("proj:secret", 7L, manager);
        assertTrue(ref.isActive());
    }

    @Test
    void unRegister_callsManagerAndDeactivates() {
        when(manager.unregister("proj:secret", 42L)).thenReturn(true);
        ActiveRefreshHandlerRef ref = new ActiveRefreshHandlerRef("proj:secret", 42L, manager);

        ref.unRegister();

        assertFalse(ref.isActive());
        verify(manager, times(1)).unregister("proj:secret", 42L);
    }

    @Test
    void unRegister_isIdempotent_onlyCallsManagerOnce() {
        when(manager.unregister("proj:secret", 42L)).thenReturn(true);
        ActiveRefreshHandlerRef ref = new ActiveRefreshHandlerRef("proj:secret", 42L, manager);

        ref.unRegister();
        ref.unRegister();
        ref.unRegister();

        assertFalse(ref.isActive());
        verify(manager, times(1)).unregister("proj:secret", 42L);
    }

    @Test
    void unRegister_handlesManagerReturningFalse() {
        // If manager says "nothing removed" we still deactivate and never retry.
        when(manager.unregister("proj:secret", 42L)).thenReturn(false);
        ActiveRefreshHandlerRef ref = new ActiveRefreshHandlerRef("proj:secret", 42L, manager);

        ref.unRegister();

        assertFalse(ref.isActive());
        verify(manager, times(1)).unregister("proj:secret", 42L);
    }

    @Test
    void constructor_rejectsNullSecretRef() {
        assertThrows(IllegalArgumentException.class,
                () -> new ActiveRefreshHandlerRef(null, 1L, manager));
        verifyNoInteractions(manager);
    }

    @Test
    void constructor_rejectsEmptySecretRef() {
        assertThrows(IllegalArgumentException.class,
                () -> new ActiveRefreshHandlerRef("", 1L, manager));
        verifyNoInteractions(manager);
    }

    @Test
    void constructor_rejectsNullManager() {
        assertThrows(IllegalArgumentException.class,
                () -> new ActiveRefreshHandlerRef("proj:secret", 1L, null));
    }
}
