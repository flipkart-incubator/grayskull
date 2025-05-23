package com.flipkart.security.grayskull.configuration;

import com.flipkart.security.grayskull.filters.AuthenticationFilter;
import com.flipkart.security.grayskull.spi.AuthenticationHandler;
import lombok.AllArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.SecurityConfigurer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.authentication.Http403ForbiddenEntryPoint;
import org.springframework.security.web.context.SecurityContextHolderFilter;

@AllArgsConstructor
public class AuthenticationFilterSecurityConfigurer implements SecurityConfigurer<DefaultSecurityFilterChain, HttpSecurity> {

    private final AuthenticationHandler authenticationHandler;

    @Override
    public void init(HttpSecurity builder) {
        // Initialization not required for this configurer
    }

    @Override
    public void configure(HttpSecurity http) {
        AuthenticationManager authenticationManager = http.getSharedObject(AuthenticationManager.class);
        authenticationHandler.initialize(authenticationManager);
        http.addFilterAfter(new AuthenticationFilter(authenticationHandler, new Http403ForbiddenEntryPoint()), SecurityContextHolderFilter.class);
    }
}
