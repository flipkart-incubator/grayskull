package com.flipkart.grayskull.spimpl.authz;

import com.flipkart.grayskull.configuration.AuthorizationProperties;
import com.flipkart.grayskull.spi.GrayskullAuthorizationProvider;
import com.flipkart.grayskull.spi.authz.AuthorizationContext;
import lombok.RequiredArgsConstructor;

import org.springframework.security.access.AccessDeniedException;
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
    public void isAuthorized(AuthorizationContext authorizationContext, String action) {
        Authentication authentication = authorizationContext.getAuthentication();
        if (authentication == null) {
            throw new AccessDeniedException("User is not authenticated");
        }

        String username = authentication.getName();
        if (authorizationProperties.getRules() == null) {
            throw new AccessDeniedException("No authorization rules found");
        }

        boolean isAuthorized = authorizationProperties.getRules().stream()
                .filter(rule -> userMatches(rule, username))
                .filter(rule -> projectMatches(rule, authorizationContext.getProjectId().orElse(null)))
                .filter(rule -> secretMatches(rule, authorizationContext.getSecretName().orElse(null)))
                .anyMatch(rule -> actionMatches(rule, action));
        if (!isAuthorized) {
            throw new AccessDeniedException("User " + username + " is not authorized to perform action " + action);
        }
    }

    private boolean userMatches(AuthorizationProperties.Rule rule, String username) {
        return "*".equals(rule.getUser()) || rule.getUser().equals(username);
    }

    private boolean projectMatches(AuthorizationProperties.Rule rule, String projectId) {
        return "*".equals(rule.getProject()) || rule.getProject().equals(projectId);
    }

    private boolean secretMatches(AuthorizationProperties.Rule rule, String secretName) {
        // If the rule doesn't specify a secret, it's a project-level rule and should match any secret context.
        if (rule.getSecret() == null) {
            return true;
        }
        // If the rule specifies a secret, it must match the context's secret name or be a wildcard.
        return "*".equals(rule.getSecret()) || rule.getSecret().equals(secretName);
    }

    private boolean actionMatches(AuthorizationProperties.Rule rule, String action) {
        return Optional.ofNullable(rule.getActions())
            .map(actions -> actions.contains("*") || actions.contains(action))
            .orElse(false);
    }
} 