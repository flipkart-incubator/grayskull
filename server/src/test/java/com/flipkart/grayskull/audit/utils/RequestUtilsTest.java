package com.flipkart.grayskull.audit.utils;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class RequestUtilsTest {

    @Test
    void getRemoteIPs_ShouldReturnAllAvailableIPs() {
        // Arrange
        HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("192.168.1.1");
        when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.195, 70.41.3.18, 150.172.238.178");
        when(request.getHeader("X-Real-IP")).thenReturn("203.0.113.195");
        when(request.getHeader("Forwarded")).thenReturn("for=198.51.100.45;proto=http;by=203.0.113.43");

        // Act
        Map<String, String> result = new RequestUtils(request).getRemoteIPs();

        // Assert
        assertNotNull(result);
        assertEquals(4, result.size());
        assertEquals("192.168.1.1", result.get("Remote-Conn-Addr"));
        assertEquals("203.0.113.195, 70.41.3.18, 150.172.238.178", result.get("X-Forwarded-For"));
        assertEquals("203.0.113.195", result.get("X-Real-IP"));
        assertEquals("for=198.51.100.45;proto=http;by=203.0.113.43", result.get("RFC7239 Forwarded"));
    }

    @Test
    void getRemoteIPs_ShouldHandleNullHeaders() {
        // Arrange
        HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("192.168.1.1");

        // Act
        Map<String, String> result = new RequestUtils(request).getRemoteIPs();

        // Assert
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("192.168.1.1", result.get("Remote-Conn-Addr"));
        assertNull(result.get("X-Forwarded-For"));
        assertNull(result.get("X-Real-IP"));
        assertNull(result.get("RFC7239 Forwarded"));
    }
}
