package com.flipkart.grayskull.controllers;

import com.flipkart.grayskull.audit.AuditAction;
import com.flipkart.grayskull.audit.AuditConstants;
import com.flipkart.grayskull.audit.utils.RequestUtils;
import com.flipkart.grayskull.models.dto.response.SecretDataResponse;
import com.flipkart.grayskull.models.dto.response.SecretDataVersionResponse;
import com.flipkart.grayskull.service.interfaces.SecretService;
import com.flipkart.grayskull.spi.AsyncAuditLogger;
import com.flipkart.grayskull.spi.models.AuditEntry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("SecretController Unit Tests")
class SecretControllerTest {

    private static final String PROJECT_ID = "test-project";
    private static final String SECRET_NAME = "test-secret";

    private final SecretService secretService = mock(SecretService.class);

    private final AsyncAuditLogger asyncAuditLogger = mock(AsyncAuditLogger.class);

    private final RequestUtils requestUtils = mock(RequestUtils.class);

    private SecretController secretController;

    @BeforeEach
    void setUp() {
        secretController = new SecretController(secretService, asyncAuditLogger, requestUtils);
        SecurityContextHolder.setContext(new SecurityContextImpl(new TestingAuthenticationToken("user", null)));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static Object[][] shouldSuccessfullyReadSecretValueProvider() {
        return new Object[][]{
                new Object[]{"public-data", Map.of("publicPart", "public-data")},
                new Object[]{"", Map.of("publicPart", "")},
                new Object[]{null, Map.of()}
        };
    }


    @ParameterizedTest
    @DisplayName("Should successfully read secret data value and log audit")
    @MethodSource("shouldSuccessfullyReadSecretValueProvider")
    void shouldSuccessfullyReadSecretValue(String publicPart, Map<String, String> expectedAuditMetadata) {
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
        assertThat(auditEntryArgumentCaptor.getValue())
                .usingRecursiveComparison()
                .ignoringFields("timestamp")
                .isEqualTo(new AuditEntry(null, PROJECT_ID, AuditConstants.RESOURCE_TYPE_SECRET, SECRET_NAME, 5, AuditAction.READ_SECRET.name(), "user", expectedIps, null, expectedAuditMetadata));
    }

    @Test
    @DisplayName("Should successfully read secret version and log audit")
    void shouldSuccessfullyReadSecretVersion() {
        // Arrange
        SecretDataVersionResponse expectedResponse = SecretDataVersionResponse.builder().publicPart("public-data").dataVersion(5).build();
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
        assertThat(auditEntryArgumentCaptor.getValue())
                .usingRecursiveComparison()
                .ignoringFields("timestamp")
                .isEqualTo(new AuditEntry(null, PROJECT_ID, AuditConstants.RESOURCE_TYPE_SECRET, SECRET_NAME, 5, AuditAction.READ_SECRET_VERSION.name(), "user", expectedIps, null, Map.of("publicPart", expectedResponse.getPublicPart())));
    }
}