package com.flipkart.grayskull.spimpl.repositories;

import com.flipkart.grayskull.entities.AuditEntryEntity;
import com.flipkart.grayskull.mappers.AuditEntryMapper;
import com.flipkart.grayskull.spi.models.AuditEntry;
import com.flipkart.grayskull.spimpl.repositories.mongo.AuditEntryMongoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("AuditEntryRepositoryImpl Unit Tests")
class AuditEntryRepositoryImplTest {

    private AuditEntryMongoRepository mongoRepository;
    private AuditEntryMapper auditEntryMapper;
    private AuditEntryRepositoryImpl repository;

    @BeforeEach
    void setUp() {
        mongoRepository = mock(AuditEntryMongoRepository.class);
        auditEntryMapper = mock(AuditEntryMapper.class);
        repository = new AuditEntryRepositoryImpl(mongoRepository, auditEntryMapper);
    }

    @Nested
    @DisplayName("save Tests")
    class SaveTests {

        @Test
        @DisplayName("should map and save audit entry")
        void shouldMapAndSaveAuditEntry() {
            AuditEntry auditEntry = new AuditEntry();
            auditEntry.setId("audit-1");
            auditEntry.setProjectId("project-123");
            
            AuditEntryEntity entity = new AuditEntryEntity();
            entity.setId("audit-1");
            entity.setProjectId("project-123");
            
            when(auditEntryMapper.toEntity(auditEntry)).thenReturn(entity);
            when(mongoRepository.save(entity)).thenReturn(entity);

            AuditEntry result = repository.save(auditEntry);

            verify(auditEntryMapper).toEntity(auditEntry);
            verify(mongoRepository).save(entity);
            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo("audit-1");
            assertThat(result.getProjectId()).isEqualTo("project-123");
        }

        @Test
        @DisplayName("should return saved entity from mongo repository")
        void shouldReturnSavedEntityFromMongoRepository() {
            AuditEntry auditEntry = new AuditEntry();
            AuditEntryEntity entity = new AuditEntryEntity();
            entity.setId("saved-id");
            
            when(auditEntryMapper.toEntity(auditEntry)).thenReturn(entity);
            when(mongoRepository.save(entity)).thenReturn(entity);

            AuditEntry result = repository.save(auditEntry);

            assertThat(result).isSameAs(entity);
        }
    }

    @Nested
    @DisplayName("saveAll Tests")
    class SaveAllTests {

        @Test
        @DisplayName("should map and save all audit entries")
        void shouldMapAndSaveAllAuditEntries() {
            AuditEntry entry1 = new AuditEntry();
            entry1.setId("audit-1");
            
            AuditEntry entry2 = new AuditEntry();
            entry2.setId("audit-2");
            
            List<AuditEntry> entries = List.of(entry1, entry2);
            
            AuditEntryEntity entity1 = new AuditEntryEntity();
            entity1.setId("audit-1");
            
            AuditEntryEntity entity2 = new AuditEntryEntity();
            entity2.setId("audit-2");
            
            when(auditEntryMapper.toEntity(entry1)).thenReturn(entity1);
            when(auditEntryMapper.toEntity(entry2)).thenReturn(entity2);
            when(mongoRepository.saveAll(anyIterable())).thenReturn(List.of(entity1, entity2));

            List<AuditEntry> results = repository.saveAll(entries);

            verify(mongoRepository).saveAll(anyIterable());
            assertThat(results).hasSize(2);
            assertThat(results.get(0).getId()).isEqualTo("audit-1");
            assertThat(results.get(1).getId()).isEqualTo("audit-2");
        }

        @Test
        @DisplayName("should handle empty list")
        void shouldHandleEmptyList() {
            List<AuditEntry> entries = List.of();
            
            when(mongoRepository.saveAll(anyIterable())).thenReturn(List.of());

            List<AuditEntry> results = repository.saveAll(entries);

            verify(mongoRepository).saveAll(anyIterable());
            assertThat(results).isEmpty();
        }

        @Test
        @DisplayName("should handle single entry")
        void shouldHandleSingleEntry() {
            AuditEntry entry = new AuditEntry();
            entry.setId("single");
            
            AuditEntryEntity entity = new AuditEntryEntity();
            entity.setId("single");
            
            when(auditEntryMapper.toEntity(entry)).thenReturn(entity);
            when(mongoRepository.saveAll(anyIterable())).thenReturn(List.of(entity));

            List<AuditEntry> results = repository.saveAll(List.of(entry));

            assertThat(results).hasSize(1);
            assertThat(results.get(0).getId()).isEqualTo("single");
        }
    }

