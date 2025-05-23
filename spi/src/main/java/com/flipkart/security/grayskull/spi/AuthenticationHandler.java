package com.flipkart.security.grayskull.spi;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;

public interface AuthenticationHandler {

    void initialize(AuthenticationManager authenticationManager);

    Authentication authenticate(HttpServletRequest request);
}
