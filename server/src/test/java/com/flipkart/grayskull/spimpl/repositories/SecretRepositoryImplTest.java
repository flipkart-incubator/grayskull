package com.flipkart.grayskull.spimpl.repositories;

import com.flipkart.grayskull.entities.SecretEntity;
import com.flipkart.grayskull.spi.models.Secret;
import com.flipkart.grayskull.spi.models.enums.LifecycleState;
import com.flipkart.grayskull.spimpl.repositories.mongo.SecretDataMongoRepository;
import com.flipkart.grayskull.spimpl.repositories.mongo.SecretMongoRepository;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("SecretRepositoryImpl Unit Tests")
class SecretRepositoryImplTest {

    SecretDataMongoRepository secretDataMongoRepository = mock();
    SecretMongoRepository mongoRepository = mock();
    MongoTemplate mongoTemplate = mock();

    SecretRepositoryImpl repository = new SecretRepositoryImpl(mongoRepository, secretDataMongoRepository, mongoTemplate);

    @Test
    @DisplayName("delete should delegate to mongoRepository when Secret is SecretEntity")
    void delete_shouldDelegate_whenSecretEntity() {
        SecretEntity entity = new SecretEntity();
        entity.setId("abcd");
        repository.delete(entity);

        verify(mongoRepository).delete(entity);
        verify(secretDataMongoRepository).deleteAllBySecretId("abcd");
    }

    @Test
    @DisplayName("delete should throw IllegalArgumentException when Secret is not SecretEntity")
    void delete_shouldThrow_whenNotSecretEntity() {
        Secret notEntity = new Secret();

        assertThatThrownBy(() -> repository.delete(notEntity))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Expected SecretEntity but got");

        verify(mongoRepository, never()).delete(any(SecretEntity.class));
        verify(secretDataMongoRepository, never()).deleteAllBySecretId(anyString());
    }

    @Nested
    @DisplayName("findActiveByProjectAndNames")
    class FindActiveByProjectAndNames {

        @Test
        @DisplayName("returns empty and does not hit Mongo when map is empty")
        void returnsEmpty_whenMapIsEmpty() {
            assertThat(repository.findActiveByProjectAndNames(Map.of())).isEmpty();
            verifyNoInteractions(mongoTemplate);
        }

        @Test
        @DisplayName("returns empty and does not hit Mongo when every project has an empty name list")
        void returnsEmpty_whenAllNameListsEmpty() {
            Map<String, List<String>> map = new HashMap<>();
            map.put("project1", List.of());
            map.put("project2", List.of());

            assertThat(repository.findActiveByProjectAndNames(map)).isEmpty();
            verifyNoInteractions(mongoTemplate);
        }

        @Test
        @DisplayName("skips null name lists and still queries projects with non-empty lists")
        void skipsNullNameLists() {
            Map<String, List<String>> map = new HashMap<>();
            map.put("projectA", null);
            map.put("projectB", List.of("secret1"));

            when(mongoTemplate.find(any(Query.class), eq(SecretEntity.class))).thenReturn(List.of());

            repository.findActiveByProjectAndNames(map);

            ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
            verify(mongoTemplate).find(captor.capture(), eq(SecretEntity.class));
            Document root = captor.getValue().getQueryObject();
            @SuppressWarnings("unchecked")
            List<Document> andClauses = (List<Document>) root.get("$and");
            Document projectBranch = andClauses.stream()
                    .filter(d -> d.containsKey("$and"))
                    .findFirst()
                    .orElseThrow();
            @SuppressWarnings("unchecked")
            List<Document> inner = (List<Document>) projectBranch.get("$and");
            assertThat(inner.get(0).get("projectId")).isEqualTo("projectB");
        }

