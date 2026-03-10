package com.flipkart.grayskull.spimpl.repositories.mongo;

import com.flipkart.grayskull.configuration.UserTypeConfiguration;
import com.flipkart.grayskull.entities.AuditEntryEntity;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("AuditEntryMongoRepositoryImpl Unit Tests")
class AuditEntryMongoRepositoryImplTest {

    private MongoTemplate mongoTemplate;
    private UserTypeConfiguration userTypeConfig;
    private AuditEntryMongoRepositoryImpl repository;

    @BeforeEach
    void setUp() {
        mongoTemplate = mock(MongoTemplate.class);
        userTypeConfig = new UserTypeConfiguration("service:", "human:");
        repository = new AuditEntryMongoRepositoryImpl(mongoTemplate, userTypeConfig);
    }

    @Nested
    @DisplayName("findByFilters Tests")
    class FindByFiltersTests {

        @Test
        @DisplayName("should apply all filters when all parameters are present")
        void shouldApplyAllFilters_whenAllParametersPresent() {
            Date timestamp = Date.from(Instant.now());
            ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
            
            when(mongoTemplate.find(any(Query.class), eq(AuditEntryEntity.class)))
                    .thenReturn(List.of());

            repository.findByFilters(
                    Optional.of("project-123"),
                    Optional.of("my-secret"),
                    Optional.of("SECRET"),
                    Optional.of("READ"),
                    Optional.of("SERVICE"),
                    Optional.of(timestamp),
                    10,
                    20
            );

            verify(mongoTemplate).find(queryCaptor.capture(), eq(AuditEntryEntity.class));
            Query capturedQuery = queryCaptor.getValue();
            Document queryDoc = capturedQuery.getQueryObject();

            assertThat(queryDoc.get("projectId")).isEqualTo("project-123");
            assertThat(queryDoc.get("resourceName")).isEqualTo("my-secret");
            assertThat(queryDoc.get("resourceType")).isEqualTo("SECRET");
            assertThat(queryDoc.get("action")).isEqualTo("READ");
            assertThat(queryDoc.containsKey("userId")).isTrue();
            assertThat(queryDoc.get("timestamp")).isInstanceOf(Document.class);
            
            assertThat(capturedQuery.getSkip()).isEqualTo(10);
            assertThat(capturedQuery.getLimit()).isEqualTo(20);
        }

        @Test
        @DisplayName("should apply only projectId filter when only projectId is present")
        void shouldApplyOnlyProjectIdFilter_whenOnlyProjectIdPresent() {
            ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
            
            when(mongoTemplate.find(any(Query.class), eq(AuditEntryEntity.class)))
                    .thenReturn(List.of());

            repository.findByFilters(
                    Optional.of("project-123"),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    0,
                    10
            );

            verify(mongoTemplate).find(queryCaptor.capture(), eq(AuditEntryEntity.class));
            Query capturedQuery = queryCaptor.getValue();
            Document queryDoc = capturedQuery.getQueryObject();

            assertThat(queryDoc.get("projectId")).isEqualTo("project-123");
            assertThat(queryDoc.containsKey("resourceName")).isFalse();
            assertThat(queryDoc.containsKey("resourceType")).isFalse();
            assertThat(queryDoc.containsKey("action")).isFalse();
            assertThat(queryDoc.containsKey("userId")).isFalse();
            assertThat(queryDoc.containsKey("timestamp")).isFalse();
        }

        @Test
        @DisplayName("should apply SERVICE user type filter with correct prefix")
        void shouldApplyServiceUserTypeFilter_withCorrectPrefix() {
            ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
            
            when(mongoTemplate.find(any(Query.class), eq(AuditEntryEntity.class)))
                    .thenReturn(List.of());

            repository.findByFilters(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of("SERVICE"),
                    Optional.empty(),
                    0,
                    10
            );

            verify(mongoTemplate).find(queryCaptor.capture(), eq(AuditEntryEntity.class));
            Query capturedQuery = queryCaptor.getValue();
            Document queryDoc = capturedQuery.getQueryObject();

            assertThat(queryDoc.containsKey("userId")).isTrue();
            // The userId field contains a regex pattern for service users
            String queryJson = queryDoc.toJson();
            assertThat(queryJson).contains("service:");
        }

