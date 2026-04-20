package com.flipkart.grayskull.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DefaultHostIdentityProviderTest {

    @Test
    void getHostIdentification_returnsNonBlankValue() {
        DefaultHostIdentityProvider p = new DefaultHostIdentityProvider();
        String id = p.getHostIdentification();

        assertNotNull(id);
        assertFalse(id.isEmpty());
        // Either it's a real hostname, or the deterministic fallback sentinel.
        // In both cases the value MUST be trimmed (no surrounding whitespace).
        assertEquals(id.trim(), id);
    }

    @Test
    void getHostIdentification_isStableAcrossCalls() {
        DefaultHostIdentityProvider p = new DefaultHostIdentityProvider();
        assertEquals(p.getHostIdentification(), p.getHostIdentification());
    }

    @Test
    void fallbackSentinel_whenResolverThrows() {
        DefaultHostIdentityProvider p = new DefaultHostIdentityProvider(
                () -> { throw new java.net.UnknownHostException("forced"); });
        assertEquals("unknown-host", p.getHostIdentification());
    }

    @Test
    void fallbackSentinel_whenResolverReturnsNull() {
        DefaultHostIdentityProvider p = new DefaultHostIdentityProvider(() -> null);
        assertEquals("unknown-host", p.getHostIdentification());
    }

    @Test
    void fallbackSentinel_whenResolverReturnsBlank() {
        DefaultHostIdentityProvider p = new DefaultHostIdentityProvider(() -> "   ");
        assertEquals("unknown-host", p.getHostIdentification());
    }

    @Test
    void resolverValue_isTrimmed() {
        DefaultHostIdentityProvider p = new DefaultHostIdentityProvider(() -> "  my-host  ");
        assertEquals("my-host", p.getHostIdentification());
    }

    @Test
    void sanityCheck_defaultConstructorExercisesRealResolver() {
        // Ensures coverage of the no-arg constructor path which resolves via
        // InetAddress.getLocalHost() (or its fallback) in the real environment.
        DefaultHostIdentityProvider p = new DefaultHostIdentityProvider();
        assertTrue(p.getHostIdentification() != null && !p.getHostIdentification().isEmpty());
    }
}
