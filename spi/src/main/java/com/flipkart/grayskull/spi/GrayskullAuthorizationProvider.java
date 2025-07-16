package com.flipkart.grayskull.spi;

import com.flipkart.grayskull.spi.authz.AuthorizationContext;

/**
 * Interface for an authorization provider in the Grayskull security framework.
 * This provider is responsible for checking if a user has the necessary permissions to perform an action on a resource.
 */
public interface GrayskullAuthorizationProvider {

    /**
     * Checks if the authenticated user is authorized to perform a given action on a specific resource.
     * The resource and security context is provided via the {@link AuthorizationContext}.
     *
     * @param authorizationContext The context object containing information about the resource and principal.
     * @param action               The action being performed (e.g., from {@link com.flipkart.grayskull.models.authz.GrayskullActions}).
     * @return {@code true} if the user is authorized, {@code false} otherwise.
     */
    boolean isAuthorized(AuthorizationContext authorizationContext, String action);

} 