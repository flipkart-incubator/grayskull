package com.flipkart.grayskull.service.impl;

import com.flipkart.grayskull.configuration.KmsConfig;
import com.flipkart.grayskull.exception.BadRequestException;
import com.flipkart.grayskull.exception.NotFoundException;
import com.flipkart.grayskull.mappers.SecretMapper;
import com.flipkart.grayskull.service.utils.AuthnUtil;
import com.flipkart.grayskull.service.utils.SecretEncryptionUtil;
import com.flipkart.grayskull.spi.models.Secret;
import com.flipkart.grayskull.spi.models.enums.LifecycleState;
import com.flipkart.grayskull.spi.repositories.ProjectRepository;
import com.flipkart.grayskull.spi.repositories.SecretDataRepository;
import com.flipkart.grayskull.spi.repositories.SecretRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@DisplayName("SecretServiceImpl Unit Tests")
class SecretServiceImplTest {

    private final SecretRepository secretRepository = mock();
    private final SecretDataRepository secretDataRepository = mock();
    private final SecretMapper secretMapper = mock();
    private final SecretEncryptionUtil secretEncryptionUtil = mock();
    private final KmsConfig kmsConfig = mock();
    private final ProjectRepository projectRepository = mock();
    private final AuthnUtil authnUtil = mock();

    private final SecretServiceImpl secretService = new SecretServiceImpl(secretRepository, secretDataRepository, secretMapper, secretEncryptionUtil, kmsConfig, projectRepository, authnUtil);

    @Test
    @DisplayName("destroySecret should throw NotFoundException when secret does not exist")
    void destroySecret_shouldThrowNotFound_whenSecretMissing() {
        when(secretRepository.findByProjectIdAndName("project", "secret")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> secretService.destroySecret("project", "secret"))
                .isInstanceOf(NotFoundException.class);

        verify(secretRepository, never()).delete(any());
    }

    @Test
    @DisplayName("destroySecret should throw BadRequestException when secret is not DISABLED")
    void destroySecret_shouldThrowBadRequest_whenSecretNotDisabled() {
        Secret secret = new Secret();
        secret.setState(LifecycleState.ACTIVE);
        when(secretRepository.findByProjectIdAndName("project", "secret")).thenReturn(Optional.of(secret));

        assertThatThrownBy(() -> secretService.destroySecret("project", "secret"))
                .isInstanceOf(BadRequestException.class);

        verify(secretRepository, never()).delete(any());
    }

    @Test
    @DisplayName("destroySecret should delete secret when state is DISABLED")
    void destroySecret_shouldDelete_whenSecretDisabled() {
        Secret secret = new Secret();
        secret.setState(LifecycleState.DISABLED);
        when(secretRepository.findByProjectIdAndName("project", "secret")).thenReturn(Optional.of(secret));

        secretService.destroySecret("project", "secret");

        verify(secretRepository).delete(secret);
    }
}