        @Test
        @DisplayName("returns entities from MongoTemplate.find unchanged (cast to Secret)")
        void returnsFindResults() {
            SecretEntity e1 = new SecretEntity();
            e1.setProjectId("p1");
            e1.setName("s1");
            SecretEntity e2 = new SecretEntity();
            e2.setProjectId("p2");
            e2.setName("s2");

            Map<String, List<String>> map = Map.of(
                    "p1", List.of("s1"),
                    "p2", List.of("s2"));

            when(mongoTemplate.find(any(Query.class), eq(SecretEntity.class))).thenReturn(List.of(e1, e2));

            List<Secret> result = repository.findActiveByProjectAndNames(map);

            assertThat(result).containsExactly(e1, e2);
        }

        @Test
        @DisplayName("query requires ACTIVE state and one conjunction branch per project")
        void queryShape_twoProjects() {
            Map<String, List<String>> map = Map.of(
                    "project1", List.of("secret1", "secret2"),
                    "project2", List.of("secret3"));

            when(mongoTemplate.find(any(Query.class), eq(SecretEntity.class))).thenReturn(List.of());

            repository.findActiveByProjectAndNames(map);

            ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
            verify(mongoTemplate).find(captor.capture(), eq(SecretEntity.class));

            Document root = captor.getValue().getQueryObject();
            assertThat(root.keySet()).contains("$and");

            @SuppressWarnings("unchecked")
            List<Document> andClauses = (List<Document>) root.get("$and");
            assertThat(andClauses).hasSize(2);

            Document stateClause = andClauses.stream()
                    .filter(d -> d.containsKey("state"))
                    .findFirst()
                    .orElseThrow();
            assertThat(stateClause.get("state")).isEqualTo(LifecycleState.ACTIVE);

            Document orWrapper = andClauses.stream()
                    .filter(d -> d.containsKey("$or"))
                    .findFirst()
                    .orElseThrow();
            @SuppressWarnings("unchecked")
            List<Document> orBranches = (List<Document>) orWrapper.get("$or");
            assertThat(orBranches).hasSize(2);
            // Map iteration order is undefined; match branches by content, not index.
            assertThat(orBranches).satisfiesExactlyInAnyOrder(
                    b -> assertBranch((Document) b, "project1", "secret1", "secret2"),
                    b -> assertBranch((Document) b, "project2", "secret3"));
        }

