package com.flipkart.grayskull.controllers;

import com.flipkart.grayskull.models.dto.response.AuditEntriesResponse;
import com.flipkart.grayskull.models.dto.response.ResponseTemplate;
import com.flipkart.grayskull.service.interfaces.AuditService;
import com.flipkart.grayskull.spi.models.AuditEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("AuditController Unit Tests")
class AuditControllerTest {

    private static final String PROJECT_ID = "test-project";
    private static final String RESOURCE_NAME = "test-secret";
    private static final String RESOURCE_TYPE = "SECRET";
    private static final String ACTION = "CREATE_SECRET";

    private final AuditService auditService = mock(AuditService.class);
    private AuditController auditController;

    @BeforeEach
    void setUp() {
        auditController = new AuditController(auditService);
    }

    @Nested
    @DisplayName("getProjectAudits Tests")
    class GetProjectAuditsTests {

        @Test
        @DisplayName("Should retrieve audit entries with all filters")
        void shouldRetrieveAuditEntriesWithAllFilters() {
            // Arrange
            List<AuditEntry> entries = List.of(
                    createAuditEntry("entry1", 1),
                    createAuditEntry("entry2", 2)
            );
            AuditEntriesResponse expectedResponse = new AuditEntriesResponse(entries, 2L);

            when(auditService.getAuditEntries(Optional.of(PROJECT_ID), Optional.of(RESOURCE_NAME), Optional.of(RESOURCE_TYPE), Optional.of(ACTION), 0, 10))
                     .thenReturn(expectedResponse);

            // Act
            ResponseTemplate<AuditEntriesResponse> result = auditController.getProjectAudits(
                    PROJECT_ID, RESOURCE_NAME, RESOURCE_TYPE, ACTION, 0, 10
            );

            // Assert
            assertThat(result.getData()).isEqualTo(expectedResponse);
            assertThat(result.getData().getEntries()).hasSize(2);
            assertThat(result.getData().getTotal()).isEqualTo(2L);
            assertThat(result.getMessage()).isEqualTo("Successfully retrieved audit entries.");

            verify(auditService).getAuditEntries(Optional.of(PROJECT_ID), Optional.of(RESOURCE_NAME), Optional.of(RESOURCE_TYPE), Optional.of(ACTION), 0, 10);
        }

        @Test
        @DisplayName("Should retrieve audit entries with only projectId filter")
        void shouldRetrieveAuditEntriesWithOnlyProjectId() {
            // Arrange
            List<AuditEntry> entries = List.of(
                    createAuditEntry("entry1", 1),
                    createAuditEntry("entry2", 2),
                    createAuditEntry("entry3", 3)
            );
            AuditEntriesResponse expectedResponse = new AuditEntriesResponse(entries, 3L);

            when(auditService.getAuditEntries(Optional.of(PROJECT_ID), Optional.empty(), Optional.empty(), Optional.empty(), 0, 10))
                    .thenReturn(expectedResponse);

            // Act
            ResponseTemplate<AuditEntriesResponse> result = auditController.getProjectAudits(
                    PROJECT_ID, null, null, null, 0, 10
            );

            // Assert
            assertThat(result.getData()).isEqualTo(expectedResponse);
            assertThat(result.getData().getEntries()).hasSize(3);
            assertThat(result.getData().getTotal()).isEqualTo(3L);

            verify(auditService).getAuditEntries(Optional.of(PROJECT_ID), Optional.empty(), Optional.empty(), Optional.empty(), 0, 10);
        }

        @Test
        @DisplayName("Should retrieve audit entries with partial filters")
        void shouldRetrieveAuditEntriesWithPartialFilters() {
            // Arrange
            List<AuditEntry> entries = List.of(createAuditEntry("entry1", 1));
            AuditEntriesResponse expectedResponse = new AuditEntriesResponse(entries, 1L);

            when(auditService.getAuditEntries(Optional.of(PROJECT_ID), Optional.of(RESOURCE_NAME), Optional.empty(), Optional.of(ACTION), 0, 10))
                    .thenReturn(expectedResponse);

            // Act
            ResponseTemplate<AuditEntriesResponse> result = auditController.getProjectAudits(
                    PROJECT_ID, RESOURCE_NAME, null, ACTION, 0, 10
            );

            // Assert
            assertThat(result.getData()).isEqualTo(expectedResponse);
            assertThat(result.getData().getEntries()).hasSize(1);

            verify(auditService).getAuditEntries(Optional.of(PROJECT_ID), Optional.of(RESOURCE_NAME), Optional.empty(), Optional.of(ACTION), 0, 10);
        }

