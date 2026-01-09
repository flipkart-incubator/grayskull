package com.flipkart.grayskull.spi.authn;

import org.springframework.security.core.AuthenticatedPrincipal;

import java.io.Serializable;

/**
 * User object which supports Identity Delegation required so that Secret Providers can call APIs on behalf of the user
 *
 * @param name name of the user. If this is a delegated identity, this will be the name of the user on whose behalf the API is being called
 * @param actorName name of the actor. i.e, the name of the user who is calling the API on behalf of some user. this will be null if this is not a delegated identity
 */
public record GrayskullUser(String name, String actorName) implements AuthenticatedPrincipal, Serializable {

    /**
     * Returns the name of the user. If this is a delegated identity, this will be the name of the user on whose behalf the API is being called
     *
     * @return current username
     */
    @Override
    public String getName() {
        return name;
    }

}
