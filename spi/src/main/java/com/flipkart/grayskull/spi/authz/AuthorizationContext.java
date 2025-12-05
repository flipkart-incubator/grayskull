package com.flipkart.grayskull.spi.authz;

import com.flipkart.grayskull.spi.models.Project;
import com.flipkart.grayskull.spi.models.Secret;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;
import org.springframework.security.core.Authentication;

import java.util.Optional;

/**
 * An immutable container for all contextual information required to make an authorization decision.
 * It encapsulates the authenticated principal, the project being accessed, and optionally, the specific secret.
 */
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Getter
@ToString
public final class AuthorizationContext {

    /** The authenticated principal and their credentials. */
    private final Authentication authentication;

    /** The project scope for the authorization check. */
    private final Project project;

    /** The secret being accessed, which may not be present for project-level checks. */
    private final Secret secret;

    public static AuthorizationContext forGlobal(Authentication authentication) {
        return new AuthorizationContext(authentication, null, null);
    }

    /**
     * Creates an authorization context for a project-level action.
     * @param authentication The user's authentication object.
     * @param project The project resource.
     * @return A new {@link AuthorizationContext} instance.
     */
    public static AuthorizationContext forProject(Authentication authentication, Project project) {
        return new AuthorizationContext(authentication, project, null);
    }

    /**
     * Creates an authorization context for a secret-level action.
     * @param authentication The user's authentication object.
     * @param project The project resource.
     * @param secret The secret resource.
     * @return A new {@link AuthorizationContext} instance.
     */
    public static AuthorizationContext forSecret(Authentication authentication, Project project, Secret secret) {
        return new AuthorizationContext(authentication, project, secret);
    }

    /**
     * Gets the secret associated with this context, if present.
     * @return an {@link Optional} containing the secret, or empty if this is a project-level context.
     */
    public Optional<Secret> getSecret() {
        return Optional.ofNullable(secret);
    }

    /**
     * Gets the ID of the project in scope.
     * @return The project ID.
     */
    public Optional<String> getProjectId() {
        return Optional.ofNullable(project).map(Project::getId);
    }

    /**
     * Gets the name of the secret in scope, if present.
     * @return an {@link Optional} containing the secret name, or empty if no secret is associated.
     */
    public Optional<String> getSecretName() {
        return getSecret().map(Secret::getName);
    }
} 