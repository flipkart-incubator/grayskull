package com.flipkart.grayskull.authn;

import com.flipkart.grayskull.spi.GrayskullAuthenticationProvider;
import lombok.AllArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.SecurityConfigurer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;

@AllArgsConstructor
public class AuthenticationFilterSecurityConfigurer implements SecurityConfigurer<DefaultSecurityFilterChain, HttpSecurity> {

    private final GrayskullAuthenticationProvider authenticationProvider;

    @Override
    public void init(HttpSecurity builder) {
        // Initialization not required for this configurer
    }

    @Override
    public void configure(HttpSecurity http) {
        AuthenticationManager authenticationManager = http.getSharedObject(AuthenticationManager.class);
        authenticationProvider.initialize(authenticationManager);
        http.addFilterAfter(new AuthenticationFilter(authenticationProvider), SecurityContextHolderFilter.class);
    }
}