    @Nested
    @DisplayName("findByFilters Tests")
    class FindByFiltersTests {

        @Test
        @DisplayName("should delegate to mongo repository with all parameters")
        void shouldDelegateToMongoRepositoryWithAllParameters() {
            Date timestamp = Date.from(Instant.now());
            
            AuditEntryEntity entity1 = new AuditEntryEntity();
            entity1.setId("audit-1");
            
            AuditEntryEntity entity2 = new AuditEntryEntity();
            entity2.setId("audit-2");
            
            when(mongoRepository.findByFilters(
                    Optional.of("project-123"),
                    Optional.of("secret-1"),
                    Optional.of("SECRET"),
                    Optional.of("READ"),
                    Optional.of("SERVICE"),
                    Optional.of(timestamp),
                    10,
                    20
            )).thenReturn(List.of(entity1, entity2));

            List<AuditEntry> results = repository.findByFilters(
                    Optional.of("project-123"),
                    Optional.of("secret-1"),
                    Optional.of("SECRET"),
                    Optional.of("READ"),
                    Optional.of("SERVICE"),
                    Optional.of(timestamp),
                    10,
                    20
            );

            verify(mongoRepository).findByFilters(
                    Optional.of("project-123"),
                    Optional.of("secret-1"),
                    Optional.of("SECRET"),
                    Optional.of("READ"),
                    Optional.of("SERVICE"),
                    Optional.of(timestamp),
                    10,
                    20
            );
            assertThat(results).hasSize(2);
            assertThat(results.get(0).getId()).isEqualTo("audit-1");
            assertThat(results.get(1).getId()).isEqualTo("audit-2");
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("filterParameterProvider")
        @DisplayName("should delegate with various filter combinations")
        void shouldDelegateWithVariousFilterCombinations(
                String testName,
                Optional<String> projectId,
                Optional<String> resourceName,
                Optional<String> resourceType,
                Optional<String> action,
                Optional<String> userType,
                Optional<Date> afterTimestamp,
                int offset,
                int limit) {
            
            when(mongoRepository.findByFilters(
                    projectId, resourceName, resourceType, action, userType, afterTimestamp, offset, limit
            )).thenReturn(List.of());

            List<AuditEntry> results = repository.findByFilters(
                    projectId, resourceName, resourceType, action, userType, afterTimestamp, offset, limit
            );

            verify(mongoRepository).findByFilters(
                    projectId, resourceName, resourceType, action, userType, afterTimestamp, offset, limit
            );
            assertThat(results).isEmpty();
        }

        static Stream<Arguments> filterParameterProvider() {
            Date timestamp = Date.from(Instant.now());
            
            return Stream.of(
                    Arguments.of(
                            "no filters",
                            Optional.empty(), Optional.empty(), Optional.empty(),
                            Optional.empty(), Optional.empty(), Optional.empty(),
                            0, 10
                    ),
                    Arguments.of(
                            "only projectId",
                            Optional.of("project-123"), Optional.empty(), Optional.empty(),
                            Optional.empty(), Optional.empty(), Optional.empty(),
                            0, 10
                    ),
                    Arguments.of(
                            "projectId and timestamp",
                            Optional.of("project-456"), Optional.empty(), Optional.empty(),
                            Optional.empty(), Optional.empty(), Optional.of(timestamp),
                            5, 15
                    ),
                    Arguments.of(
                            "all filters with custom pagination",
                            Optional.of("project-789"), Optional.of("resource"), Optional.of("TYPE"),
                            Optional.of("UPDATE"), Optional.of("HUMAN"), Optional.of(timestamp),
                            20, 50
                    )
            );
        }

        @Test
        @DisplayName("should return empty list when mongo repository returns empty")
        void shouldReturnEmptyListWhenMongoReturnsEmpty() {
            when(mongoRepository.findByFilters(
                    any(), any(), any(), any(), any(), any(), anyInt(), anyInt()
            )).thenReturn(List.of());

            List<AuditEntry> results = repository.findByFilters(
                    Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(),
                    0, 10
            );

            assertThat(results).isEmpty();
        }

        @Test
        @DisplayName("should cast entities to AuditEntry interface")
        void shouldCastEntitiesToAuditEntryInterface() {
            AuditEntryEntity entity = new AuditEntryEntity();
            entity.setId("test-id");
            entity.setProjectId("project-id");
            
            when(mongoRepository.findByFilters(
                    any(), any(), any(), any(), any(), any(), anyInt(), anyInt()
            )).thenReturn(List.of(entity));

            List<AuditEntry> results = repository.findByFilters(
                    Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(),
                    0, 10
            );

            assertThat(results).hasSize(1);
            AuditEntry result = results.get(0);
            assertThat(result).isInstanceOf(AuditEntry.class);
            assertThat(result).isInstanceOf(AuditEntryEntity.class);
            assertThat(result.getId()).isEqualTo("test-id");
        }
    }

