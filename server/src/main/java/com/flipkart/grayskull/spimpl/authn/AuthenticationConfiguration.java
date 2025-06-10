package com.flipkart.grayskull.spimpl.authn;

import com.flipkart.grayskull.spi.AuthenticationProvider;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

import static io.swagger.v3.oas.models.security.SecurityScheme.In.HEADER;
import static io.swagger.v3.oas.models.security.SecurityScheme.Type.HTTP;

@Configuration
@ConditionalOnMissingBean(AuthenticationProvider.class)
public class AuthenticationConfiguration {

    @Bean
    public AuthenticationProvider authenticationProvider() {
        return new SimpleAuthenticationProvider();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    @Bean
    public OpenApiCustomizer securityOpenApiCustomizer() {
        return openApi -> {
            openApi.addSecurityItem(new SecurityRequirement().addList("BasicAuth"));
            openApi.schemaRequirement("BasicAuth", new SecurityScheme().type(HTTP).in(HEADER).name("Authorization").scheme("basic"));
        };
    }

    /**
     * A default implementation of the UserDetailsService interface that uses InMemoryUserDetailsManager.
     * Spring's AuthenticationManager will use this UserDetailsService to authenticate users which will be used by the SimpleAuthenticationProvider.
     */
    @Bean
    public UserDetailsService userDetailsService() {
        UserDetails user = User.withUsername("user1")
                // password: "password"
                .password("$argon2id$v=19$m=16384,t=2,p=1$XgbDosOMGMKqwpEzc8OXwq3ChsKZdsO0w4o$QER2I+HnPAxaX8SOt9/WW3ZvAqsMXwK7X8+CHYkkM9A")
                .roles("USER")
                .build();
        UserDetails admin = User.withUsername("user2")
                .password("$argon2id$v=19$m=16384,t=2,p=1$XgbDosOMGMKqwpEzc8OXwq3ChsKZdsO0w4o$QER2I+HnPAxaX8SOt9/WW3ZvAqsMXwK7X8+CHYkkM9A")
                .roles("USER")
                .build();
        return new InMemoryUserDetailsManager(user, admin);
    }
}
