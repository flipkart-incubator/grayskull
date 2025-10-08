package com.flipkart.grayskull.controllers;

import com.flipkart.grayskull.models.db.AuditEntry;
import com.flipkart.grayskull.models.dto.response.SecretDataResponse;
import com.flipkart.grayskull.service.interfaces.SecretService;
import com.flipkart.grayskull.spi.AsyncAuditLogger;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("SecretController Unit Tests")
class SecretControllerTest {

    private final SecretService secretService = mock(SecretService.class);

    private final AsyncAuditLogger asyncAuditLogger = mock(AsyncAuditLogger.class);

    private SecretController secretController;

    @BeforeEach
    void setUp() {
        secretController = new SecretController(secretService, asyncAuditLogger);
    }

    @Nested
    @DisplayName("readSecretValue Tests")
    class ReadSecretValueTests {

        private static final String PROJECT_ID = "test-project";
        private static final String SECRET_NAME = "test-secret";
        private static final String SECRET_VALUE = "sensitive-secret-value";
        private static final int DATA_VERSION = 1;

        @BeforeEach
        void setUp() {
            SecurityContextHolder.setContext(new SecurityContextImpl(new TestingAuthenticationToken("user", null)));
        }

        @AfterEach
        void tearDown() {
            SecurityContextHolder.clearContext();
        }

        @Test
        @DisplayName("Should successfully read secret value and log audit")
        void shouldSuccessfullyReadSecretValue() {
            // Arrange
            SecretDataResponse expectedResponse = SecretDataResponse.builder()
                    .dataVersion(DATA_VERSION)
                    .publicPart("public-data")
                    .privatePart(SECRET_VALUE)
                    .lastRotated(Instant.now())
                    .creationTime(Instant.now())
                    .updatedTime(Instant.now())
                    .createdBy("test-user")
                    .updatedBy("test-user")
                    .state("ACTIVE")
                    .build();

            when(secretService.readSecretValue(PROJECT_ID, SECRET_NAME))
                    .thenReturn(expectedResponse);

            // Act
            var result = secretController.readSecretValue(PROJECT_ID, SECRET_NAME);

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.getData()).isEqualTo(expectedResponse);
            assertThat(result.getMessage()).isEqualTo("Successfully read secret value.");

            // Verify audit logging
            ArgumentCaptor<AuditEntry> auditEntryArgumentCaptor = ArgumentCaptor.captor();
            verify(asyncAuditLogger).log(auditEntryArgumentCaptor.capture());

            AuditEntry capturedMetadata = auditEntryArgumentCaptor.getValue();
            assertThat(capturedMetadata.getMetadata().get("result")).contains("privatePart");
            // The privatePart should be masked in the audit log
            assertThat(capturedMetadata.getMetadata().get("result")).doesNotContain(SECRET_VALUE);
        }

    }
}