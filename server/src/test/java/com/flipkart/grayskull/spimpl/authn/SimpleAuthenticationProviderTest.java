package com.flipkart.grayskull.spimpl.authn;

import com.flipkart.grayskull.spi.authn.GrayskullUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SimpleAuthenticationProviderTest {

    private final AuthenticationManager authenticationManager = mock();

    private final SimpleAuthenticationProvider authenticationProvider = new SimpleAuthenticationProvider();

    @BeforeEach
    void setUp() {
        authenticationProvider.initialize(authenticationManager);
    }

    @Test
    void authenticate_WithValidBasicAuth_ReturnsGrayskullUser() {
        // Given
        MockHttpServletRequest request = new MockHttpServletRequest();
        String credentials = Base64.getEncoder().encodeToString("testuser:testpass".getBytes());
        request.addHeader("Authorization", "Basic " + credentials);

        Authentication mockAuth = new UsernamePasswordAuthenticationToken("testuser", "testpass");
        when(authenticationManager.authenticate(any(Authentication.class))).thenReturn(mockAuth);

        // When
        GrayskullUser result = authenticationProvider.authenticate(request);

        // Then
        assertNotNull(result);
        assertEquals("testuser", result.getName());
        assertTrue(result.getActorName().isEmpty());
        verify(authenticationManager).authenticate(any(Authentication.class));
    }

    @Test
    void authenticate_WithValidBasicAuthAndProxyHeader_ReturnsGrayskullUserWithActor() {
        // Given
        MockHttpServletRequest request = new MockHttpServletRequest();
        String credentials = Base64.getEncoder().encodeToString("serviceaccount:servicepass".getBytes());
        request.addHeader("Authorization", "Basic " + credentials);
        request.addHeader("x-proxy-user", "actualuser");

        Authentication mockAuth = new UsernamePasswordAuthenticationToken("serviceaccount", "servicepass");
        when(authenticationManager.authenticate(any(Authentication.class))).thenReturn(mockAuth);

        // When
        GrayskullUser result = authenticationProvider.authenticate(request);

        // Then
        assertNotNull(result);
        assertEquals("actualuser", result.getName());
        assertTrue(result.getActorName().isPresent());
        assertEquals("serviceaccount", result.getActorName().get());
        verify(authenticationManager).authenticate(any(Authentication.class));
    }

    @Test
    void authenticate_WithNoAuthorizationHeader_ReturnsNull() {
        // Given
        MockHttpServletRequest request = new MockHttpServletRequest();
        // No Authorization header

        // When
        GrayskullUser result = authenticationProvider.authenticate(request);

        // Then
        assertNull(result);
        verify(authenticationManager, never()).authenticate(any(Authentication.class));
    }

    @Test
    void authenticate_WithInvalidAuthorizationHeader_ReturnsNull() {
        // Given
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer token123"); // Not Basic auth

        // When
        GrayskullUser result = authenticationProvider.authenticate(request);

        // Then
        assertNull(result);
        verify(authenticationManager, never()).authenticate(any(Authentication.class));
    }


}