        @Test
        @DisplayName("Should return empty list when no audit entries found")
        void shouldReturnEmptyListWhenNoAuditEntriesFound() {
            // Arrange
            AuditEntriesResponse expectedResponse = new AuditEntriesResponse(Collections.emptyList(), 0L);

            when(auditService.getAuditEntries(Optional.of(PROJECT_ID), Optional.of(RESOURCE_NAME), Optional.of(RESOURCE_TYPE), Optional.of(ACTION), 0, 10))
                    .thenReturn(expectedResponse);

            // Act
            ResponseTemplate<AuditEntriesResponse> result = auditController.getProjectAudits(
                    PROJECT_ID, RESOURCE_NAME, RESOURCE_TYPE, ACTION, 0, 10
            );

            // Assert
            assertThat(result.getData().getEntries()).isEmpty();
            assertThat(result.getData().getTotal()).isZero();

            verify(auditService).getAuditEntries(Optional.of(PROJECT_ID), Optional.of(RESOURCE_NAME), Optional.of(RESOURCE_TYPE), Optional.of(ACTION), 0, 10);
        }

        @Test
        @DisplayName("Should handle pagination with custom offset and limit")
        void shouldHandlePaginationWithCustomOffsetAndLimit() {
            // Arrange
            List<AuditEntry> entries = List.of(
                    createAuditEntry("entry6", 6),
                    createAuditEntry("entry7", 7),
                    createAuditEntry("entry8", 8)
            );
            AuditEntriesResponse expectedResponse = new AuditEntriesResponse(entries, 50L);

            when(auditService.getAuditEntries(Optional.of(PROJECT_ID), Optional.empty(), Optional.empty(), Optional.empty(), 5, 3))
                    .thenReturn(expectedResponse);

            // Act
            ResponseTemplate<AuditEntriesResponse> result = auditController.getProjectAudits(
                    PROJECT_ID, null, null, null, 5, 3
            );

            // Assert
            assertThat(result.getData().getEntries()).hasSize(3);
            assertThat(result.getData().getTotal()).isEqualTo(50L);

            verify(auditService).getAuditEntries(Optional.of(PROJECT_ID), Optional.empty(), Optional.empty(), Optional.empty(), 5, 3);
        }

        @Test
        @DisplayName("Should use default pagination values when not provided")
        void shouldUseDefaultPaginationValues() {
            // Arrange
            List<AuditEntry> entries = List.of(createAuditEntry("entry1", 1));
            AuditEntriesResponse expectedResponse = new AuditEntriesResponse(entries, 1L);

            when(auditService.getAuditEntries(Optional.of(PROJECT_ID), Optional.empty(), Optional.empty(), Optional.empty(), 0, 10))
                    .thenReturn(expectedResponse);

            // Act - Using default offset=0, limit=10
            ResponseTemplate<AuditEntriesResponse> result = auditController.getProjectAudits(
                    PROJECT_ID, null, null, null, 0, 10
            );

            // Assert
            assertThat(result.getData()).isEqualTo(expectedResponse);
            verify(auditService).getAuditEntries(Optional.of(PROJECT_ID), Optional.empty(), Optional.empty(), Optional.empty(), 0, 10);
        }

        @Test
        @DisplayName("Should handle maximum allowed limit")
        void shouldHandleMaximumAllowedLimit() {
            // Arrange
            List<AuditEntry> entries = List.of(createAuditEntry("entry1", 1));
            AuditEntriesResponse expectedResponse = new AuditEntriesResponse(entries, 1L);

            when(auditService.getAuditEntries(Optional.of(PROJECT_ID), Optional.empty(), Optional.empty(), Optional.empty(), 0, 100))
                    .thenReturn(expectedResponse);

            // Act - Using maximum limit of 100
            ResponseTemplate<AuditEntriesResponse> result = auditController.getProjectAudits(
                    PROJECT_ID, null, null, null, 0, 100
            );

            // Assert
            assertThat(result.getData()).isEqualTo(expectedResponse);
            verify(auditService).getAuditEntries(Optional.of(PROJECT_ID), Optional.empty(), Optional.empty(), Optional.empty(), 0, 100);
        }

        @Test
        @DisplayName("Should handle large offset for pagination")
        void shouldHandleLargeOffsetForPagination() {
            // Arrange
            AuditEntriesResponse expectedResponse = new AuditEntriesResponse(Collections.emptyList(), 1000L);

            when(auditService.getAuditEntries(Optional.of(PROJECT_ID), Optional.empty(), Optional.empty(), Optional.empty(), 990, 10))
                    .thenReturn(expectedResponse);

            // Act
            ResponseTemplate<AuditEntriesResponse> result = auditController.getProjectAudits(
                    PROJECT_ID, null, null, null, 990, 10
            );

            // Assert
            assertThat(result.getData().getEntries()).isEmpty();
            assertThat(result.getData().getTotal()).isEqualTo(1000L);
            verify(auditService).getAuditEntries(Optional.of(PROJECT_ID), Optional.empty(), Optional.empty(), Optional.empty(), 990, 10);
        }
    }

    /**
     * Helper method to create test audit entries
     */
    private AuditEntry createAuditEntry(String id, int version) {
        return new AuditEntry(
                id,
                PROJECT_ID,
                RESOURCE_TYPE,
                RESOURCE_NAME,
                version,
                ACTION,
                "test-user",
                "test-actor",
                Map.of("ip", "127.0.0.1"),
                Instant.now(),
                Map.of("key", "value")
        );
    }
}

