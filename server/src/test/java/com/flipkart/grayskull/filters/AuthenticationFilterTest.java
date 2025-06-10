package com.flipkart.grayskull.filters;

import com.flipkart.grayskull.spi.AuthenticationProvider;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthenticationFilterTest {

    private final AuthenticationProvider authenticationProvider = mock(AuthenticationProvider.class);

    private final AuthenticationEntryPoint authenticationEntryPoint = mock(AuthenticationEntryPoint.class);

    private final HttpServletRequest request = mock(HttpServletRequest.class);

    private final HttpServletResponse response = mock(HttpServletResponse.class);

    private final FilterChain filterChain = mock(FilterChain.class);

    private final AuthenticationFilter authenticationFilter = new AuthenticationFilter(authenticationProvider, authenticationEntryPoint);

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void doFilterInternal_WhenAuthenticationSuccessful_ShouldSetSecurityContext() throws ServletException, IOException {
        // Arrange
        Authentication authentication = new TestingAuthenticationToken("user", "password");
        when(authenticationProvider.authenticate(request)).thenReturn(authentication);

        // Act
        authenticationFilter.doFilterInternal(request, response, filterChain);

        // Assert
        verify(filterChain).doFilter(request, response);
        assertEquals(authentication, SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void doFilterInternal_WhenAuthenticationReturnsNull_ShouldContinueChain() throws ServletException, IOException {
        // Arrange
        when(authenticationProvider.authenticate(request)).thenReturn(null);

        // Act
        authenticationFilter.doFilterInternal(request, response, filterChain);

        // Assert
        verify(filterChain).doFilter(request, response);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void doFilterInternal_WhenAuthenticationFails_ShouldCallEntryPoint() throws ServletException, IOException {
        // Arrange
        AuthenticationException authException = mock(AuthenticationException.class);
        when(authenticationProvider.authenticate(request)).thenThrow(authException);

        // Act
        authenticationFilter.doFilterInternal(request, response, filterChain);

        // Assert
        verify(authenticationEntryPoint).commence(request, response, authException);
        verify(filterChain, never()).doFilter(request, response);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }
} 