        @Test
        @DisplayName("single project uses $and of state and (projectId + name $in) without redundant $or")
        void queryShape_singleProject() {
            Map<String, List<String>> map = Map.of("onlyProject", List.of("a", "b"));

            when(mongoTemplate.find(any(Query.class), eq(SecretEntity.class))).thenReturn(List.of());

            repository.findActiveByProjectAndNames(map);

            ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
            verify(mongoTemplate).find(captor.capture(), eq(SecretEntity.class));

            Document root = captor.getValue().getQueryObject();
            @SuppressWarnings("unchecked")
            List<Document> andClauses = (List<Document>) root.get("$and");
            assertThat(andClauses).hasSize(2);
            assertThat(andClauses).noneMatch(d -> d.containsKey("$or"));

            Document stateClause = andClauses.stream().filter(d -> d.containsKey("state")).findFirst().orElseThrow();
            assertThat(stateClause.get("state")).isEqualTo(LifecycleState.ACTIVE);

            Document projectClause = andClauses.stream().filter(d -> d.containsKey("$and")).findFirst().orElseThrow();
            @SuppressWarnings("unchecked")
            List<Document> innerAnd = (List<Document>) projectClause.get("$and");
            assertThat(innerAnd).hasSize(2);
            assertThat(innerAnd.get(0).get("projectId")).isEqualTo("onlyProject");
            assertThat(extractNameInList(innerAnd.get(1))).containsExactlyInAnyOrder("a", "b");
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("crossProjectIsolationCases")
        @DisplayName("Mongo is queried once; no broad projectId IN + name IN fan-out")
        void delegatesToMongoTemplateOnce(String label, Map<String, List<String>> map) {
            when(mongoTemplate.find(any(Query.class), eq(SecretEntity.class))).thenReturn(List.of());

            repository.findActiveByProjectAndNames(map);

            verify(mongoTemplate, times(1)).find(any(Query.class), eq(SecretEntity.class));
            verifyNoInteractions(mongoRepository);
        }

        @Test
        @DisplayName("null map is treated as empty (no Mongo call)")
        void returnsEmpty_whenMapIsNull() {
            assertThat(repository.findActiveByProjectAndNames(null)).isEmpty();
            verifyNoInteractions(mongoTemplate);
        }

        @Test
        @DisplayName("null projectId keys are skipped; only valid projects are queried")
        void skipsNullProjectIdKeys() {
            Map<String, List<String>> map = new HashMap<>();
            map.put(null, List.of("orphan"));
            map.put("validProject", List.of("secret1"));

            when(mongoTemplate.find(any(Query.class), eq(SecretEntity.class))).thenReturn(List.of());

            repository.findActiveByProjectAndNames(map);

            ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
            verify(mongoTemplate).find(captor.capture(), eq(SecretEntity.class));
            Document root = captor.getValue().getQueryObject();
            @SuppressWarnings("unchecked")
            List<Document> andClauses = (List<Document>) root.get("$and");
            Document projectClause = andClauses.stream()
                    .filter(d -> d.containsKey("$and"))
                    .findFirst()
                    .orElseThrow();
            @SuppressWarnings("unchecked")
            List<Document> inner = (List<Document>) projectClause.get("$and");
            assertThat(inner.get(0).get("projectId")).isEqualTo("validProject");
        }

        @Test
        @DisplayName("three projects produce $or with three branches")
        void queryShape_threeProjects() {
            Map<String, List<String>> map = new HashMap<>();
            map.put("p1", List.of("s1"));
            map.put("p2", List.of("s2"));
            map.put("p3", List.of("s3"));

            when(mongoTemplate.find(any(Query.class), eq(SecretEntity.class))).thenReturn(List.of());

            repository.findActiveByProjectAndNames(map);

            ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
            verify(mongoTemplate).find(captor.capture(), eq(SecretEntity.class));
            Document root = captor.getValue().getQueryObject();
            @SuppressWarnings("unchecked")
            List<Document> andClauses = (List<Document>) root.get("$and");
            Document orWrapper = andClauses.stream()
                    .filter(d -> d.containsKey("$or"))
                    .findFirst()
                    .orElseThrow();
            @SuppressWarnings("unchecked")
            List<Document> orBranches = (List<Document>) orWrapper.get("$or");
            assertThat(orBranches).hasSize(3);
            assertThat(orBranches).satisfiesExactlyInAnyOrder(
                    b -> assertBranch((Document) b, "p1", "s1"),
                    b -> assertBranch((Document) b, "p2", "s2"),
                    b -> assertBranch((Document) b, "p3", "s3"));
        }

        static Stream<Arguments> crossProjectIsolationCases() {
            return Stream.of(
                    Arguments.of("two projects", Map.of("A", List.of("x"), "B", List.of("y"))),
                    Arguments.of("same secret name in two projects",
                            Map.of("proj-a", List.of("shared-name"), "proj-b", List.of("shared-name"))));
        }
    }

    /**
     * Branch shape: {@code { $and: [ { projectId: ... }, { name: { $in: [...] } } ] } }.
     */
    private static void assertBranch(Document branch, String expectedProjectId, String... expectedNames) {
        assertThat(branch.containsKey("$and")).isTrue();
        @SuppressWarnings("unchecked")
        List<Document> parts = (List<Document>) branch.get("$and");
        assertThat(parts.get(0).get("projectId")).isEqualTo(expectedProjectId);
        assertThat(extractNameInList(parts.get(1))).containsExactlyInAnyOrder(expectedNames);
    }

    @SuppressWarnings("unchecked")
    private static List<String> extractNameInList(Document nameCriterion) {
        Object nameVal = nameCriterion.get("name");
        if (nameVal instanceof List<?> list) {
            return (List<String>) list;
        }
        Document inWrapper = (Document) nameVal;
        return (List<String>) inWrapper.get("$in");
    }
}
