package com.flipkart.grayskull.spi.authn;

import org.springframework.security.core.AuthenticatedPrincipal;

import java.util.Optional;

/**
 * User object which supports Identity Delegation required so that Secret Providers can call APIs on behalf of the user
 */
public interface GrayskullUser extends AuthenticatedPrincipal {
    /**
     * Returns the name of the user. If this is a delegated identity, this will be the name of the user on whose behalf the API is being called
     *
     * @return current username
     */
    @Override
    String getName();
    /**
     * Returns the name of the actor. i.e, the name of the user who is calling the API on behalf of some user
     *
     * @return actor's username
     */
    Optional<String> getActorName();
}
