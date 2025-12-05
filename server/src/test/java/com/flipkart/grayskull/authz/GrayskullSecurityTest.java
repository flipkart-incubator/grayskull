package com.flipkart.grayskull.authz;

import com.flipkart.grayskull.spi.GrayskullAuthorizationProvider;
import com.flipkart.grayskull.spi.authz.AuthorizationContext;
import com.flipkart.grayskull.spi.models.Project;
import com.flipkart.grayskull.spi.models.Secret;
import com.flipkart.grayskull.spi.repositories.ProjectRepository;
import com.flipkart.grayskull.spi.repositories.SecretRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GrayskullSecurityTest {

    private final ProjectRepository projectRepository = mock();
    private final SecretRepository secretRepository = mock();
    private final GrayskullAuthorizationProvider authorizationProvider = mock();
    private final GrayskullSecurity grayskullSecurity = new GrayskullSecurity(projectRepository, secretRepository, authorizationProvider);

    private final Authentication authentication = new TestingAuthenticationToken("test-user", "password");
    private final Project project = Project.builder()
            .id("test-project")
            .kmsKeyId("test-key")
            .build();
    private final Secret secret = Secret.builder()
            .name("test-secret")
            .projectId("test-project")
            .build();

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void hasPermission_ProjectLevel_WhenAuthorized_ReturnsTrue() {
        // Given
        SecurityContextHolder.getContext().setAuthentication(authentication);
        when(projectRepository.findByIdOrTransient("test-project")).thenReturn(project);
        when(authorizationProvider.isAuthorized(any(AuthorizationContext.class), eq("secrets.list"))).thenReturn(true);

        // When
        boolean result = grayskullSecurity.hasPermission("test-project", "secrets.list");

        // Then
        assertTrue(result);
    }

    @Test
    void hasPermission_ProjectLevel_WhenNotAuthorized_ReturnsFalse() {
        // Given
        SecurityContextHolder.getContext().setAuthentication(authentication);
        when(projectRepository.findByIdOrTransient("test-project")).thenReturn(project);
        when(authorizationProvider.isAuthorized(any(AuthorizationContext.class), eq("secrets.list"))).thenReturn(false);

        // When
        boolean result = grayskullSecurity.hasPermission("test-project", "secrets.list");

        // Then
        assertFalse(result);
    }

    @Test
    void hasPermission_SecretLevel_WhenProjectAndSecretExist_AndAuthorized_ReturnsTrue() {
        // Given
        SecurityContextHolder.getContext().setAuthentication(authentication);
        when(projectRepository.findById("test-project")).thenReturn(Optional.of(project));
        when(secretRepository.findByProjectIdAndName("test-project", "test-secret")).thenReturn(Optional.of(secret));
        when(authorizationProvider.isAuthorized(any(AuthorizationContext.class), eq("secret.read.value"))).thenReturn(true);

        // When
        boolean result = grayskullSecurity.hasPermission("test-project", "test-secret", "secret.read.value");

        // Then
        assertTrue(result);
    }

    @Test
    void hasPermission_SecretLevel_WhenProjectAndSecretExist_AndNotAuthorized_ReturnsFalse() {
        // Given
        SecurityContextHolder.getContext().setAuthentication(authentication);
        when(projectRepository.findById("test-project")).thenReturn(Optional.of(project));
        when(secretRepository.findByProjectIdAndName("test-project", "test-secret")).thenReturn(Optional.of(secret));
        when(authorizationProvider.isAuthorized(any(AuthorizationContext.class), eq("secret.read.value"))).thenReturn(false);

        // When
        boolean result = grayskullSecurity.hasPermission("test-project", "test-secret", "secret.read.value");

        // Then
        assertFalse(result);
    }

    @Test
    void hasPermission_SecretLevel_WhenProjectExistsButSecretDoesNot_FallsBackToProjectLevel() {
        // Given
        SecurityContextHolder.getContext().setAuthentication(authentication);
        when(projectRepository.findById("test-project")).thenReturn(Optional.of(project));
        when(secretRepository.findByProjectIdAndName("test-project", "non-existent-secret")).thenReturn(Optional.empty());
        when(authorizationProvider.isAuthorized(any(AuthorizationContext.class), eq("secret.create"))).thenReturn(true);

        // When
        boolean result = grayskullSecurity.hasPermission("test-project", "non-existent-secret", "secret.create");

        // Then
        assertTrue(result);
    }

    @Test
    void hasPermission_SecretLevel_WhenProjectDoesNotExist_ReturnsFalse() {
        // Given
        SecurityContextHolder.getContext().setAuthentication(authentication);
        when(projectRepository.findById("non-existent-project")).thenReturn(Optional.empty());

        // When
        boolean result = grayskullSecurity.hasPermission("non-existent-project", "test-secret", "secret.read.value");

        // Then
        assertFalse(result);
    }

    @Test
    void hasPermission_GlobalLevel_WhenAuthorized_ReturnsTrue() {
        // Given
        SecurityContextHolder.getContext().setAuthentication(authentication);
        when(authorizationProvider.isAuthorized(any(AuthorizationContext.class), eq("providers.create"))).thenReturn(true);

        // When
        boolean result = grayskullSecurity.hasPermission("providers.create");

        // Then
        assertTrue(result);
    }

    @Test
    void hasPermission_GlobalLevel_WhenNotAuthorized_ReturnsFalse() {
        // Given
        SecurityContextHolder.getContext().setAuthentication(authentication);
        when(authorizationProvider.isAuthorized(any(AuthorizationContext.class), eq("providers.create"))).thenReturn(false);

        // When
        boolean result = grayskullSecurity.hasPermission("providers.create");

        // Then
        assertFalse(result);
    }

    @Test
    void hasPermission_WhenNoAuthentication_StillCallsAuthorizationProvider() {
        // Given - No authentication set in SecurityContext
        when(authorizationProvider.isAuthorized(any(AuthorizationContext.class), eq("providers.list"))).thenReturn(false);

        // When
        boolean result = grayskullSecurity.hasPermission("providers.list");

        // Then
        assertFalse(result);
    }
}
