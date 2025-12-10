package com.flipkart.grayskull.authn;

import com.flipkart.grayskull.spi.GrayskullAuthenticationProvider;
import com.flipkart.grayskull.spi.authn.GrayskullUser;
import com.flipkart.grayskull.spimpl.authn.SimpleGrayskullUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthenticationFilterTest {

    private final GrayskullAuthenticationProvider authenticationProvider = mock(GrayskullAuthenticationProvider.class);

    private final HttpServletRequest request = mock(HttpServletRequest.class);

    private final HttpServletResponse response = mock(HttpServletResponse.class);

    private final FilterChain filterChain = mock(FilterChain.class);

    private final AuthenticationFilter authenticationFilter = new AuthenticationFilter(authenticationProvider);

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void doFilterInternal_WhenAuthenticationSuccessful_ShouldSetSecurityContext() throws ServletException, IOException {
        // Arrange
        GrayskullUser user = new SimpleGrayskullUser("user", null);
        when(authenticationProvider.authenticate(request)).thenReturn(user);

        // Act
        authenticationFilter.doFilterInternal(request, response, filterChain);

        // Assert
        verify(filterChain).doFilter(request, response);
        assertEquals(user, SecurityContextHolder.getContext().getAuthentication().getPrincipal());
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
        AuthenticationException authException = new BadCredentialsException("Invalid credentials");
        when(authenticationProvider.authenticate(request)).thenThrow(authException);

        // Act
        authenticationFilter.doFilterInternal(request, response, filterChain);

        // Assert
        verify(response).sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid credentials");
        verify(filterChain, never()).doFilter(request, response);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }
} 