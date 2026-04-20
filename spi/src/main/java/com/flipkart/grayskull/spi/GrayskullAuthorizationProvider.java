package com.flipkart.grayskull.spi;

import com.flipkart.grayskull.spi.authn.GrayskullAuthentication;
import com.flipkart.grayskull.spi.authz.AuthorizationContext;

import java.util.List;

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

    /**
     * Checks if the authenticated user is authorized to perform a given action on multiple resources.
     * <p>
     * The default implementation delegates to the single-resource {@link #isAuthorized(AuthorizationContext, String)}
     * method in a fail-fast loop. Providers may override this method to perform more efficient bulk
     * authorization checks if the underlying system supports it.
     *
     * @param authorizationContexts A list of context objects, each containing information about a resource and principal.
     * @param action                The action being performed.
     * @return {@code true} if the user is authorized for all contexts, {@code false} on the first denial.
     */
    default boolean bulkAuthorize(List<AuthorizationContext> authorizationContexts, String action) {
        for (AuthorizationContext context : authorizationContexts) {
            if (!isAuthorized(context, action)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Checks if the authenticated user is authorized to perform a given action on a global resource.
     *
     * @param authentication The user's authentication object
     * @param action         The action being performed
     * @return {@code true} if the user is authorized, {@code false} otherwise.
     */
    boolean isAuthorized(GrayskullAuthentication authentication, String action);

}
