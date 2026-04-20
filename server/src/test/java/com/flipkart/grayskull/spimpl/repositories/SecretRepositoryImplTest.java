package com.flipkart.grayskull.spimpl.repositories;

import com.flipkart.grayskull.entities.SecretEntity;
import com.flipkart.grayskull.spi.models.Secret;
import com.flipkart.grayskull.spi.models.enums.LifecycleState;
import com.flipkart.grayskull.spimpl.repositories.mongo.SecretDataMongoRepository;
import com.flipkart.grayskull.spimpl.repositories.mongo.SecretMongoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    @Test
    @DisplayName("findActiveByProjectAndNames should return empty list when map is empty")
    void findActiveByProjectAndNames_shouldReturnEmpty_whenMapIsEmpty() {
        List<Secret> result = repository.findActiveByProjectAndNames(Map.of());
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("findActiveByProjectAndNames should return matching secrets")
    void findActiveByProjectAndNames_shouldReturnMatchingSecrets() {
        Map<String, List<String>> projectToNames = new HashMap<>();
        projectToNames.put("project1", List.of("secret1", "secret2"));
        projectToNames.put("project2", List.of("secret3"));

        SecretEntity entity1 = new SecretEntity();
        entity1.setProjectId("project1");
        entity1.setName("secret1");

        SecretEntity entity2 = new SecretEntity();
        entity2.setProjectId("project2");
        entity2.setName("secret3");

        SecretEntity entity3 = new SecretEntity();
        entity3.setProjectId("project1");
        entity3.setName("secret3"); // Cross-match, should be filtered out

        when(mongoRepository.findByProjectIdInAndNameInAndState(
                anyList(), anyList(), eq(LifecycleState.ACTIVE)))
                .thenReturn(List.of(entity1, entity2, entity3));

        List<Secret> result = repository.findActiveByProjectAndNames(projectToNames);

        assertThat(result).hasSize(2)
                .containsExactlyInAnyOrder(entity1, entity2);
    }
}
