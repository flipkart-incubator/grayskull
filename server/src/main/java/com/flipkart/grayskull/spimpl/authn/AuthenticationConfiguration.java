package com.flipkart.grayskull.spimpl.authn;

import com.flipkart.grayskull.spi.GrayskullAuthenticationProvider;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;

import static io.swagger.v3.oas.models.security.SecurityScheme.In.HEADER;
import static io.swagger.v3.oas.models.security.SecurityScheme.Type.HTTP;

@Configuration
@ConditionalOnMissingBean(GrayskullAuthenticationProvider.class)
public class AuthenticationConfiguration {

    @Bean
    public GrayskullAuthenticationProvider authenticationProvider() {
        return new SimpleAuthenticationProvider();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new DummyPasswordEncoder();
    }

    @Bean
    public OpenApiCustomizer securityOpenApiCustomizer() {
        return openApi -> {
            openApi.addSecurityItem(new SecurityRequirement().addList("BasicAuth"));
            openApi.schemaRequirement("BasicAuth", new SecurityScheme().type(HTTP).in(HEADER).name("Authorization").scheme("basic"));
        };
    }

    /**
     * A default implementation of the UserDetailsService interface that returns true for any username and password combination
     * Spring's AuthenticationManager will use this UserDetailsService to authenticate users which will be used by the SimpleAuthenticationProvider.
     */
    @Bean
    public UserDetailsService userDetailsService() {
        return username -> User.withUsername(username)
                .roles("USER")
                .password("")
                .build();
    }

    /**
     * A password encoder which treats every password as valid password
     */
    public static class DummyPasswordEncoder implements PasswordEncoder {
        @Override
        public String encode(CharSequence rawPassword) {
            return rawPassword.toString();
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            return true;
        }
    }
}