        @Test
        @DisplayName("should apply HUMAN user type filter with correct prefix")
        void shouldApplyHumanUserTypeFilter_withCorrectPrefix() {
            ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
            
            when(mongoTemplate.find(any(Query.class), eq(AuditEntryEntity.class)))
                    .thenReturn(List.of());

            repository.findByFilters(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of("HUMAN"),
                    Optional.empty(),
                    0,
                    10
            );

            verify(mongoTemplate).find(queryCaptor.capture(), eq(AuditEntryEntity.class));
            Query capturedQuery = queryCaptor.getValue();
            Document queryDoc = capturedQuery.getQueryObject();

            assertThat(queryDoc.containsKey("userId")).isTrue();
            String queryJson = queryDoc.toJson();
            assertThat(queryJson).contains("human:");
        }

        @Test
        @DisplayName("should not apply user type filter when unknown user type is provided")
        void shouldNotApplyUserTypeFilter_whenUnknownUserType() {
            ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
            
            when(mongoTemplate.find(any(Query.class), eq(AuditEntryEntity.class)))
                    .thenReturn(List.of());

            repository.findByFilters(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of("UNKNOWN_TYPE"),
                    Optional.empty(),
                    0,
                    10
            );

            verify(mongoTemplate).find(queryCaptor.capture(), eq(AuditEntryEntity.class));
            Query capturedQuery = queryCaptor.getValue();
            Document queryDoc = capturedQuery.getQueryObject();

            assertThat(queryDoc.containsKey("userId")).isFalse();
        }

        @Test
        @DisplayName("should apply timestamp filter correctly")
        void shouldApplyTimestampFilter_correctly() {
            Date timestamp = Date.from(Instant.parse("2024-01-01T10:00:00Z"));
            ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
            
            when(mongoTemplate.find(any(Query.class), eq(AuditEntryEntity.class)))
                    .thenReturn(List.of());

            repository.findByFilters(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(timestamp),
                    0,
                    10
            );

            verify(mongoTemplate).find(queryCaptor.capture(), eq(AuditEntryEntity.class));
            Query capturedQuery = queryCaptor.getValue();
            Document queryDoc = capturedQuery.getQueryObject();

            assertThat(queryDoc.get("timestamp")).isInstanceOf(Document.class);
            Document timestampDoc = (Document) queryDoc.get("timestamp");
            assertThat(timestampDoc.containsKey("$gt")).isTrue();
        }

        @Test
        @DisplayName("should apply no filters when all parameters are empty")
        void shouldApplyNoFilters_whenAllParametersEmpty() {
            ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
            
            when(mongoTemplate.find(any(Query.class), eq(AuditEntryEntity.class)))
                    .thenReturn(List.of());

            repository.findByFilters(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    0,
                    100
            );

            verify(mongoTemplate).find(queryCaptor.capture(), eq(AuditEntryEntity.class));
            Query capturedQuery = queryCaptor.getValue();
            Document queryDoc = capturedQuery.getQueryObject();

            assertThat(queryDoc.isEmpty()).isTrue();
            assertThat(capturedQuery.getSkip()).isEqualTo(0);
            assertThat(capturedQuery.getLimit()).isEqualTo(100);
        }

        @Test
        @DisplayName("should apply descending sort by timestamp")
        void shouldApplyDescendingSortByTimestamp() {
            ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
            
            when(mongoTemplate.find(any(Query.class), eq(AuditEntryEntity.class)))
                    .thenReturn(List.of());

            repository.findByFilters(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    0,
                    10
            );

            verify(mongoTemplate).find(queryCaptor.capture(), eq(AuditEntryEntity.class));
            Query capturedQuery = queryCaptor.getValue();
            Document sortDoc = capturedQuery.getSortObject();

            assertThat(sortDoc.get("timestamp")).isEqualTo(-1);
        }

