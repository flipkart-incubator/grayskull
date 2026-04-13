package com.flipkart.grayskull.controllers;

import com.flipkart.grayskull.audit.AuditAction;
import com.flipkart.grayskull.audit.AuditConstants;
import com.flipkart.grayskull.audit.utils.RequestUtils;
import com.flipkart.grayskull.models.dto.request.BatchGetSecretsRequest;
import com.flipkart.grayskull.models.dto.request.SecretVersionEntry;
import com.flipkart.grayskull.models.dto.response.BatchGetSecretsResponse;
import com.flipkart.grayskull.models.dto.response.UpdatedSecret;
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

@DisplayName("SecretBatchController Unit Tests")
class SecretBatchControllerTest {

    private final SecretService secretService = mock(SecretService.class);
    private final AsyncAuditLogger asyncAuditLogger = mock(AsyncAuditLogger.class);
    private final RequestUtils requestUtils = mock(RequestUtils.class);
    private final List<AuditMetadataEnhancer> auditMetadataEnhancers = new ArrayList<>();

    private SecretBatchController controller;

    @BeforeEach
    void setUp() {
        controller = new SecretBatchController(secretService, asyncAuditLogger, requestUtils, auditMetadataEnhancers);
        SecurityContextHolder.setContext(new SecurityContextImpl(new GrayskullAuthentication("user", "actor-name")));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Should return updated secrets and log audit when secrets have changed")
    void batchGet_shouldReturnUpdatedSecretsAndLogAudit() {
        BatchGetSecretsRequest request = new BatchGetSecretsRequest(List.of(
                new SecretVersionEntry("proj-a", "db-pass", 1),
                new SecretVersionEntry("proj-b", "api-key", 2)
        ));
        SecretDataResponse secretValue = SecretDataResponse.builder().dataVersion(3).publicPart("pub").build();
        UpdatedSecret updatedSecret = new UpdatedSecret("proj-b", "api-key", secretValue);
        BatchGetSecretsResponse serviceResponse = new BatchGetSecretsResponse(1, List.of(updatedSecret));
        Map<String, String> expectedIps = Map.of("Remote-Conn-Addr", "10.0.0.1");

        when(secretService.batchGetSecrets(request.getSecrets())).thenReturn(serviceResponse);
        when(requestUtils.getRemoteIPs()).thenReturn(expectedIps);

        MockHttpServletRequest httpRequest = new MockHttpServletRequest();

        var result = controller.batchGetSecrets(request, httpRequest);

        assertThat(result.getData().getUpdatedCount()).isEqualTo(1);
        assertThat(result.getData().getUpdatedSecrets()).hasSize(1);
        assertThat(result.getData().getUpdatedSecrets().get(0).getProjectId()).isEqualTo("proj-b");

        ArgumentCaptor<AuditEntry> auditCaptor = ArgumentCaptor.captor();
        verify(asyncAuditLogger).log(auditCaptor.capture());
        AuditEntry logged = auditCaptor.getValue();
        assertThat(logged.getAction()).isEqualTo(AuditAction.BATCH_GET_SECRETS.name());
        assertThat(logged.getResourceType()).isEqualTo(AuditConstants.RESOURCE_TYPE_SECRET);
        assertThat(logged.getUserId()).isEqualTo("user");
        assertThat(logged.getActorId()).isEqualTo("actor-name");
        assertThat(logged.getIps()).isEqualTo(expectedIps);
        assertThat(logged.getMetadata()).containsEntry("updatedSecretRefs", "proj-b:api-key");
    }

    @Test
    @DisplayName("Should not log audit when no secrets have changed")
    void batchGet_shouldNotLogAudit_whenNoSecretsChanged() {
        BatchGetSecretsRequest request = new BatchGetSecretsRequest(List.of(
                new SecretVersionEntry("proj-a", "db-pass", 5)
        ));
        BatchGetSecretsResponse serviceResponse = new BatchGetSecretsResponse(0, List.of());

        when(secretService.batchGetSecrets(request.getSecrets())).thenReturn(serviceResponse);

        MockHttpServletRequest httpRequest = new MockHttpServletRequest();

        var result = controller.batchGetSecrets(request, httpRequest);

        assertThat(result.getData().getUpdatedCount()).isZero();
        assertThat(result.getData().getUpdatedSecrets()).isEmpty();
        verify(asyncAuditLogger, never()).log(any());
    }

    @Test
    @DisplayName("Should include audit metadata from enhancers")
    void batchGet_shouldIncludeEnhancerMetadata() {
        AuditMetadataEnhancer enhancer = mock(AuditMetadataEnhancer.class);
        when(enhancer.getAdditionalMetadata(any())).thenReturn(Map.of("requestId", "req-123"));
        auditMetadataEnhancers.add(enhancer);

        SecretDataResponse secretValue = SecretDataResponse.builder().dataVersion(2).build();
        UpdatedSecret updatedSecret = new UpdatedSecret("proj-a", "db-pass", secretValue);
        BatchGetSecretsResponse serviceResponse = new BatchGetSecretsResponse(1, List.of(updatedSecret));

        BatchGetSecretsRequest request = new BatchGetSecretsRequest(List.of(
                new SecretVersionEntry("proj-a", "db-pass", 1)
        ));

        when(secretService.batchGetSecrets(request.getSecrets())).thenReturn(serviceResponse);
        when(requestUtils.getRemoteIPs()).thenReturn(Map.of());

        MockHttpServletRequest httpRequest = new MockHttpServletRequest();

        controller.batchGetSecrets(request, httpRequest);

        ArgumentCaptor<AuditEntry> auditCaptor = ArgumentCaptor.captor();
        verify(asyncAuditLogger).log(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getMetadata())
                .containsEntry("requestId", "req-123")
                .containsEntry("updatedSecretRefs", "proj-a:db-pass");
    }

    @Test
    @DisplayName("Should format updatedSecretRefs as comma-separated for multiple updates")
    void batchGet_shouldFormatMultipleSecretRefs() {
        SecretDataResponse value1 = SecretDataResponse.builder().dataVersion(2).build();
        SecretDataResponse value2 = SecretDataResponse.builder().dataVersion(3).build();
        BatchGetSecretsResponse serviceResponse = new BatchGetSecretsResponse(2, List.of(
                new UpdatedSecret("proj-a", "db-pass", value1),
                new UpdatedSecret("proj-b", "api-key", value2)
        ));

        BatchGetSecretsRequest request = new BatchGetSecretsRequest(List.of(
                new SecretVersionEntry("proj-a", "db-pass", 1),
                new SecretVersionEntry("proj-b", "api-key", 1)
        ));

        when(secretService.batchGetSecrets(request.getSecrets())).thenReturn(serviceResponse);
        when(requestUtils.getRemoteIPs()).thenReturn(Map.of());

        MockHttpServletRequest httpRequest = new MockHttpServletRequest();

        controller.batchGetSecrets(request, httpRequest);

        ArgumentCaptor<AuditEntry> auditCaptor = ArgumentCaptor.captor();
        verify(asyncAuditLogger).log(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getMetadata().get("updatedSecretRefs"))
                .isEqualTo("proj-a:db-pass,proj-b:api-key");
    }

    @Test
    @DisplayName("Should skip null metadata from enhancers")
    void batchGet_shouldSkipNullEnhancerMetadata() {
        AuditMetadataEnhancer nullEnhancer = mock(AuditMetadataEnhancer.class);
        when(nullEnhancer.getAdditionalMetadata(any())).thenReturn(null);
        auditMetadataEnhancers.add(nullEnhancer);

        SecretDataResponse secretValue = SecretDataResponse.builder().dataVersion(2).build();
        UpdatedSecret updatedSecret = new UpdatedSecret("proj-a", "db-pass", secretValue);
        BatchGetSecretsResponse serviceResponse = new BatchGetSecretsResponse(1, List.of(updatedSecret));

        BatchGetSecretsRequest request = new BatchGetSecretsRequest(List.of(
                new SecretVersionEntry("proj-a", "db-pass", 1)
        ));

        when(secretService.batchGetSecrets(request.getSecrets())).thenReturn(serviceResponse);
        when(requestUtils.getRemoteIPs()).thenReturn(Map.of());
        MockHttpServletRequest httpRequest = new MockHttpServletRequest();

        controller.batchGetSecrets(request, httpRequest);

        ArgumentCaptor<AuditEntry> auditCaptor = ArgumentCaptor.captor();
        verify(asyncAuditLogger).log(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getMetadata()).containsKey("updatedSecretRefs");
    }
}
