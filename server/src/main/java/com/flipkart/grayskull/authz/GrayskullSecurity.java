package com.flipkart.grayskull.authz;

import com.flipkart.grayskull.spi.authn.GrayskullUser;
import com.flipkart.grayskull.spi.models.Project;
import com.flipkart.grayskull.spi.GrayskullAuthorizationProvider;
import com.flipkart.grayskull.spi.authz.AuthorizationContext;
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
     *
     * @param projectId The ID of the project.
     * @param action    The action to authorize (e.g., "LIST_SECRETS",
     *                  "CREATE_SECRET").
     * @return {@code true} if authorized, {@code false} otherwise.
     */
    public boolean hasPermission(String projectId, String action) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Project project = projectRepository.findByIdOrTransient(projectId);

        AuthorizationContext context = AuthorizationContext.forProject(authentication, project);
        return authorizationProvider.isAuthorized(context, action);
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
     *
     * @param projectId  The ID of the project.
     * @param secretName The name of the secret.
     * @param action     The action to authorize (e.g., "READ_SECRET_VALUE").
     * @return {@code true} if authorized, {@code false} otherwise.
     */
    public boolean hasPermission(String projectId, String secretName, String action) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return projectRepository.findById(projectId)
                .map(project -> secretRepository.findByProjectIdAndName(project.getId(), secretName)
                        .map(secret -> {
                            // Secret exists, check with secret context
                            AuthorizationContext context = AuthorizationContext.forSecret(authentication, project,
                                    secret);
                            return authorizationProvider.isAuthorized(context, action);
                        })
                        .orElseGet(() -> {
                            // Secret does not exist, fall back to a project-level check.
                            AuthorizationContext context = AuthorizationContext.forProject(authentication, project);
                            return authorizationProvider.isAuthorized(context, action);
                        }))
                .orElse(false);
    }

    /**
     * Checks if the current user has permission to perform a global-level action
     * <br/>
     * This method is designed for actions that are not project or secret-specific,
     * such as creating other resources like secret providers.
     *
     * @param action     The action to authorize (e.g., "CREATE_PROVIDER").
     * @return {@code true} if authorized, {@code false} otherwise.
     */
    public boolean hasPermission(String action) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        AuthorizationContext context = AuthorizationContext.forGlobal(authentication);
        return authorizationProvider.isAuthorized(context, action);
    }

    /**
     * Checks for authorization with respect to user delegation. for 'SELF' provider, it does not check for actor name.
     * for other providers, it checks if the actor is the one registered with the provider.
     * @param providerName the secret provider name
     */
    public boolean checkProviderAuthorization(String providerName) {
        GrayskullUser user = (GrayskullUser) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        Optional<String> actorName = user.getActorName();
        if ("SELF".equals(providerName)) {
            return true;
        }
        String actor = actorName.orElseThrow(() -> new AccessDeniedException("Expected an actor name for the " + providerName + " managed secrets"));
        return secretProviderRepository.findByName(providerName)
                .map(secretProvider -> secretProvider.getPrincipal().equals(actor))
                .orElse(false);
    }
}