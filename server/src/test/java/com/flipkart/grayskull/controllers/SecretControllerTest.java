package com.flipkart.grayskull.controllers;

import com.flipkart.grayskull.audit.AuditAction;
import com.flipkart.grayskull.audit.AuditConstants;
import com.flipkart.grayskull.audit.utils.RequestUtils;
import com.flipkart.grayskull.models.dto.request.CreateSecretRequest;
import com.flipkart.grayskull.models.dto.response.SecretDataResponse;
import com.flipkart.grayskull.models.dto.response.SecretDataVersionResponse;
import com.flipkart.grayskull.models.dto.response.SecretResponse;
import com.flipkart.grayskull.service.interfaces.SecretService;
import com.flipkart.grayskull.spi.AsyncAuditLogger;
import com.flipkart.grayskull.spi.MetadataValidator;
import com.flipkart.grayskull.spi.models.AuditEntry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("SecretController Unit Tests")
class SecretControllerTest {

    private static final String PROJECT_ID = "test-project";
    private static final String SECRET_NAME = "test-secret";

    private final SecretService secretService = mock(SecretService.class);

    private final AsyncAuditLogger asyncAuditLogger = mock(AsyncAuditLogger.class);

    private final RequestUtils requestUtils = mock(RequestUtils.class);
    private final List<MetadataValidator> plugins = new ArrayList<>();

    private SecretController secretController;

    @BeforeEach
    void setUp() {
        secretController = new SecretController(secretService, asyncAuditLogger, requestUtils, plugins);
        SecurityContextHolder.setContext(new SecurityContextImpl(new TestingAuthenticationToken("user", null)));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @DisplayName("Should successfully read secret data value and log audit")
    @CsvSource({"'public-part'", "''", "null"})
    void shouldSuccessfullyReadSecretValue(String publicPart) {
        // Arrange
        SecretDataResponse expectedResponse = SecretDataResponse.builder().publicPart(publicPart).dataVersion(5).build();
        Map<String, String> expectedIps = Map.of("Remote-Conn-Addr", "ip1");

        when(secretService.readSecretValue(PROJECT_ID, SECRET_NAME)).thenReturn(expectedResponse);
        when(requestUtils.getRemoteIPs()).thenReturn(expectedIps);

        // Act
        var result = secretController.readSecretValue(PROJECT_ID, SECRET_NAME);

        // Assert
        assertThat(result.getData()).isEqualTo(expectedResponse);

        // Verify audit logging
        ArgumentCaptor<AuditEntry> auditEntryArgumentCaptor = ArgumentCaptor.captor();
        verify(asyncAuditLogger).log(auditEntryArgumentCaptor.capture());
        Map<String, String> expectedAuditMetadata = new HashMap<>();
        expectedAuditMetadata.put("publicPart", publicPart);
        assertThat(auditEntryArgumentCaptor.getValue())
                .usingRecursiveComparison()
                .ignoringFields("timestamp")
                .isEqualTo(new AuditEntry(null, PROJECT_ID, AuditConstants.RESOURCE_TYPE_SECRET, SECRET_NAME, 5, AuditAction.READ_SECRET.name(), "user", null, expectedIps, null, expectedAuditMetadata));
    }

    @ParameterizedTest
    @DisplayName("Should successfully read secret version and log audit")
    @CsvSource({"'public-part'", "''", "null"})
    void shouldSuccessfullyReadSecretVersion(String publicPart) {
        // Arrange
        SecretDataVersionResponse expectedResponse = SecretDataVersionResponse.builder().publicPart(publicPart).dataVersion(5).build();
        Map<String, String> expectedIps = Map.of("Remote-Conn-Addr", "ip1");

        when(secretService.getSecretDataVersion(PROJECT_ID, SECRET_NAME, 5, Optional.empty())).thenReturn(expectedResponse);
        when(requestUtils.getRemoteIPs()).thenReturn(expectedIps);

        // Act
        var result = secretController.getSecretDataVersion(PROJECT_ID, SECRET_NAME, 5, Optional.empty());

        // Assert
        assertThat(result.getData()).isEqualTo(expectedResponse);

        // Verify audit logging
        ArgumentCaptor<AuditEntry> auditEntryArgumentCaptor = ArgumentCaptor.captor();
        verify(asyncAuditLogger).log(auditEntryArgumentCaptor.capture());
        Map<String, String> expectedMetadata = new HashMap<>();
        expectedMetadata.put("publicPart", publicPart);
        assertThat(auditEntryArgumentCaptor.getValue())
                .usingRecursiveComparison()
                .ignoringFields("timestamp")
                .isEqualTo(new AuditEntry(null, PROJECT_ID, AuditConstants.RESOURCE_TYPE_SECRET, SECRET_NAME, 5, AuditAction.READ_SECRET_VERSION.name(), "user", null, expectedIps, null, expectedMetadata));
    }


    @ParameterizedTest
    @CsvSource({"0", "1", "10"})
    void shouldSuccessfullyCreateSecret(int numValidators) {
        // Arrange
        plugins.clear();
        for (int i = 0; i < numValidators; i++) {
            plugins.add(mock(MetadataValidator.class));
        }
        SecretResponse response = mock();
        when(secretService.createSecret(eq(PROJECT_ID), any())).thenReturn(response);

        // Act
        var result = secretController.createSecret(PROJECT_ID, new CreateSecretRequest());

        // Assert
        assertThat(result.getData()).isEqualTo(response);
        for (int i = 0; i < numValidators; i++) {
            verify(plugins.get(i)).validateMetadata(any(), any());
        }
    }
}