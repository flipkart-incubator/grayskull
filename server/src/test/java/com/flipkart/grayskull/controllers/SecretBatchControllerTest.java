package com.flipkart.grayskull.controllers;

import com.flipkart.grayskull.audit.AuditAction;
import com.flipkart.grayskull.audit.AuditConstants;
import com.flipkart.grayskull.audit.utils.RequestUtils;
import com.flipkart.grayskull.models.dto.request.BatchGetSecretsRequest;
import com.flipkart.grayskull.models.dto.request.SecretVersionEntry;
import com.flipkart.grayskull.models.dto.response.BatchGetSecretsResponse;
import com.flipkart.grayskull.models.dto.response.BatchSecretItem;
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

    private static BatchSecretItem item(String projectId, String name, int version, String publicPart) {
        return BatchSecretItem.builder()
                .projectId(projectId)
                .secretName(name)
                .dataVersion(version)
                .publicPart(publicPart)
                .build();
    }

    @Test
    @DisplayName("Should log one audit entry per updated secret, matching readSecretValue pattern")
    void batchGet_shouldLogPerSecretAudit() {
        BatchSecretItem updatedSecret = item("proj-b", "api-key", 3, "pub");
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
        BatchGetSecretsResponse serviceResponse = new BatchGetSecretsResponse(2, List.of(
                item("proj-a", "db-pass", 2, "pub1"),
                item("proj-b", "api-key", 3, "pub2")));

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
        when(enhancer.getAdditionalMetadata(any())).thenReturn(Map.of("RequestId", "req-123"));
        auditMetadataEnhancers.add(enhancer);

        BatchGetSecretsResponse serviceResponse = new BatchGetSecretsResponse(1,
                List.of(item("proj-a", "db-pass", 2, "pub")));

        BatchGetSecretsRequest request = new BatchGetSecretsRequest(List.of(
                new SecretVersionEntry("proj-a", "db-pass", 1)));

        when(secretService.batchGetSecrets(request.getSecrets())).thenReturn(serviceResponse);
        when(requestUtils.getRemoteIPs()).thenReturn(Map.of());

        controller.batchGetSecrets(request, new MockHttpServletRequest());

        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.captor();
        verify(asyncAuditLogger).log(captor.capture());
        assertThat(captor.getValue().getMetadata())
                .containsEntry("RequestId", "req-123")
                .containsEntry("publicPart", "pub");
    }

    @Test
    @DisplayName("Should skip null metadata from enhancers")
    void batchGet_shouldSkipNullEnhancerMetadata() {
        AuditMetadataEnhancer nullEnhancer = mock(AuditMetadataEnhancer.class);
        when(nullEnhancer.getAdditionalMetadata(any())).thenReturn(null);
        auditMetadataEnhancers.add(nullEnhancer);

        BatchGetSecretsResponse serviceResponse = new BatchGetSecretsResponse(1,
                List.of(item("proj-a", "db-pass", 2, "pub")));

        BatchGetSecretsRequest request = new BatchGetSecretsRequest(List.of(
                new SecretVersionEntry("proj-a", "db-pass", 1)));

        when(secretService.batchGetSecrets(request.getSecrets())).thenReturn(serviceResponse);
        when(requestUtils.getRemoteIPs()).thenReturn(Map.of());

        controller.batchGetSecrets(request, new MockHttpServletRequest());

        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.captor();
        verify(asyncAuditLogger).log(captor.capture());
        assertThat(captor.getValue().getMetadata()).containsKey("publicPart");
        assertThat(captor.getValue().getMetadata()).doesNotContainKey("RequestId");
    }

    @Test
    @DisplayName("Per-secret metadata should not bleed into other entries in the same batch")
    void batchGet_perSecretMetadataIsIsolated() {
        AuditMetadataEnhancer enhancer = mock(AuditMetadataEnhancer.class);
        when(enhancer.getAdditionalMetadata(any())).thenReturn(Map.of("RequestId", "req-xyz"));
        auditMetadataEnhancers.add(enhancer);

        BatchGetSecretsResponse serviceResponse = new BatchGetSecretsResponse(2, List.of(
                item("proj-a", "s1", 2, "pubA"),
                item("proj-b", "s2", 3, "pubB")));

        BatchGetSecretsRequest request = new BatchGetSecretsRequest(List.of(
                new SecretVersionEntry("proj-a", "s1", 1),
                new SecretVersionEntry("proj-b", "s2", 1)));

        when(secretService.batchGetSecrets(request.getSecrets())).thenReturn(serviceResponse);
        when(requestUtils.getRemoteIPs()).thenReturn(Map.of());

        controller.batchGetSecrets(request, new MockHttpServletRequest());

        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.captor();
        verify(asyncAuditLogger, times(2)).log(captor.capture());
        // Same RequestId on every entry (correlation) ...
        assertThat(captor.getAllValues().get(0).getMetadata()).containsEntry("RequestId", "req-xyz");
        assertThat(captor.getAllValues().get(1).getMetadata()).containsEntry("RequestId", "req-xyz");
        // ... but each entry carries only its own publicPart.
        assertThat(captor.getAllValues().get(0).getMetadata()).containsEntry("publicPart", "pubA");
        assertThat(captor.getAllValues().get(1).getMetadata()).containsEntry("publicPart", "pubB");
    }
}