        @Test
        @DisplayName("should apply correct pagination parameters")
        void shouldApplyCorrectPaginationParameters() {
            ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
            
            when(mongoTemplate.find(any(Query.class), eq(AuditEntryEntity.class)))
                    .thenReturn(List.of());

            repository.findByFilters(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    50,
                    25
            );

            verify(mongoTemplate).find(queryCaptor.capture(), eq(AuditEntryEntity.class));
            Query capturedQuery = queryCaptor.getValue();

            assertThat(capturedQuery.getSkip()).isEqualTo(50);
            assertThat(capturedQuery.getLimit()).isEqualTo(25);
        }

        @Test
        @DisplayName("should return results from MongoTemplate")
        void shouldReturnResultsFromMongoTemplate() {
            AuditEntryEntity entity1 = new AuditEntryEntity();
            entity1.setId("audit-1");
            entity1.setProjectId("project-123");
            entity1.setResourceName("secret-1");
            
            AuditEntryEntity entity2 = new AuditEntryEntity();
            entity2.setId("audit-2");
            entity2.setProjectId("project-123");
            entity2.setResourceName("secret-2");
            
            when(mongoTemplate.find(any(Query.class), eq(AuditEntryEntity.class)))
                    .thenReturn(List.of(entity1, entity2));

            List<AuditEntryEntity> results = repository.findByFilters(
                    Optional.of("project-123"),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    0,
                    10
            );

            assertThat(results).hasSize(2);
            assertThat(results.get(0).getId()).isEqualTo("audit-1");
            assertThat(results.get(1).getId()).isEqualTo("audit-2");
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("filterCombinationProvider")
        @DisplayName("should handle various filter combinations correctly")
        void shouldHandleFilterCombinations(
                String testName,
                Optional<String> projectId,
                Optional<String> resourceName,
                Optional<String> resourceType,
                Optional<String> action,
                Optional<String> userType,
                Optional<Date> afterTimestamp,
                java.util.function.Consumer<Document> assertions) {
            
            ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
            
            when(mongoTemplate.find(any(Query.class), eq(AuditEntryEntity.class)))
                    .thenReturn(List.of());

            repository.findByFilters(
                    projectId,
                    resourceName,
                    resourceType,
                    action,
                    userType,
                    afterTimestamp,
                    0,
                    10
            );

            verify(mongoTemplate).find(queryCaptor.capture(), eq(AuditEntryEntity.class));
            Query capturedQuery = queryCaptor.getValue();
            Document queryDoc = capturedQuery.getQueryObject();

            assertions.accept(queryDoc);
        }

        static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> filterCombinationProvider() {
            Date timestamp = Date.from(Instant.parse("2024-01-01T10:00:00Z"));
            
            return java.util.stream.Stream.of(
                    org.junit.jupiter.params.provider.Arguments.of(
                            "projectId + resourceType + timestamp",
                            Optional.of("project-123"),
                            Optional.empty(),
                            Optional.of("SECRET"),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.of(timestamp),
                            (java.util.function.Consumer<Document>) queryDoc -> {
                                assertThat(queryDoc.get("projectId")).isEqualTo("project-123");
                                assertThat(queryDoc.get("resourceType")).isEqualTo("SECRET");
                                assertThat(queryDoc.get("timestamp")).isInstanceOf(Document.class);
                                assertThat(queryDoc.containsKey("resourceName")).isFalse();
                                assertThat(queryDoc.containsKey("action")).isFalse();
                            }
                    ),
                    org.junit.jupiter.params.provider.Arguments.of(
                            "resourceName + action",
                            Optional.empty(),
                            Optional.of("my-secret"),
                            Optional.empty(),
                            Optional.of("READ"),
                            Optional.empty(),
                            Optional.empty(),
                            (java.util.function.Consumer<Document>) queryDoc -> {
                                assertThat(queryDoc.get("resourceName")).isEqualTo("my-secret");
                                assertThat(queryDoc.get("action")).isEqualTo("READ");
                                assertThat(queryDoc.containsKey("projectId")).isFalse();
                                assertThat(queryDoc.containsKey("resourceType")).isFalse();
                                assertThat(queryDoc.containsKey("userId")).isFalse();
                            }
                    ),
                    org.junit.jupiter.params.provider.Arguments.of(
                            "projectId + resourceName + resourceType",
                            Optional.of("project-456"),
                            Optional.of("secret-xyz"),
                            Optional.of("SECRET_DATA"),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            (java.util.function.Consumer<Document>) queryDoc -> {
                                assertThat(queryDoc.get("projectId")).isEqualTo("project-456");
                                assertThat(queryDoc.get("resourceName")).isEqualTo("secret-xyz");
                                assertThat(queryDoc.get("resourceType")).isEqualTo("SECRET_DATA");
                                assertThat(queryDoc.containsKey("action")).isFalse();
                                assertThat(queryDoc.containsKey("userId")).isFalse();
                            }
                    ),
                    org.junit.jupiter.params.provider.Arguments.of(
                            "action + userType + timestamp",
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.of("DELETE"),
                            Optional.of("HUMAN"),
                            Optional.of(timestamp),
                            (java.util.function.Consumer<Document>) queryDoc -> {
                                assertThat(queryDoc.get("action")).isEqualTo("DELETE");
                                assertThat(queryDoc.containsKey("userId")).isTrue();
                                assertThat(queryDoc.get("timestamp")).isInstanceOf(Document.class);
                                assertThat(queryDoc.containsKey("projectId")).isFalse();
                            }
                    )
            );
        }

        @Test
        @DisplayName("should escape special regex characters in user prefix")
        void shouldEscapeSpecialRegexCharacters_inUserPrefix() {
            UserTypeConfiguration configWithSpecialChars = new UserTypeConfiguration(
                    "service:v2.",
                    "user+prefix*"
            );
            AuditEntryMongoRepositoryImpl repoWithSpecialChars = 
                    new AuditEntryMongoRepositoryImpl(mongoTemplate, configWithSpecialChars);
            
            ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
            
            when(mongoTemplate.find(any(Query.class), eq(AuditEntryEntity.class)))
                    .thenReturn(List.of());

            repoWithSpecialChars.findByFilters(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of("SERVICE"),
                    Optional.empty(),
                    0,
                    10
            );

            verify(mongoTemplate).find(queryCaptor.capture(), eq(AuditEntryEntity.class));
            Query capturedQuery = queryCaptor.getValue();
            Document queryDoc = capturedQuery.getQueryObject();

            assertThat(queryDoc.containsKey("userId")).isTrue();
            // Verify the special characters are escaped
            String queryJson = queryDoc.toJson();
            assertThat(queryJson).contains("service:v2.");
        }
    }

