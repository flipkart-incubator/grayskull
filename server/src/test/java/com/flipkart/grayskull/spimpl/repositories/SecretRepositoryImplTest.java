package com.flipkart.grayskull.spimpl.repositories;

import com.flipkart.grayskull.entities.SecretEntity;
import com.flipkart.grayskull.spi.models.Secret;
import com.flipkart.grayskull.spimpl.repositories.mongo.SecretDataMongoRepository;
import com.flipkart.grayskull.spimpl.repositories.mongo.SecretMongoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@DisplayName("SecretRepositoryImpl Unit Tests")
class SecretRepositoryImplTest {

    SecretDataMongoRepository secretDataMongoRepository = mock();
    SecretMongoRepository mongoRepository = mock();

    SecretRepositoryImpl repository = new SecretRepositoryImpl(mongoRepository, secretDataMongoRepository);

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
}
