package com.flipkart.grayskull.workload;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.*;

class DefaultWorkloadIdentityResolverTest {

    @Test
    void testResolve_returnsLocalHostname() throws Exception {
        // Given
        String expectedHostname = InetAddress.getLocalHost().getHostName();

        // When
        DefaultWorkloadIdentityResolver resolver = new DefaultWorkloadIdentityResolver();

        // Then
        assertEquals(expectedHostname, resolver.resolve());
    }

    @Test
    void testResolve_usesSuppliedHostname() {
        // Given
        DefaultWorkloadIdentityResolver resolver = new DefaultWorkloadIdentityResolver(() -> "test-host-1");

        // When/Then
        assertEquals("test-host-1", resolver.resolve());
    }

    @Test
    void testResolve_returnsCachedValue() {
        // Given
        DefaultWorkloadIdentityResolver resolver = new DefaultWorkloadIdentityResolver(() -> "cached-host");

        // When
        String first = resolver.resolve();
        String second = resolver.resolve();

        // Then - the constructor-resolved value is cached; resolve() is a field read
        assertEquals("cached-host", first);
        assertSame(first, second);
    }

    @Test
    void testResolve_fallsBackToUnknown_whenSupplierThrowsUnknownHostException() {
        // Given
        DefaultWorkloadIdentityResolver resolver = new DefaultWorkloadIdentityResolver(
                () -> { throw new UnknownHostException("no dns"); });

        // When/Then
        assertEquals(DefaultWorkloadIdentityResolver.UNKNOWN_HOST, resolver.resolve());
    }

    @Test
    void testResolve_fallsBackToUnknown_whenSupplierThrowsRuntimeException() {
        // Given - any Exception subclass should be caught, not just checked types
        DefaultWorkloadIdentityResolver resolver = new DefaultWorkloadIdentityResolver(
                () -> { throw new SecurityException("blocked"); });

        // When/Then
        assertEquals(DefaultWorkloadIdentityResolver.UNKNOWN_HOST, resolver.resolve());
    }

    @Test
    void testResolve_fallsBackToUnknown_whenSupplierReturnsNull() {
        // Given
        DefaultWorkloadIdentityResolver resolver = new DefaultWorkloadIdentityResolver(() -> null);

        // When/Then
        assertEquals(DefaultWorkloadIdentityResolver.UNKNOWN_HOST, resolver.resolve());
    }

    @Test
    void testResolve_fallsBackToUnknown_whenSupplierReturnsEmpty() {
        // Given
        DefaultWorkloadIdentityResolver resolver = new DefaultWorkloadIdentityResolver(() -> "");

        // When/Then
        assertEquals(DefaultWorkloadIdentityResolver.UNKNOWN_HOST, resolver.resolve());
    }

    @Test
    void testResolve_fallsBackToUnknown_whenSupplierReturnsWhitespace() {
        // Given
        DefaultWorkloadIdentityResolver resolver = new DefaultWorkloadIdentityResolver(() -> "   ");

        // When/Then
        assertEquals(DefaultWorkloadIdentityResolver.UNKNOWN_HOST, resolver.resolve());
    }

    @Test
    void testResolve_neverReturnsNull() {
        // Given - even when supplier misbehaves, resolve() never returns null
        DefaultWorkloadIdentityResolver throwing = new DefaultWorkloadIdentityResolver(
                () -> { throw new RuntimeException("oops"); });
        DefaultWorkloadIdentityResolver nullSupplier = new DefaultWorkloadIdentityResolver(() -> null);

        // When/Then
        assertNotNull(throwing.resolve());
        assertNotNull(nullSupplier.resolve());
    }

    @Test
    void testImplementsWorkloadIdentityResolver() {
        // Given
        DefaultWorkloadIdentityResolver resolver = new DefaultWorkloadIdentityResolver(() -> "x");

        // When/Then - assignable to the public contract
        WorkloadIdentityResolver contract = resolver;
        assertEquals("x", contract.resolve());
    }

    @Test
    void testUnknownHostConstant_isStable() {
        // Guard-rail: the header value on lookup failure is part of the
        // public telemetry contract; a silent change would confuse operators.
        assertEquals("UNKNOWN", DefaultWorkloadIdentityResolver.UNKNOWN_HOST);
    }
}
