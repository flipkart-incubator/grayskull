package com.flipkart.security.grayskull.authn;

import com.flipkart.security.grayskull.spi.AuthenticationHandler;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.web.authentication.AuthenticationConverter;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.www.BasicAuthenticationConverter;
import org.springframework.stereotype.Component;

@Component
public class SimpleAuthenticationHandler implements AuthenticationHandler {

    private AuthenticationManager authenticationManager;
    private final AuthenticationConverter authenticationConverter = new BasicAuthenticationConverter();

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
