package com.flipkart.grayskull.authz;

import com.flipkart.grayskull.models.dto.request.SecretVersionEntry;
import com.flipkart.grayskull.spi.authn.GrayskullAuthentication;
import com.flipkart.grayskull.spi.models.Project;
import com.flipkart.grayskull.spi.models.Secret;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.flipkart.grayskull.service.utils.SecretProviderConstants.PROVIDER_SELF;

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
        GrayskullAuthentication authentication = (GrayskullAuthentication) SecurityContextHolder.getContext().getAuthentication();
        return authorizationProvider.isAuthorized(authentication, action);
    }

    /**
     * Batch authorization for a list of (projectId, secretName) pairs.
     * Uses two bulk MongoDB lookups (projects + secrets) instead of per-entry queries.
     * Only checks authz for secrets that exist; missing secrets are skipped
     * (the service layer will exclude them from the response).
     *
     * @param entries List of entries each containing a projectId and secretName.
     * @param action  The action to authorize.
     * @return {@code true} if authorized for all found entries, {@code false} if any check fails.
     */
    public boolean hasPermissionForSecrets(List<SecretVersionEntry> entries, String action) {
        if (entries.isEmpty()) {
            return true;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        Set<String> projectIds = entries.stream()
                .map(SecretVersionEntry::getProjectId)
                .collect(Collectors.toSet());
        Map<String, Project> projectMap = new HashMap<>();
        projectRepository.findAllById(projectIds).forEach(p -> projectMap.put(p.getId(), p));

        Map<String, List<String>> projectToNames = entries.stream()
                .collect(Collectors.groupingBy(
                        SecretVersionEntry::getProjectId,
                        Collectors.mapping(SecretVersionEntry::getSecretName, Collectors.toList())));
        Map<String, Secret> secretMap = new HashMap<>();
        secretRepository.findActiveByProjectAndNames(projectToNames)
                .forEach(s -> secretMap.put(s.getProjectId() + ":" + s.getName(), s));

        for (SecretVersionEntry entry : entries) {
            Secret secret = secretMap.get(entry.getProjectId() + ":" + entry.getSecretName());
            if (secret == null) {
                continue;
            }
            Project project = projectMap.get(entry.getProjectId());
            if (project == null) {
                return false;
            }
            AuthorizationContext context = AuthorizationContext.forSecret(authentication, project, secret);
            if (!authorizationProvider.isAuthorized(context, action)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Checks for authorization with respect to user delegation. for 'SELF' provider, it does not check for actor name.
     * for other providers, it checks if the actor is the one registered with the provider.
     * @param providerName the secret provider name
     */
    public boolean checkProviderAuthorization(String providerName) {
        if (PROVIDER_SELF.equals(providerName)) {
            return true;
        }
        GrayskullAuthentication authentication = (GrayskullAuthentication) SecurityContextHolder.getContext().getAuthentication();
        String actorName = authentication.getActor();
        if (actorName == null) {
            throw new AccessDeniedException("Expected an actor name for the " + providerName + " managed secrets");
        }
        return secretProviderRepository.findByName(providerName)
                .filter(secretProvider -> secretProvider.getPrincipal().equals(actorName))
                .isPresent();
    }
}