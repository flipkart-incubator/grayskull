package com.flipkart.grayskull.configuration;

import com.flipkart.grayskull.authz.GrayskullPermissionEvaluator;
import com.flipkart.grayskull.spi.GrayskullAuthenticationProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, GrayskullAuthenticationProvider authenticationProvider) throws Exception {

        http
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authz -> authz
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/swagger-resources/**", "/v3/api-docs/**",
                                "/error", "/actuator/**").permitAll()
                        .anyRequest().authenticated())
                .csrf(AbstractHttpConfigurer::disable);

        http.apply(new AuthenticationFilterSecurityConfigurer(authenticationProvider));

        return http.build();
    }

    /**
     * Creates a {@link MethodSecurityExpressionHandler} that registers the custom {@link GrayskullPermissionEvaluator}.
     * This is necessary to wire up the {@code hasPermission()} expressions in {@code @PreAuthorize} annotations
     * to our custom permission evaluation logic.
     *
     * @param permissionEvaluator The custom permission evaluator to be used.
     * @return A configured {@link MethodSecurityExpressionHandler}.
     */
    @Bean
    public MethodSecurityExpressionHandler createExpressionHandler(GrayskullPermissionEvaluator permissionEvaluator) {
        var expressionHandler = new DefaultMethodSecurityExpressionHandler();
        expressionHandler.setPermissionEvaluator(permissionEvaluator);
        return expressionHandler;
    }
}