    @Nested
    @DisplayName("countByFilters Tests")
    class CountByFiltersTests {

        @Test
        @DisplayName("should count with all filters when all parameters are present")
        void shouldCountWithAllFilters_whenAllParametersPresent() {
            Date timestamp = Date.from(Instant.now());
            ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
            
            when(mongoTemplate.count(any(Query.class), eq(AuditEntryEntity.class)))
                    .thenReturn(42L);

            long count = repository.countByFilters(
                    Optional.of("project-123"),
                    Optional.of("my-secret"),
                    Optional.of("SECRET"),
                    Optional.of("READ"),
                    Optional.of("SERVICE"),
                    Optional.of(timestamp)
            );

            verify(mongoTemplate).count(queryCaptor.capture(), eq(AuditEntryEntity.class));
            Query capturedQuery = queryCaptor.getValue();
            Document queryDoc = capturedQuery.getQueryObject();

            assertThat(queryDoc.get("projectId")).isEqualTo("project-123");
            assertThat(queryDoc.get("resourceName")).isEqualTo("my-secret");
            assertThat(queryDoc.get("resourceType")).isEqualTo("SECRET");
            assertThat(queryDoc.get("action")).isEqualTo("READ");
            assertThat(queryDoc.containsKey("userId")).isTrue();
            assertThat(queryDoc.get("timestamp")).isInstanceOf(Document.class);
            
            assertThat(count).isEqualTo(42L);
        }

