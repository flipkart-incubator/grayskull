package com.flipkart.grayskull.spi;

import org.springframework.security.core.Authentication;

/**
 * Interface for an authorization provider in the Grayskull security framework.
 * This provider is responsible for checking if a user has the necessary permissions to perform an action on a resource.
 */
public interface GrayskullAuthorizationProvider {

    /**
     * Checks if the authenticated user is authorized to perform a given action on a specific project.
     *
     * @param authentication The authentication object representing the user.
     * @param projectId      The ID of the project being accessed.
     * @param action         The action being performed (e.g., from {@link com.flipkart.grayskull.models.authz.GrayskullActions}).
     * @return {@code true} if the user is authorized, {@code false} otherwise.
     */
    boolean isAuthorized(Authentication authentication, String projectId, String action);

} 