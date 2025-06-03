package com.flipkart.security.grayskull.configuration;

import com.flipkart.security.grayskull.configuration.properties.ReadOnlyAppProperties;
import com.flipkart.security.grayskull.filters.ReadOnlyFilter;
import com.flipkart.security.grayskull.spi.AuthenticationHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;

@Configuration
public class SecurityConfiguration {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           ReadOnlyAppProperties readOnlyAppProperties,
                                           AuthenticationHandler authenticationHandler) throws Exception {

        http
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .authorizeHttpRequests(authz -> authz
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/swagger-resources/**",
                                "/v3/api-docs/**", "/error", "/actuator/**").permitAll()
                        .anyRequest().authenticated()
                )
                .csrf(AbstractHttpConfigurer::disable);

        http.addFilterBefore(new ReadOnlyFilter(readOnlyAppProperties), SecurityContextHolderFilter.class);

        http.apply(new AuthenticationFilterSecurityConfigurer(authenticationHandler));


        return http.build();
    }
}
