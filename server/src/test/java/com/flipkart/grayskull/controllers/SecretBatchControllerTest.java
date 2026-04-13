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
    @DisplayName("Should log one audit entry per updated secret, matching readSecretValue pattern")
    void batchGet_shouldLogPerSecretAudit() {
        SecretDataResponse value = SecretDataResponse.builder()
                .dataVersion(3).publicPart("pub").build();
        UpdatedSecret updatedSecret = new UpdatedSecret("proj-b", "api-key", value);
        BatchGetSecretsResponse serviceResponse = new BatchGetSecretsResponse(1, List.of(updatedSecret));
        Map<String, String> expectedIps = Map.of("Remote-Conn-Addr", "10.0.0.1");

        BatchGetSecretsRequest request = new BatchGetSecretsRequest(List.of(
                new SecretVersionEntry("proj-b", "api-key", 2)));

        when(secretService.batchGetSecrets(request.getSecrets())).thenReturn(serviceResponse);
        when(requestUtils.getRemoteIPs()).thenReturn(expectedIps);

        var result = controller.batchGetSecrets(request, new MockHttpServletRequest());

        assertThat(result.getData().getUpdatedCount()).isEqualTo(1);

        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.captor();
        verify(asyncAuditLogger).log(captor.capture());
        AuditEntry logged = captor.getValue();
        assertThat(logged.getProjectId()).isEqualTo("proj-b");
        assertThat(logged.getResourceType()).isEqualTo(AuditConstants.RESOURCE_TYPE_SECRET);
        assertThat(logged.getResourceName()).isEqualTo("api-key");
        assertThat(logged.getResourceVersion()).isEqualTo(3);
        assertThat(logged.getAction()).isEqualTo(AuditAction.BATCH_GET_SECRETS.name());
        assertThat(logged.getUserId()).isEqualTo("user");
        assertThat(logged.getActorId()).isEqualTo("actor-name");
        assertThat(logged.getIps()).isEqualTo(expectedIps);
        assertThat(logged.getMetadata()).containsEntry("publicPart", "pub");
    }

    @Test
    @DisplayName("Should not log audit when no secrets have changed")
    void batchGet_shouldNotLogAudit_whenNoSecretsChanged() {
        BatchGetSecretsRequest request = new BatchGetSecretsRequest(List.of(
                new SecretVersionEntry("proj-a", "db-pass", 5)));
        BatchGetSecretsResponse serviceResponse = new BatchGetSecretsResponse(0, List.of());

        when(secretService.batchGetSecrets(request.getSecrets())).thenReturn(serviceResponse);

        var result = controller.batchGetSecrets(request, new MockHttpServletRequest());

        assertThat(result.getData().getUpdatedCount()).isZero();
        assertThat(result.getData().getUpdatedSecrets()).isEmpty();
        verify(asyncAuditLogger, never()).log(any());
    }

    @Test
    @DisplayName("Should log N audit entries for N updated secrets")
    void batchGet_shouldLogOneEntryPerUpdatedSecret() {
        SecretDataResponse value1 = SecretDataResponse.builder()
                .dataVersion(2).publicPart("pub1").build();
        SecretDataResponse value2 = SecretDataResponse.builder()
                .dataVersion(3).publicPart("pub2").build();
        BatchGetSecretsResponse serviceResponse = new BatchGetSecretsResponse(2, List.of(
                new UpdatedSecret("proj-a", "db-pass", value1),
                new UpdatedSecret("proj-b", "api-key", value2)));

        BatchGetSecretsRequest request = new BatchGetSecretsRequest(List.of(
                new SecretVersionEntry("proj-a", "db-pass", 1),
                new SecretVersionEntry("proj-b", "api-key", 1)));

        when(secretService.batchGetSecrets(request.getSecrets())).thenReturn(serviceResponse);
        when(requestUtils.getRemoteIPs()).thenReturn(Map.of());

        controller.batchGetSecrets(request, new MockHttpServletRequest());

        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.captor();
        verify(asyncAuditLogger, times(2)).log(captor.capture());

        AuditEntry first = captor.getAllValues().get(0);
        assertThat(first.getProjectId()).isEqualTo("proj-a");
        assertThat(first.getResourceName()).isEqualTo("db-pass");
        assertThat(first.getResourceVersion()).isEqualTo(2);
        assertThat(first.getAction()).isEqualTo(AuditAction.BATCH_GET_SECRETS.name());
        assertThat(first.getMetadata()).containsEntry("publicPart", "pub1");

        AuditEntry second = captor.getAllValues().get(1);
        assertThat(second.getProjectId()).isEqualTo("proj-b");
        assertThat(second.getResourceName()).isEqualTo("api-key");
        assertThat(second.getResourceVersion()).isEqualTo(3);
        assertThat(second.getMetadata()).containsEntry("publicPart", "pub2");
    }

    @Test
    @DisplayName("Should include audit metadata from enhancers in each per-secret entry")
    void batchGet_shouldIncludeEnhancerMetadataInEachEntry() {
        AuditMetadataEnhancer enhancer = mock(AuditMetadataEnhancer.class);
        when(enhancer.getAdditionalMetadata(any())).thenReturn(Map.of("requestId", "req-123"));
        auditMetadataEnhancers.add(enhancer);

        SecretDataResponse value = SecretDataResponse.builder()
                .dataVersion(2).publicPart("pub").build();
        BatchGetSecretsResponse serviceResponse = new BatchGetSecretsResponse(1,
                List.of(new UpdatedSecret("proj-a", "db-pass", value)));

        BatchGetSecretsRequest request = new BatchGetSecretsRequest(List.of(
                new SecretVersionEntry("proj-a", "db-pass", 1)));

        when(secretService.batchGetSecrets(request.getSecrets())).thenReturn(serviceResponse);
        when(requestUtils.getRemoteIPs()).thenReturn(Map.of());

        controller.batchGetSecrets(request, new MockHttpServletRequest());

        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.captor();
        verify(asyncAuditLogger).log(captor.capture());
        assertThat(captor.getValue().getMetadata())
                .containsEntry("requestId", "req-123")
                .containsEntry("publicPart", "pub");
    }

    @Test
    @DisplayName("Should skip null metadata from enhancers")
    void batchGet_shouldSkipNullEnhancerMetadata() {
        AuditMetadataEnhancer nullEnhancer = mock(AuditMetadataEnhancer.class);
        when(nullEnhancer.getAdditionalMetadata(any())).thenReturn(null);
        auditMetadataEnhancers.add(nullEnhancer);

        SecretDataResponse value = SecretDataResponse.builder()
                .dataVersion(2).publicPart("pub").build();
        BatchGetSecretsResponse serviceResponse = new BatchGetSecretsResponse(1,
                List.of(new UpdatedSecret("proj-a", "db-pass", value)));

        BatchGetSecretsRequest request = new BatchGetSecretsRequest(List.of(
                new SecretVersionEntry("proj-a", "db-pass", 1)));

        when(secretService.batchGetSecrets(request.getSecrets())).thenReturn(serviceResponse);
        when(requestUtils.getRemoteIPs()).thenReturn(Map.of());

        controller.batchGetSecrets(request, new MockHttpServletRequest());

        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.captor();
        verify(asyncAuditLogger).log(captor.capture());
        assertThat(captor.getValue().getMetadata()).containsKey("publicPart");
        assertThat(captor.getValue().getMetadata()).doesNotContainKey("requestId");
    }
}
