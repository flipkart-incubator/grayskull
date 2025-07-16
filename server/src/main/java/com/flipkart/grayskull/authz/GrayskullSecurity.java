package com.flipkart.grayskull.authz;

import com.flipkart.grayskull.models.db.Project;
import com.flipkart.grayskull.spi.GrayskullAuthorizationProvider;
import com.flipkart.grayskull.spi.authz.AuthorizationContext;
import com.flipkart.grayskull.spi.repositories.ProjectRepository;
import com.flipkart.grayskull.spi.repositories.SecretRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * A security facade bean that centralizes authorization logic for use in Spring Security's
 * method security expressions (e.g., {@code @PreAuthorize}).
 * <p>
 * This component acts as a bridge between the application's REST controllers and the underlying
 * {@link GrayskullAuthorizationProvider}, providing convenient methods to check permissions
 * against projects and secrets.
 */
@Component
@RequiredArgsConstructor
public class GrayskullSecurity {

    private final ProjectRepository projectRepository;
    private final SecretRepository secretRepository;
    private final GrayskullAuthorizationProvider authorizationProvider;

    /**
     * Checks if the current user has permission to perform a project-level action.
     * <p>
     * This method is designed for actions where a secret is not yet involved, such as listing secrets
     * within a project or creating a new one. If the project does not yet exist in the database,
     * this method creates a transient {@link Project} instance. This allows authorization rules
     * (e.g., wildcard rules for admins) to grant permission for creating resources in new projects.
     *
     * @param projectId The ID of the project.
     * @param action    The action to authorize (e.g., "LIST_SECRETS", "CREATE_SECRET").
     * @return {@code true} if authorized, {@code false} otherwise.
     */
    public boolean hasPermission(String projectId, String action) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Project project = projectRepository.findById(projectId)
                .orElse(new Project(projectId, null)); // Create a transient project if not found

        AuthorizationContext context = AuthorizationContext.forProject(authentication, project);
        return authorizationProvider.isAuthorized(context, action);
    }

    /**
     * Checks if the current user has permission to perform an action on a specific secret within a project.
     * <p>
     * This method is designed for secret-level operations like reading, updating, or deleting a secret.
     * It handles two key scenarios for non-existent resources:
     * <ul>
     *   <li>If the {@code project} does not exist, it returns {@code false}, denying permission.</li>
     *   <li>If the {@code project} exists but the {@code secret} does not, it performs a project-level
     *   permission check. This allows rules to grant permissions (e.g., for creation) even before the
     *   secret resource exists. The service layer is then responsible for returning the appropriate
     *   response (e.g., 404 Not Found).</li>
     * </ul>
     *
     * @param projectId      The ID of the project.
     * @param secretName     The name of the secret.
     * @param action         The action to authorize (e.g., "READ_SECRET_VALUE").
     * @return {@code true} if authorized, {@code false} otherwise.
     */
    public boolean hasPermission(String projectId, String secretName, String action) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return projectRepository.findById(projectId)
                .map(project -> secretRepository.findByProjectIdAndName(project.getId(), secretName)
                        .map(secret -> {
                            // Secret exists, check with secret context
                            AuthorizationContext context = AuthorizationContext.forSecret(authentication, project, secret);
                            return authorizationProvider.isAuthorized(context, action);
                        })
                        .orElseGet(() -> {
                            // Secret does not exist, fall back to a project-level check.
                            AuthorizationContext context = AuthorizationContext.forProject(authentication, project);
                            return authorizationProvider.isAuthorized(context, action);
                        }))
                .orElse(false);
    }
}