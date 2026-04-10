package com.flipkart.grayskull.controllers;

import com.flipkart.grayskull.audit.AuditAction;
import com.flipkart.grayskull.audit.AuditConstants;
import com.flipkart.grayskull.audit.utils.RequestUtils;
import com.flipkart.grayskull.models.dto.request.BulkPollRequest;
import com.flipkart.grayskull.models.dto.request.BulkPollSecretEntry;
import com.flipkart.grayskull.models.dto.response.BulkPollResponse;
import com.flipkart.grayskull.models.dto.response.BulkPollUpdatedSecret;
import com.flipkart.grayskull.models.dto.response.SecretDataResponse;
import com.flipkart.grayskull.service.interfaces.SecretService;
import com.flipkart.grayskull.spi.AsyncAuditLogger;
import com.flipkart.grayskull.spi.AuditMetadataEnhancer;
import com.flipkart.grayskull.spi.authn.GrayskullAuthentication;
import com.flipkart.grayskull.spi.models.AuditEntry;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("BulkPollController Unit Tests")
class BulkPollControllerTest {

    private final SecretService secretService = mock(SecretService.class);
    private final AsyncAuditLogger asyncAuditLogger = mock(AsyncAuditLogger.class);
    private final RequestUtils requestUtils = mock(RequestUtils.class);
    private final List<AuditMetadataEnhancer> auditMetadataEnhancers = new ArrayList<>();

    private BulkPollController controller;

    @BeforeEach
    void setUp() {
        controller = new BulkPollController(secretService, asyncAuditLogger, requestUtils, auditMetadataEnhancers);
        SecurityContextHolder.setContext(new SecurityContextImpl(new GrayskullAuthentication("user", "actor-name")));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Should return updated secrets and log audit when secrets have changed")
    void bulkPoll_shouldReturnUpdatedSecretsAndLogAudit() {
        // Arrange
        BulkPollRequest request = new BulkPollRequest(List.of(
                new BulkPollSecretEntry("proj-a", "db-pass", 1),
                new BulkPollSecretEntry("proj-b", "api-key", 2)
        ));
        SecretDataResponse secretValue = SecretDataResponse.builder().dataVersion(3).publicPart("pub").build();
        BulkPollUpdatedSecret updatedSecret = new BulkPollUpdatedSecret("proj-b", "api-key", secretValue);
        BulkPollResponse serviceResponse = new BulkPollResponse(List.of(updatedSecret));
        Map<String, String> expectedIps = Map.of("Remote-Conn-Addr", "10.0.0.1");

        when(secretService.bulkPollSecrets(request.getSecrets())).thenReturn(serviceResponse);
        when(requestUtils.getRemoteIPs()).thenReturn(expectedIps);

        MockHttpServletRequest httpRequest = new MockHttpServletRequest();

        // Act
        var result = controller.bulkPoll(request, httpRequest);

        // Assert
        assertThat(result.getData().getUpdatedSecrets()).hasSize(1);
        assertThat(result.getData().getUpdatedSecrets().get(0).getProjectId()).isEqualTo("proj-b");
        assertThat(result.getData().getUpdatedSecrets().get(0).getSecretName()).isEqualTo("api-key");

        ArgumentCaptor<AuditEntry> auditCaptor = ArgumentCaptor.captor();
        verify(asyncAuditLogger).log(auditCaptor.capture());
        AuditEntry logged = auditCaptor.getValue();
        assertThat(logged.getAction()).isEqualTo(AuditAction.BULK_POLL_SECRETS.name());
        assertThat(logged.getResourceType()).isEqualTo(AuditConstants.RESOURCE_TYPE_SECRET);
        assertThat(logged.getUserId()).isEqualTo("user");
        assertThat(logged.getActorId()).isEqualTo("actor-name");
        assertThat(logged.getIps()).isEqualTo(expectedIps);
        assertThat(logged.getMetadata()).containsEntry("updatedSecretRefs", "proj-b:api-key");
    }

    @Test
    @DisplayName("Should not log audit when no secrets have changed")
    void bulkPoll_shouldNotLogAudit_whenNoSecretsChanged() {
        // Arrange
        BulkPollRequest request = new BulkPollRequest(List.of(
                new BulkPollSecretEntry("proj-a", "db-pass", 5)
        ));
        BulkPollResponse serviceResponse = new BulkPollResponse(List.of());

        when(secretService.bulkPollSecrets(request.getSecrets())).thenReturn(serviceResponse);

        MockHttpServletRequest httpRequest = new MockHttpServletRequest();

        // Act
        var result = controller.bulkPoll(request, httpRequest);

        // Assert
        assertThat(result.getData().getUpdatedSecrets()).isEmpty();
        verify(asyncAuditLogger, never()).log(any());
    }

    @Test
    @DisplayName("Should include audit metadata from enhancers")
    void bulkPoll_shouldIncludeEnhancerMetadata() {
        // Arrange
        AuditMetadataEnhancer enhancer = mock(AuditMetadataEnhancer.class);
        when(enhancer.getAdditionalMetadata(any())).thenReturn(Map.of("requestId", "req-123"));
        auditMetadataEnhancers.add(enhancer);

        SecretDataResponse secretValue = SecretDataResponse.builder().dataVersion(2).build();
        BulkPollUpdatedSecret updatedSecret = new BulkPollUpdatedSecret("proj-a", "db-pass", secretValue);
        BulkPollResponse serviceResponse = new BulkPollResponse(List.of(updatedSecret));

        BulkPollRequest request = new BulkPollRequest(List.of(
                new BulkPollSecretEntry("proj-a", "db-pass", 1)
        ));

        when(secretService.bulkPollSecrets(request.getSecrets())).thenReturn(serviceResponse);
        when(requestUtils.getRemoteIPs()).thenReturn(Map.of());

        MockHttpServletRequest httpRequest = new MockHttpServletRequest();

        // Act
        controller.bulkPoll(request, httpRequest);

        // Assert
        ArgumentCaptor<AuditEntry> auditCaptor = ArgumentCaptor.captor();
        verify(asyncAuditLogger).log(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getMetadata())
                .containsEntry("requestId", "req-123")
                .containsEntry("updatedSecretRefs", "proj-a:db-pass");
    }

    @Test
    @DisplayName("Should format updatedSecretRefs as comma-separated for multiple updates")
    void bulkPoll_shouldFormatMultipleSecretRefs() {
        // Arrange
        SecretDataResponse value1 = SecretDataResponse.builder().dataVersion(2).build();
        SecretDataResponse value2 = SecretDataResponse.builder().dataVersion(3).build();
        BulkPollResponse serviceResponse = new BulkPollResponse(List.of(
                new BulkPollUpdatedSecret("proj-a", "db-pass", value1),
                new BulkPollUpdatedSecret("proj-b", "api-key", value2)
        ));

        BulkPollRequest request = new BulkPollRequest(List.of(
                new BulkPollSecretEntry("proj-a", "db-pass", 1),
                new BulkPollSecretEntry("proj-b", "api-key", 1)
        ));

        when(secretService.bulkPollSecrets(request.getSecrets())).thenReturn(serviceResponse);
        when(requestUtils.getRemoteIPs()).thenReturn(Map.of());

        MockHttpServletRequest httpRequest = new MockHttpServletRequest();

        // Act
        controller.bulkPoll(request, httpRequest);

        // Assert
        ArgumentCaptor<AuditEntry> auditCaptor = ArgumentCaptor.captor();
        verify(asyncAuditLogger).log(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getMetadata().get("updatedSecretRefs"))
                .isEqualTo("proj-a:db-pass,proj-b:api-key");
    }
}
