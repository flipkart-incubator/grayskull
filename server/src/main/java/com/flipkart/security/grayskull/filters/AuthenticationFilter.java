package com.flipkart.security.grayskull.filters;

import com.flipkart.security.grayskull.spi.AuthenticationHandler;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.AllArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@AllArgsConstructor
public class AuthenticationFilter extends OncePerRequestFilter {

    private final AuthenticationHandler authenticationHandler;
    private final AuthenticationEntryPoint authenticationEntryPoint;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        try {
            Authentication authentication = authenticationHandler.authenticate(request);
            if (authentication != null) {
                SecurityContext context = SecurityContextHolder.getContextHolderStrategy().createEmptyContext();
                context.setAuthentication(authentication);
                SecurityContextHolder.setContext(context);
            }
            filterChain.doFilter(request, response);
        } catch (AuthenticationException e) {
            SecurityContextHolder.clearContext();
            logger.debug("Failed to process authentication request", e);
            this.authenticationEntryPoint.commence(request, response, e);
        }
    }
}