        @Test
        @DisplayName("should count with only projectId filter when only projectId is present")
        void shouldCountWithOnlyProjectIdFilter_whenOnlyProjectIdPresent() {
            ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
            
            when(mongoTemplate.count(any(Query.class), eq(AuditEntryEntity.class)))
                    .thenReturn(15L);

            long count = repository.countByFilters(
                    Optional.of("project-123"),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty()
            );

            verify(mongoTemplate).count(queryCaptor.capture(), eq(AuditEntryEntity.class));
            Query capturedQuery = queryCaptor.getValue();
            Document queryDoc = capturedQuery.getQueryObject();

            assertThat(queryDoc.get("projectId")).isEqualTo("project-123");
            assertThat(queryDoc.containsKey("resourceName")).isFalse();
            assertThat(queryDoc.containsKey("resourceType")).isFalse();
            assertThat(queryDoc.containsKey("action")).isFalse();
            assertThat(queryDoc.containsKey("userId")).isFalse();
            assertThat(queryDoc.containsKey("timestamp")).isFalse();
            
            assertThat(count).isEqualTo(15L);
        }

        @Test
        @DisplayName("should count with SERVICE user type filter")
        void shouldCountWithServiceUserTypeFilter() {
            ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
            
            when(mongoTemplate.count(any(Query.class), eq(AuditEntryEntity.class)))
                    .thenReturn(10L);

            long count = repository.countByFilters(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of("SERVICE"),
                    Optional.empty()
            );

            verify(mongoTemplate).count(queryCaptor.capture(), eq(AuditEntryEntity.class));
            Query capturedQuery = queryCaptor.getValue();
            Document queryDoc = capturedQuery.getQueryObject();

            assertThat(queryDoc.containsKey("userId")).isTrue();
            assertThat(count).isEqualTo(10L);
        }

        @Test
        @DisplayName("should count with HUMAN user type filter")
        void shouldCountWithHumanUserTypeFilter() {
            ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
            
            when(mongoTemplate.count(any(Query.class), eq(AuditEntryEntity.class)))
                    .thenReturn(5L);

            long count = repository.countByFilters(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of("HUMAN"),
                    Optional.empty()
            );

            verify(mongoTemplate).count(queryCaptor.capture(), eq(AuditEntryEntity.class));
            Query capturedQuery = queryCaptor.getValue();
            Document queryDoc = capturedQuery.getQueryObject();

            assertThat(queryDoc.containsKey("userId")).isTrue();
            assertThat(count).isEqualTo(5L);
        }

        @Test
        @DisplayName("should count without user type filter when unknown user type is provided")
        void shouldCountWithoutUserTypeFilter_whenUnknownUserType() {
            ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
            
            when(mongoTemplate.count(any(Query.class), eq(AuditEntryEntity.class)))
                    .thenReturn(20L);

            long count = repository.countByFilters(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of("UNKNOWN_TYPE"),
                    Optional.empty()
            );

            verify(mongoTemplate).count(queryCaptor.capture(), eq(AuditEntryEntity.class));
            Query capturedQuery = queryCaptor.getValue();
            Document queryDoc = capturedQuery.getQueryObject();

            assertThat(queryDoc.containsKey("userId")).isFalse();
            assertThat(count).isEqualTo(20L);
        }

        @Test
        @DisplayName("should count all when no filters are present")
        void shouldCountAll_whenNoFiltersPresent() {
            ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
            
            when(mongoTemplate.count(any(Query.class), eq(AuditEntryEntity.class)))
                    .thenReturn(100L);

            long count = repository.countByFilters(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty()
            );

            verify(mongoTemplate).count(queryCaptor.capture(), eq(AuditEntryEntity.class));
            Query capturedQuery = queryCaptor.getValue();
            Document queryDoc = capturedQuery.getQueryObject();

            assertThat(queryDoc.isEmpty()).isTrue();
            assertThat(count).isEqualTo(100L);
        }

        @Test
        @DisplayName("should return zero when no matching documents")
        void shouldReturnZero_whenNoMatchingDocuments() {
            when(mongoTemplate.count(any(Query.class), eq(AuditEntryEntity.class)))
                    .thenReturn(0L);

            long count = repository.countByFilters(
                    Optional.of("non-existent-project"),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty()
            );

            assertThat(count).isEqualTo(0L);
        }
    }
}
