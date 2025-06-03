package com.flipkart.security.grayskull.filters;

import com.flipkart.security.grayskull.configuration.properties.ReadOnlyAppProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.io.IOException;

import static org.mockito.Mockito.*;

class ReadOnlyFilterTest {

    private final HttpServletRequest request = mock(HttpServletRequest.class);

    private final HttpServletResponse response = mock(HttpServletResponse.class);

    private final FilterChain filterChain = mock(FilterChain.class);

    @Test
    void whenReadOnlyDisabled_shouldAllowAllMethods() throws ServletException, IOException {
        // Given
        ReadOnlyAppProperties readOnlyAppProperties = new ReadOnlyAppProperties();
        readOnlyAppProperties.setEnabled(false);
        ReadOnlyFilter readOnlyFilter = new ReadOnlyFilter(readOnlyAppProperties);
        when(request.getMethod()).thenReturn(HttpMethod.POST.name());
        when(request.getServletPath()).thenReturn("/api/test");

        // When
        readOnlyFilter.doFilterInternal(request, response, filterChain);

        // Then
        verify(filterChain).doFilter(request, response);
        verify(response, never()).sendError(anyInt(), anyString());
    }

    @Test
    void whenReadOnlyEnabled_andBlockedMethod_shouldReturnMethodNotAllowed() throws ServletException, IOException {
        // Given
        ReadOnlyAppProperties readOnlyAppProperties = new ReadOnlyAppProperties();
        readOnlyAppProperties.setEnabled(true);
        ReadOnlyFilter readOnlyFilter = new ReadOnlyFilter(readOnlyAppProperties);
        when(request.getMethod()).thenReturn(HttpMethod.POST.name());
        when(request.getServletPath()).thenReturn("/api/test");

        // When
        readOnlyFilter.doFilterInternal(request, response, filterChain);

        // Then
        verify(response).sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED, 
            "The application server is read only. The specified operation is not allowed.");
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    void whenReadOnlyEnabled_andAllowedMethod_shouldAllowRequest() throws ServletException, IOException {
        // Given
        ReadOnlyAppProperties readOnlyAppProperties = new ReadOnlyAppProperties();
        readOnlyAppProperties.setEnabled(true);
        ReadOnlyFilter readOnlyFilter = new ReadOnlyFilter(readOnlyAppProperties);
        when(request.getMethod()).thenReturn(HttpMethod.GET.name());
        when(request.getServletPath()).thenReturn("/api/test");

        // When
        readOnlyFilter.doFilterInternal(request, response, filterChain);

        // Then
        verify(filterChain).doFilter(request, response);
        verify(response, never()).sendError(anyInt(), anyString());
    }

    @Test
    void whenReadOnlyEnabled_andExemptedPath_shouldAllowRequest() throws ServletException, IOException {
        // Given
        ReadOnlyAppProperties readOnlyAppProperties = new ReadOnlyAppProperties();
        readOnlyAppProperties.setEnabled(true);
        ReadOnlyFilter readOnlyFilter = new ReadOnlyFilter(readOnlyAppProperties);
        when(request.getMethod()).thenReturn(HttpMethod.POST.name());
        when(request.getServletPath()).thenReturn(""); // Empty path matches the exempted pattern

        // When
        readOnlyFilter.doFilterInternal(request, response, filterChain);

        // Then
        verify(filterChain).doFilter(request, response);
        verify(response, never()).sendError(anyInt(), anyString());
    }

    @Test
    void shouldBlockAllBlockedMethods() throws ServletException, IOException {
        // Given
        ReadOnlyAppProperties readOnlyAppProperties = new ReadOnlyAppProperties();
        readOnlyAppProperties.setEnabled(true);
        ReadOnlyFilter readOnlyFilter = new ReadOnlyFilter(readOnlyAppProperties);
        when(request.getServletPath()).thenReturn("/api/test");

        // Test all blocked methods
        for (String method : new String[]{"PUT", "POST", "PATCH", "DELETE"}) {
            when(request.getMethod()).thenReturn(method);

            // When
            readOnlyFilter.doFilterInternal(request, response, filterChain);

            // Then
            verify(response).sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED, 
                "The application server is read only. The specified operation is not allowed.");
            verify(filterChain, never()).doFilter(request, response);
            
            // Reset mocks for next iteration
            reset(response, filterChain);
        }
    }
} 