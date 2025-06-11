package com.flipkart.grayskull.spimpl.authn;

import com.flipkart.grayskull.spi.AuthenticationProvider;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.web.authentication.AuthenticationConverter;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.www.BasicAuthenticationConverter;

/**
 * A simple implementation of the AuthenticationProvider interface that uses Basic Authentication.
 * The actual authentication is done by the AuthenticationManager so that spring's UserDetailsService can be used for authentication.
 */
public class SimpleAuthenticationProvider implements AuthenticationProvider {

    private final AuthenticationConverter authenticationConverter = new BasicAuthenticationConverter();
    private AuthenticationManager authenticationManager;

    @Override
    public void initialize(AuthenticationManager authenticationManager) {
        this.authenticationManager = authenticationManager;
    }

    @Override
    public Authentication authenticate(HttpServletRequest request) {
        Authentication authRequest = authenticationConverter.convert(request);
        if (authRequest == null) {
            return null;
        }
        return authenticationManager.authenticate(authRequest);
    }
}
