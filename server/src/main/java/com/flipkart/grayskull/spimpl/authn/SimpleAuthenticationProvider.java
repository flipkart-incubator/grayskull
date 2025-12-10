package com.flipkart.grayskull.spimpl.authn;

import com.flipkart.grayskull.spi.GrayskullAuthenticationProvider;
import com.flipkart.grayskull.spi.authn.GrayskullUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.web.authentication.AuthenticationConverter;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.www.BasicAuthenticationConverter;

/**
 * A simple implementation of the AuthenticationProvider interface that uses Basic Authentication.
 * The actual authentication is done by the AuthenticationManager so that spring's UserDetailsService can be used for authentication.
 */
public class SimpleAuthenticationProvider implements GrayskullAuthenticationProvider {

    private static final String PROXY_HEADER = "x-proxy-user";

    private final AuthenticationConverter authenticationConverter = new BasicAuthenticationConverter();
    private AuthenticationManager authenticationManager;

    @Override
    public void initialize(AuthenticationManager authenticationManager) {
        this.authenticationManager = authenticationManager;
    }

    @Override
    public GrayskullUser authenticate(HttpServletRequest request) {
        Authentication authRequest = authenticationConverter.convert(request);
        if (authRequest == null) {
            return null;
        }
        Authentication authenticate = authenticationManager.authenticate(authRequest);
        String name = authenticate.getName();
        String actor = null;
        if (request.getHeader(PROXY_HEADER) != null) {
            actor = name;
            name = request.getHeader(PROXY_HEADER);
        }
        return new SimpleGrayskullUser(name, actor);
    }
}
