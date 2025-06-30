package com.flipkart.grayskull.spimpl.authz;

import com.flipkart.grayskull.configuration.AuthorizationProperties;
import com.flipkart.grayskull.spi.GrayskullAuthorizationProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * A simple implementation of the {@link GrayskullAuthorizationProvider} that uses a static set of rules
 * defined in the application's configuration file via {@link AuthorizationProperties}.
 * This implementation is intended for basic use cases and testing environments. It supports wildcard matching
 * for users, projects, and actions.
 */
@Component
@RequiredArgsConstructor
public class SimpleAuthorizationProvider implements GrayskullAuthorizationProvider {

    private final AuthorizationProperties authorizationProperties;

    @Override
    public boolean isAuthorized(Authentication authentication, String projectId, String action) {
        if (authentication == null) {
            return false;
        }

        String username = authentication.getName();
        if (authorizationProperties.getRules() == null) {
            return false;
        }

        return authorizationProperties.getRules().stream()
                .filter(rule -> userMatches(rule, username))
                .filter(rule -> projectMatches(rule, projectId))
                .anyMatch(rule -> actionMatches(rule, action));
    }

    private boolean userMatches(AuthorizationProperties.Rule rule, String username) {
        return "*".equals(rule.getUser()) || rule.getUser().equals(username);
    }

    private boolean projectMatches(AuthorizationProperties.Rule rule, String projectId) {
        return "*".equals(rule.getProject()) || rule.getProject().equals(projectId);
    }

    private boolean actionMatches(AuthorizationProperties.Rule rule, String action) {
        return Optional.ofNullable(rule.getActions())
                .map(actions -> actions.contains("*") || actions.contains(action))
                .orElse(false);
    }
} 