    @Nested
    @DisplayName("countByFilters Tests")
    class CountByFiltersTests {

        @Test
        @DisplayName("should delegate to mongo repository with all parameters")
        void shouldDelegateToMongoRepositoryWithAllParameters() {
            Date timestamp = Date.from(Instant.now());
            
            when(mongoRepository.countByFilters(
                    Optional.of("project-123"),
                    Optional.of("secret-1"),
                    Optional.of("SECRET"),
                    Optional.of("READ"),
                    Optional.of("SERVICE"),
                    Optional.of(timestamp)
            )).thenReturn(42L);

            long count = repository.countByFilters(
                    Optional.of("project-123"),
                    Optional.of("secret-1"),
                    Optional.of("SECRET"),
                    Optional.of("READ"),
                    Optional.of("SERVICE"),
                    Optional.of(timestamp)
            );

            verify(mongoRepository).countByFilters(
                    Optional.of("project-123"),
                    Optional.of("secret-1"),
                    Optional.of("SECRET"),
                    Optional.of("READ"),
                    Optional.of("SERVICE"),
                    Optional.of(timestamp)
            );
            assertThat(count).isEqualTo(42L);
        }

        @ParameterizedTest(name = "{0} - expected: {7}")
        @MethodSource("countParameterProvider")
        @DisplayName("should delegate count with various filter combinations")
        void shouldDelegateCountWithVariousFilterCombinations(
                String testName,
                Optional<String> projectId,
                Optional<String> resourceName,
                Optional<String> resourceType,
                Optional<String> action,
                Optional<String> userType,
                Optional<Date> afterTimestamp,
                long expectedCount) {
            
            when(mongoRepository.countByFilters(
                    projectId, resourceName, resourceType, action, userType, afterTimestamp
            )).thenReturn(expectedCount);

            long count = repository.countByFilters(
                    projectId, resourceName, resourceType, action, userType, afterTimestamp
            );

            verify(mongoRepository).countByFilters(
                    projectId, resourceName, resourceType, action, userType, afterTimestamp
            );
            assertThat(count).isEqualTo(expectedCount);
        }

        static Stream<Arguments> countParameterProvider() {
            Date timestamp = Date.from(Instant.now());
            
            return Stream.of(
                    Arguments.of(
                            "no filters",
                            Optional.empty(), Optional.empty(), Optional.empty(),
                            Optional.empty(), Optional.empty(), Optional.empty(),
                            100L
                    ),
                    Arguments.of(
                            "only projectId",
                            Optional.of("project-123"), Optional.empty(), Optional.empty(),
                            Optional.empty(), Optional.empty(), Optional.empty(),
                            25L
                    ),
                    Arguments.of(
                            "projectId and userType",
                            Optional.of("project-456"), Optional.empty(), Optional.empty(),
                            Optional.empty(), Optional.of("HUMAN"), Optional.empty(),
                            10L
                    ),
                    Arguments.of(
                            "all filters",
                            Optional.of("project-789"), Optional.of("resource"), Optional.of("TYPE"),
                            Optional.of("DELETE"), Optional.of("SERVICE"), Optional.of(timestamp),
                            5L
                    ),
                    Arguments.of(
                            "zero results",
                            Optional.of("non-existent"), Optional.empty(), Optional.empty(),
                            Optional.empty(), Optional.empty(), Optional.empty(),
                            0L
                    )
            );
        }

        @Test
        @DisplayName("should return zero when mongo repository returns zero")
        void shouldReturnZeroWhenMongoReturnsZero() {
            when(mongoRepository.countByFilters(
                    any(), any(), any(), any(), any(), any()
            )).thenReturn(0L);

            long count = repository.countByFilters(
                    Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty()
            );

            assertThat(count).isEqualTo(0L);
        }

        @Test
        @DisplayName("should handle large counts")
        void shouldHandleLargeCounts() {
            when(mongoRepository.countByFilters(
                    any(), any(), any(), any(), any(), any()
            )).thenReturn(1_000_000L);

            long count = repository.countByFilters(
                    Optional.of("large-project"), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty()
            );

            assertThat(count).isEqualTo(1_000_000L);
        }
    }
}
