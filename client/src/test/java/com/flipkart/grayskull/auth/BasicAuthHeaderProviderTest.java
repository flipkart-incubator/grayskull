package com.flipkart.grayskull.auth;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class BasicAuthHeaderProviderTest {

    @Test
    void testGetAuthHeader_validCredentials() {
        // Given
        String username = "testuser";
        String password = "testpass";
        BasicAuthHeaderProvider provider = new BasicAuthHeaderProvider(username, password);

        // When
        String authHeader = provider.getAuthHeader();

        // Then
        assertNotNull(authHeader);
        assertTrue(authHeader.startsWith("Basic "));
        
        // Decode and verify
        String encodedPart = authHeader.substring("Basic ".length());
        String decoded = new String(Base64.getDecoder().decode(encodedPart), StandardCharsets.UTF_8);
        assertEquals("testuser:testpass", decoded);
    }

    @Test
    void testConstructor_nullUsername() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> new BasicAuthHeaderProvider(null, "password"));
    }

    @Test
    void testConstructor_nullPassword() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> new BasicAuthHeaderProvider("username", null));
    }

    @Test
    void testConstructor_bothNull() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> new BasicAuthHeaderProvider(null, null));
    }

    @Test
    void testGetAuthHeader_emptyCredentials() {
        // Given
        BasicAuthHeaderProvider provider = new BasicAuthHeaderProvider("", "");

        // When
        String authHeader = provider.getAuthHeader();

        // Then
        String encodedPart = authHeader.substring("Basic ".length());
        String decoded = new String(Base64.getDecoder().decode(encodedPart), StandardCharsets.UTF_8);
        assertEquals(":", decoded);
    }
}

