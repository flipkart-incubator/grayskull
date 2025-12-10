package com.flipkart.grayskull.authz;

import com.flipkart.grayskull.spi.GrayskullAuthorizationProvider;
import com.flipkart.grayskull.spi.authz.AuthorizationContext;
import com.flipkart.grayskull.spi.models.Project;
import com.flipkart.grayskull.spi.models.Secret;
import com.flipkart.grayskull.spi.models.SecretProvider;
import com.flipkart.grayskull.spi.repositories.ProjectRepository;
import com.flipkart.grayskull.spi.repositories.SecretProviderRepository;
import com.flipkart.grayskull.spi.repositories.SecretRepository;
import com.flipkart.grayskull.spimpl.authn.SimpleGrayskullUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class GrayskullSecurityTest {

    private final ProjectRepository projectRepository = mock();
    private final SecretRepository secretRepository = mock();
    private final SecretProviderRepository secretProviderRepository = mock();
    private final GrayskullAuthorizationProvider authorizationProvider = mock();
    private final GrayskullSecurity grayskullSecurity = new GrayskullSecurity(projectRepository, secretRepository, secretProviderRepository, authorizationProvider);

    private final Authentication authentication = new TestingAuthenticationToken(new SimpleGrayskullUser("test-user", null), "password");
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

        // When
        assertDoesNotThrow(() -> grayskullSecurity.hasPermission("test-project", "secrets.list"));
}

    @Test
    void hasPermission_ProjectLevel_WhenNotAuthorized_ReturnsFalse() {
        // Given
        SecurityContextHolder.getContext().setAuthentication(authentication);
        when(projectRepository.findByIdOrTransient("test-project")).thenReturn(project);
        doThrow(new AccessDeniedException("User is not authorized")).when(authorizationProvider).isAuthorized(any(AuthorizationContext.class), eq("secrets.list"));

        // When
        assertThrows(AccessDeniedException.class, () -> grayskullSecurity.hasPermission("test-project", "secrets.list"));
    }

    @Test
    void hasPermission_SecretLevel_WhenProjectAndSecretExist_AndAuthorized_ReturnsTrue() {
        // Given
        SecurityContextHolder.getContext().setAuthentication(authentication);
        when(projectRepository.findById("test-project")).thenReturn(Optional.of(project));
        when(secretRepository.findByProjectIdAndName("test-project", "test-secret")).thenReturn(Optional.of(secret));

        // When
        assertDoesNotThrow(() -> grayskullSecurity.hasPermission("test-project", "test-secret", "secret.read.value"));

    }

    @Test
    void hasPermission_SecretLevel_WhenProjectAndSecretExist_AndNotAuthorized_ReturnsFalse() {
        // Given
        SecurityContextHolder.getContext().setAuthentication(authentication);
        when(projectRepository.findById("test-project")).thenReturn(Optional.of(project));
        when(secretRepository.findByProjectIdAndName("test-project", "test-secret")).thenReturn(Optional.of(secret));
        doThrow(new AccessDeniedException("User is not authorized")).when(authorizationProvider).isAuthorized(any(AuthorizationContext.class), eq("secret.read.value"));

        // When
        assertThrows(AccessDeniedException.class, () -> grayskullSecurity.hasPermission("test-project", "test-secret", "secret.read.value"));
    }

    @Test
    void hasPermission_SecretLevel_WhenProjectExistsButSecretDoesNot_FallsBackToProjectLevel() {
        // Given
        SecurityContextHolder.getContext().setAuthentication(authentication);
        when(projectRepository.findById("test-project")).thenReturn(Optional.of(project));
        when(secretRepository.findByProjectIdAndName("test-project", "non-existent-secret")).thenReturn(Optional.empty());

        // When
        assertDoesNotThrow(() -> grayskullSecurity.hasPermission("test-project", "non-existent-secret", "secret.create"));
    }

    @Test
    void hasPermission_SecretLevel_WhenProjectDoesNotExist_ReturnsFalse() {
        // Given
        SecurityContextHolder.getContext().setAuthentication(authentication);
        when(projectRepository.findById("non-existent-project")).thenReturn(Optional.empty());

        // When
        assertThrows(AccessDeniedException.class, () -> grayskullSecurity.hasPermission("non-existent-project", "test-secret", "secret.read.value"));
    }

    @Test
    void hasPermission_GlobalLevel_WhenAuthorized_ReturnsTrue() {
        // Given
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // When
        assertDoesNotThrow(() -> grayskullSecurity.hasPermission("providers.create"));
    }

    @Test
    void hasPermission_GlobalLevel_WhenNotAuthorized_ReturnsFalse() {
        // Given
        SecurityContextHolder.getContext().setAuthentication(authentication);
        doThrow(new AccessDeniedException("User is not authorized")).when(authorizationProvider).isAuthorized(any(AuthorizationContext.class), eq("providers.create"));

        // When
        assertThrows(AccessDeniedException.class, () -> grayskullSecurity.hasPermission("providers.create"));
    }

    // Tests for ensureEmptyActor method
    @Test
    void ensureEmptyActor_WhenUserHasNoActorName_ReturnsTrue() {
        // Given - User with no actor name
        Authentication authWithoutActor = new TestingAuthenticationToken(new SimpleGrayskullUser("test-user", null), "password");
        SecurityContextHolder.getContext().setAuthentication(authWithoutActor);

        // When & Then
        assertTrue(() -> grayskullSecurity.ensureEmptyActor());
    }

    @Test
    void ensureEmptyActor_WhenUserHasActorName_ThrowsAccessDeniedException() {
        // Given - User with actor name
        Authentication authWithActor = new TestingAuthenticationToken(new SimpleGrayskullUser("test-user", "actor-name"), "password");
        SecurityContextHolder.getContext().setAuthentication(authWithActor);

        // When & Then
        AccessDeniedException exception = assertThrows(AccessDeniedException.class, () -> grayskullSecurity.ensureEmptyActor());
        assertEquals("User delegation is not expected for this action", exception.getMessage());
    }

    // Tests for checkProviderAuthorization method
    @Test
    void checkProviderAuthorization_WhenProviderIsSelfAndUserHasNoActor_ReturnsTrue() {
        // Given - User with no actor name
        Authentication authWithoutActor = new TestingAuthenticationToken(new SimpleGrayskullUser("test-user", null), "password");
        SecurityContextHolder.getContext().setAuthentication(authWithoutActor);

        // When & Then
        assertTrue(grayskullSecurity.checkProviderAuthorization("SELF"));
    }

    @Test
    void checkProviderAuthorization_WhenProviderIsSelfAndUserHasActor_ThrowsAccessDeniedException() {
        // Given - User with actor name
        Authentication authWithActor = new TestingAuthenticationToken(new SimpleGrayskullUser("test-user", "actor-name"), "password");
        SecurityContextHolder.getContext().setAuthentication(authWithActor);

        // When & Then
        AccessDeniedException exception = assertThrows(AccessDeniedException.class, 
            () -> grayskullSecurity.checkProviderAuthorization("SELF"));
        assertEquals("User delegation is not supported for 'SELF' managed secrets", exception.getMessage());
    }

    @Test
    void checkProviderAuthorization_WhenProviderIsNotSelfAndUserHasNoActor_ThrowsAccessDeniedException() {
        // Given - User with no actor name
        Authentication authWithoutActor = new TestingAuthenticationToken(new SimpleGrayskullUser("test-user", null), "password");
        SecurityContextHolder.getContext().setAuthentication(authWithoutActor);

        // When & Then
        AccessDeniedException exception = assertThrows(AccessDeniedException.class, 
            () -> grayskullSecurity.checkProviderAuthorization("test-provider"));
        assertEquals("Expected an actor name for the test-provider managed secrets", exception.getMessage());
    }

    @Test
    void checkProviderAuthorization_WhenProviderNotFound_ThrowsAccessDeniedException() {
        // Given - User with actor name but provider doesn't exist
        Authentication authWithActor = new TestingAuthenticationToken(new SimpleGrayskullUser("test-user", "actor-name"), "password");
        SecurityContextHolder.getContext().setAuthentication(authWithActor);
        when(secretProviderRepository.findByName("non-existent-provider")).thenReturn(Optional.empty());

        // When & Then
        AccessDeniedException exception = assertThrows(AccessDeniedException.class, 
            () -> grayskullSecurity.checkProviderAuthorization("non-existent-provider"));
        assertEquals("Secret provider not found", exception.getMessage());
    }

    @Test
    void checkProviderAuthorization_WhenActorNameMatchesProviderPrincipal_ReturnsTrue() {
        // Given - User with actor name that matches provider principal
        String actorName = "matching-actor";
        Authentication authWithActor = new TestingAuthenticationToken(new SimpleGrayskullUser("test-user", actorName), "password");
        SecurityContextHolder.getContext().setAuthentication(authWithActor);
        
        SecretProvider provider = SecretProvider.builder()
                .name("test-provider")
                .principal(actorName)
                .build();
        when(secretProviderRepository.findByName("test-provider")).thenReturn(Optional.of(provider));

        // When & Then
        assertTrue(grayskullSecurity.checkProviderAuthorization("test-provider"));
    }

    @Test
    void checkProviderAuthorization_WhenActorNameDoesNotMatchProviderPrincipal_ThrowsAccessDeniedException() {
        // Given - User with actor name that doesn't match provider principal
        Authentication authWithActor = new TestingAuthenticationToken(new SimpleGrayskullUser("test-user", "wrong-actor"), "password");
        SecurityContextHolder.getContext().setAuthentication(authWithActor);
        
        SecretProvider provider = SecretProvider.builder()
                .name("test-provider")
                .principal("correct-actor")
                .build();
        when(secretProviderRepository.findByName("test-provider")).thenReturn(Optional.of(provider));

        // When & Then
        AccessDeniedException exception = assertThrows(AccessDeniedException.class, 
            () -> grayskullSecurity.checkProviderAuthorization("test-provider"));
        assertEquals("Actor is not authorized to access the secrets of this provider", exception.getMessage());
    }
}
