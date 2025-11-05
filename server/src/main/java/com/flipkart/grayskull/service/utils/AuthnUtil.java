package com.flipkart.grayskull.service.utils;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * A utility component to provide authentication-related information.
 */
@Component
public final class AuthnUtil {

    /**
     * Retrieves the username of the currently authenticated principal from the SecurityContext.
     *
     * @return The name of the current user.
     * @throws IllegalStateException if no authentication is found in the security context.
     */
    public String getCurrentUsername() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            throw new IllegalStateException("No authentication found in security context");
        }
        return authentication.getName();
    }
} 