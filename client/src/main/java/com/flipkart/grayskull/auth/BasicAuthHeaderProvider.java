package com.flipkart.grayskull.auth;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Basic authentication header provider implementation.
 * <p>
 * This implementation provides HTTP Basic Authentication by encoding the username
 * and password in Base64 format according to RFC 7617.
 * </p>
 */
public final class BasicAuthHeaderProvider implements GrayskullAuthHeaderProvider {
    
    private final String username;
    private final String password;
    
    public BasicAuthHeaderProvider(String username, String password) {
        if (username == null || password == null) {
            throw new IllegalArgumentException("Username and password cannot be null");
        }
        this.username = username;
        this.password = password;
    }

    @Override
    public String getAuthHeader() {
        String credentials = username + ":" + password;
        String encodedCredentials = Base64.getEncoder()
            .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        return "Basic " + encodedCredentials;
    }
}
