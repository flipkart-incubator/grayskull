package com.flipkart.grayskull.spi;

import com.flipkart.grayskull.spi.authn.GrayskullUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.AuthenticationManager;

/**
 * Interface for an authentication provider in the Grayskull security framework.
 * If there are multiple authentication providers, Grayskull will pick the bean with {@code @Primary} annotation
 */
public interface GrayskullAuthenticationProvider {

    default void initialize(AuthenticationManager authenticationManager) {
        // no-op; override if you need the manager
    }

    /**
     * Authenticate the request.
     * If returned object is null, the request will be continued and might fail later if the api is not open.
     * If an AuthenticationException is thrown, the request will be rejected right away.
     */
    GrayskullUser authenticate(HttpServletRequest request);
}
