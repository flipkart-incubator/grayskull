package com.flipkart.grayskull.auth;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.Assert.*;

public class BasicAuthHeaderProviderTest {

    @Test
    public void testGetAuthHeader_validCredentials() {
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

    @Test(expected = IllegalArgumentException.class)
    public void testConstructor_nullUsername() {
        // When/Then
        new BasicAuthHeaderProvider(null, "password");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructor_nullPassword() {
        // When/Then
        new BasicAuthHeaderProvider("username", null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructor_bothNull() {
        // When/Then
        new BasicAuthHeaderProvider(null, null);
    }

    @Test
    public void testGetAuthHeader_emptyCredentials() {
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

