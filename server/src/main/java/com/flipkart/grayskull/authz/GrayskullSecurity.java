package com.flipkart.grayskull.authz;

import com.flipkart.grayskull.spi.authn.GrayskullUser;
import com.flipkart.grayskull.spi.models.Project;
import com.flipkart.grayskull.spi.GrayskullAuthorizationProvider;
import com.flipkart.grayskull.spi.authz.AuthorizationContext;
import com.flipkart.grayskull.spi.models.Secret;
import com.flipkart.grayskull.spi.models.SecretProvider;
import com.flipkart.grayskull.spi.repositories.ProjectRepository;
import com.flipkart.grayskull.spi.repositories.SecretProviderRepository;
import com.flipkart.grayskull.spi.repositories.SecretRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * A security facade bean that centralizes authorization logic for use in Spring
 * Security's
 * method security expressions (e.g., {@code @PreAuthorize}).
 * <p>
 * This component acts as a bridge between the application's REST controllers
 * and the underlying
 * {@link GrayskullAuthorizationProvider}, providing convenient methods to check
 * permissions
 * against projects and secrets.
 */
@Component
@RequiredArgsConstructor
public class GrayskullSecurity {

    private final ProjectRepository projectRepository;
    private final SecretRepository secretRepository;
    private final SecretProviderRepository secretProviderRepository;
    private final GrayskullAuthorizationProvider authorizationProvider;

    /**
     * Checks if the current user has permission to perform a project-level action.
     * <p>
     * This method is designed for actions where a secret is not yet involved, such
     * as listing secrets
     * within a project or creating a new one. The project resolution logic
     * (including transient project creation for non-existent projects) is
     * delegated to the repository layer, keeping authorization logic clean
     * and focused on permission evaluation.
     * <p>
     * <b>Note:</b> This method does not check for empty actor. Use
     * {@link #ensureEmptyActor()} to check for empty actor. or use {@link #checkProviderAuthorization(String)}
     * to check for provider authorization.
     *
     * @param projectId The ID of the project.
     * @param action    The action to authorize (e.g., "LIST_SECRETS",
     *                  "CREATE_SECRET").
     */
    public boolean hasPermission(String projectId, String action) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Project project = projectRepository.findByIdOrTransient(projectId);

        AuthorizationContext context = AuthorizationContext.forProject(authentication, project);
        authorizationProvider.isAuthorized(context, action);
        return true;
    }

    /**
     * Checks if the current user has permission to perform an action on a specific
     * secret within a project.
     * <p>
     * This method is designed for secret-level operations like reading, updating,
     * or deleting a secret.
     * It handles two key scenarios for non-existent resources:
     * <ul>
     * <li>If the {@code project} does not exist, it returns {@code false}, denying
     * permission.</li>
     * <li>If the {@code project} exists but the {@code secret} does not, it
     * performs a project-level
     * permission check. This allows rules to grant permissions (e.g., for creation)
     * even before the
     * secret resource exists. The service layer is then responsible for returning
     * the appropriate
     * response (e.g., 404 Not Found).</li>
     * </ul>
     * <p>
     * <b>Note:</b> This method checks that the actor is empty.
     * @param projectId  The ID of the project.
     * @param secretName The name of the secret.
     * @param action     The action to authorize (e.g., "READ_SECRET_VALUE").
     */
    public boolean hasPermission(String projectId, String secretName, String action) {
        ensureEmptyActor();
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new AccessDeniedException("Project not found"));
        Secret secret = secretRepository.findByProjectIdAndName(project.getId(), secretName).orElse(null);
        AuthorizationContext context = AuthorizationContext.forSecret(authentication, project, secret);
        authorizationProvider.isAuthorized(context, action);
        return true;
    }

    /**
     * Checks if the current user has permission to perform a global-level action
     * <br/>
     * This method is designed for actions that are not project or secret-specific,
     * such as creating other resources like secret providers.
     * <p>
     * <b>Note:</b> This method checks that the actor is empty.
     * @param action     The action to authorize (e.g., "CREATE_PROVIDER").
     */
    public boolean hasPermission(String action) {
        ensureEmptyActor();
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        AuthorizationContext context = AuthorizationContext.forGlobal(authentication);
        authorizationProvider.isAuthorized(context, action);
        return true;
    }

    private Optional<String> actorName() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        GrayskullUser user = (GrayskullUser) authentication.getPrincipal();
        return user.getActorName();
    }

    public boolean ensureEmptyActor() {
        Optional<String> actorName = actorName();
        if (actorName.isPresent()) {
            throw new AccessDeniedException("User delegation is not expected for this action");
        }
        return true;
    }

    /**
     * Checks for authorizaion with respect to user delegation. for 'SELF' provider, it checks if the actor is empty.
     * for other providers, it checks if the actor is the one registered with the provider.
     * @param providerName the secret provider name
     */
    public boolean checkProviderAuthorization(String providerName) {
        checkProviderAuthorizationInternal(providerName);
        return true;
    }

    public void checkProviderAuthorizationInternal(String providerName) {
        Optional<String> actorName = actorName();
        if ("SELF".equals(providerName)) {
            actorName.ifPresent(actor -> {
                throw new AccessDeniedException("User delegation is not supported for 'SELF' managed secrets");
            });
            return;
        }

        String actor = actorName.orElseThrow(() -> new AccessDeniedException("Expected an actor name for the " + providerName + " managed secrets"));
        SecretProvider provider = secretProviderRepository.findByName(providerName).orElseThrow(() -> new AccessDeniedException("Secret provider not found"));
        if (!provider.getPrincipal().equals(actor)) {
            throw new AccessDeniedException("Actor is not authorized to access the secrets of this provider");
        }
    }
}