package com.flipkart.grayskull.authz;

import com.flipkart.grayskull.models.dto.request.SecretVersionEntry;
import com.flipkart.grayskull.spi.GrayskullAuthorizationProvider;
import com.flipkart.grayskull.spi.authn.GrayskullAuthentication;
import com.flipkart.grayskull.spi.authz.AuthorizationContext;
import com.flipkart.grayskull.spi.models.Project;
import com.flipkart.grayskull.spi.models.Secret;
import com.flipkart.grayskull.spi.models.SecretProvider;
import com.flipkart.grayskull.spi.repositories.ProjectRepository;
import com.flipkart.grayskull.spi.repositories.SecretProviderRepository;
import com.flipkart.grayskull.spi.repositories.SecretRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static com.flipkart.grayskull.service.utils.SecretProviderConstants.PROVIDER_SELF;
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

    private final Authentication authentication = new GrayskullAuthentication("test-user", null);
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
        when(authorizationProvider.isAuthorized(any(GrayskullAuthentication.class), eq("providers.create"))).thenReturn(true);

        // When
        boolean result = grayskullSecurity.hasPermission("providers.create");

        // Then
        assertTrue(result);
    }

    @Test
    void hasPermission_GlobalLevel_WhenNotAuthorized_ReturnsFalse() {
        // Given
        SecurityContextHolder.getContext().setAuthentication(authentication);
        when(authorizationProvider.isAuthorized(any(GrayskullAuthentication.class), eq("providers.create"))).thenReturn(false);

        // When
        boolean result = grayskullSecurity.hasPermission("providers.create");

        // Then
        assertFalse(result);
    }

    // Tests for checkProviderAuthorization method
    @Test
    void checkProviderAuthorization_WhenProviderIsSelfAndUserHasNoActor_ReturnsTrue() {
        // Given - User with no actor name
        Authentication authWithoutActor = new GrayskullAuthentication("test-user", null);
        SecurityContextHolder.getContext().setAuthentication(authWithoutActor);

        // When & Then
        assertTrue(grayskullSecurity.checkProviderAuthorization(PROVIDER_SELF));
    }

    @Test
    void checkProviderAuthorization_WhenProviderIsSelfAndUserHasActor_ReturnsTrue() {
        // Given - User with actor name
        Authentication authWithActor = new GrayskullAuthentication("test-user", "actor-name");
        SecurityContextHolder.getContext().setAuthentication(authWithActor);

        // When & Then
        assertTrue(grayskullSecurity.checkProviderAuthorization(PROVIDER_SELF));
    }

    @Test
    void checkProviderAuthorization_WhenProviderIsNotSelfAndUserHasNoActor_ThrowsAccessDeniedException() {
        // Given - User with no actor name
        Authentication authWithoutActor = new GrayskullAuthentication("test-user", null);
        SecurityContextHolder.getContext().setAuthentication(authWithoutActor);

        // When & Then
        AccessDeniedException exception = assertThrows(AccessDeniedException.class, 
            () -> grayskullSecurity.checkProviderAuthorization("test-provider"));
        assertEquals("Expected an actor name for the test-provider managed secrets", exception.getMessage());
    }

    @Test
    void checkProviderAuthorization_WhenProviderNotFound_ReturnsFalse() {
        // Given - User with actor name but provider doesn't exist
        Authentication authWithActor = new GrayskullAuthentication("test-user", "actor-name");
        SecurityContextHolder.getContext().setAuthentication(authWithActor);
        when(secretProviderRepository.findByName("non-existent-provider")).thenReturn(Optional.empty());

        // When & Then
        assertFalse(grayskullSecurity.checkProviderAuthorization("non-existent-provider"));
    }

    @Test
    void checkProviderAuthorization_WhenActorNameMatchesProviderPrincipal_ReturnsTrue() {
        // Given - User with actor name that matches provider principal
        String actorName = "matching-actor";
        Authentication authWithActor = new GrayskullAuthentication("test-user", actorName);
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
    void checkProviderAuthorization_WhenActorNameDoesNotMatchProviderPrincipal_ReturnsFalse() {
        // Given - User with actor name that doesn't match provider principal
        Authentication authWithActor = new GrayskullAuthentication("test-user", "wrong-actor");
        SecurityContextHolder.getContext().setAuthentication(authWithActor);
        
        SecretProvider provider = SecretProvider.builder()
                .name("test-provider")
                .principal("correct-actor")
                .build();
        when(secretProviderRepository.findByName("test-provider")).thenReturn(Optional.of(provider));

        // When & Then
        assertFalse(grayskullSecurity.checkProviderAuthorization("test-provider"));
    }

    // Tests for hasPermissionForSecrets method (batch authz).
    // Contract: no DB fetches; for each entry, transient Project/Secret are built from the
    // request and the SPI is called in a fail-fast loop via isAuthorized(ctx, action).
    @Test
    void hasPermissionForSecrets_WhenAllAuthorized_ReturnsTrue_AndMakesNoDbCalls() {
        SecurityContextHolder.getContext().setAuthentication(authentication);
        when(authorizationProvider.isAuthorized(any(AuthorizationContext.class), eq("secrets.read.value")))
                .thenReturn(true);

        List<SecretVersionEntry> entries = List.of(
                new SecretVersionEntry("proj-a", "secret-1", 1),
                new SecretVersionEntry("proj-b", "secret-2", 2));

        assertTrue(grayskullSecurity.hasPermissionForSecrets(entries, "secrets.read.value"));

        verifyNoInteractions(projectRepository);
        verifyNoInteractions(secretRepository);
        verify(authorizationProvider, times(2))
                .isAuthorized(any(AuthorizationContext.class), eq("secrets.read.value"));
    }

    @Test
    void hasPermissionForSecrets_PassesEachEntryThroughToSpiInOrder() {
        SecurityContextHolder.getContext().setAuthentication(authentication);
        ArgumentCaptor<AuthorizationContext> captor = ArgumentCaptor.forClass(AuthorizationContext.class);
        when(authorizationProvider.isAuthorized(captor.capture(), eq("secrets.read.value"))).thenReturn(true);

        List<SecretVersionEntry> entries = List.of(
                new SecretVersionEntry("proj-a", "secret-1", 1),
                new SecretVersionEntry("proj-b", "secret-2", 2));

        assertTrue(grayskullSecurity.hasPermissionForSecrets(entries, "secrets.read.value"));

        List<AuthorizationContext> forwarded = captor.getAllValues();
        assertEquals(2, forwarded.size());
        assertEquals("proj-a", forwarded.get(0).getProjectId());
        assertEquals(Optional.of("secret-1"), forwarded.get(0).getSecretName());
        assertEquals("proj-b", forwarded.get(1).getProjectId());
        assertEquals(Optional.of("secret-2"), forwarded.get(1).getSecretName());
    }

    @Test
    void hasPermissionForSecrets_FailsFast_OnFirstDenial_AndStopsCalling() {
        SecurityContextHolder.getContext().setAuthentication(authentication);
        // First entry denied -> second entry must not be evaluated.
        when(authorizationProvider.isAuthorized(any(AuthorizationContext.class), eq("secrets.read.value")))
                .thenReturn(false)
                .thenThrow(new AssertionError("should not be called after a denial"));

        List<SecretVersionEntry> entries = List.of(
                new SecretVersionEntry("proj-a", "s1", 1),
                new SecretVersionEntry("proj-a", "s2", 2));

        assertFalse(grayskullSecurity.hasPermissionForSecrets(entries, "secrets.read.value"));
        verify(authorizationProvider, times(1))
                .isAuthorized(any(AuthorizationContext.class), eq("secrets.read.value"));
    }

    @Test
    void hasPermissionForSecrets_FailsOnSecondEntry_ReturnsFalse() {
        SecurityContextHolder.getContext().setAuthentication(authentication);
        when(authorizationProvider.isAuthorized(any(AuthorizationContext.class), eq("secrets.read.value")))
                .thenReturn(true)
                .thenReturn(false);

        List<SecretVersionEntry> entries = List.of(
                new SecretVersionEntry("proj-a", "s1", 1),
                new SecretVersionEntry("proj-a", "s2", 2));

        assertFalse(grayskullSecurity.hasPermissionForSecrets(entries, "secrets.read.value"));
        verify(authorizationProvider, times(2))
                .isAuthorized(any(AuthorizationContext.class), eq("secrets.read.value"));
    }

    @Test
    void hasPermissionForSecrets_WhenEmptyList_ReturnsTrue_WithoutTouchingProvider() {
        SecurityContextHolder.getContext().setAuthentication(authentication);

        assertTrue(grayskullSecurity.hasPermissionForSecrets(List.of(), "secrets.read.value"));

        verifyNoInteractions(projectRepository);
        verifyNoInteractions(secretRepository);
        verifyNoInteractions(authorizationProvider);
    }

    @Test
    void hasPermissionForSecrets_WithNullLastKnownVersion_AllowsAuthzCheck() {
        // Verifies null lastKnownVersion (per SecretVersionEntry contract) is accepted and passed through.
        SecurityContextHolder.getContext().setAuthentication(authentication);
        when(authorizationProvider.isAuthorized(any(AuthorizationContext.class), eq("secrets.read.value")))
                .thenReturn(true);

        List<SecretVersionEntry> entries = List.of(new SecretVersionEntry("proj-a", "s1", null));

        assertTrue(grayskullSecurity.hasPermissionForSecrets(entries, "secrets.read.value"));
        verify(authorizationProvider, times(1))
                .isAuthorized(any(AuthorizationContext.class), eq("secrets.read.value"));
    }
}
