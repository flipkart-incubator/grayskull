package com.flipkart.grayskull.service.impl;

import com.flipkart.grayskull.configuration.KmsConfig;
import com.flipkart.grayskull.exception.BadRequestException;
import com.flipkart.grayskull.exception.NotFoundException;
import com.flipkart.grayskull.mappers.SecretMapper;
import com.flipkart.grayskull.models.dto.request.SecretVersionEntry;
import com.flipkart.grayskull.models.dto.response.BatchGetSecretsResponse;
import com.flipkart.grayskull.models.dto.response.SecretDataResponse;
import com.flipkart.grayskull.service.utils.AuthnUtil;
import com.flipkart.grayskull.service.utils.SecretEncryptionUtil;
import com.flipkart.grayskull.spi.models.Secret;
import com.flipkart.grayskull.spi.models.SecretData;
import com.flipkart.grayskull.spi.models.enums.LifecycleState;
import com.flipkart.grayskull.spi.repositories.ProjectRepository;
import com.flipkart.grayskull.spi.repositories.SecretDataRepository;
import com.flipkart.grayskull.spi.repositories.SecretRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
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

    @Nested
    @DisplayName("batchGetSecrets")
    class BatchGetSecretsTest {

        @Test
        @DisplayName("Should return empty list when all secrets are up-to-date")
        void shouldReturnEmpty_whenAllSecretsUpToDate() {
            Secret secret = Secret.builder()
                    .id("s1").projectId("proj").name("db-pass").currentDataVersion(3).build();
            when(secretRepository.findActiveByProjectAndNames(Map.of("proj", List.of("db-pass"))))
                    .thenReturn(List.of(secret));

            BatchGetSecretsResponse response = secretService.batchGetSecrets(List.of(
                    new SecretVersionEntry("proj", "db-pass", 3)
            ));

            assertThat(response.getUpdatedCount()).isZero();
            assertThat(response.getUpdatedSecrets()).isEmpty();
            verify(secretDataRepository, never()).findBySecretIdAndVersionPairs(any());
        }

        @Test
        @DisplayName("Should return updated secret when version has changed")
        void shouldReturnUpdatedSecret_whenVersionChanged() {
            Secret secret = Secret.builder()
                    .id("s1").projectId("proj").name("db-pass").currentDataVersion(5).build();
            SecretData secretData = SecretData.builder().secretId("s1").dataVersion(5).build();
            SecretDataResponse mappedResponse = SecretDataResponse.builder().dataVersion(5).publicPart("pub").build();

            when(secretRepository.findActiveByProjectAndNames(Map.of("proj", List.of("db-pass"))))
                    .thenReturn(List.of(secret));
            when(secretDataRepository.findBySecretIdAndVersionPairs(Map.of("s1", 5L)))
                    .thenReturn(List.of(secretData));
            when(secretMapper.toSecretDataResponse(secret, secretData))
                    .thenReturn(mappedResponse);

            BatchGetSecretsResponse response = secretService.batchGetSecrets(List.of(
                    new SecretVersionEntry("proj", "db-pass", 2)
            ));

            assertThat(response.getUpdatedCount()).isEqualTo(1);
            assertThat(response.getUpdatedSecrets()).hasSize(1);
            assertThat(response.getUpdatedSecrets().get(0).getProjectId()).isEqualTo("proj");
            assertThat(response.getUpdatedSecrets().get(0).getSecretName()).isEqualTo("db-pass");
            assertThat(response.getUpdatedSecrets().get(0).getSecretValue()).isEqualTo(mappedResponse);
            verify(secretEncryptionUtil).decryptSecretData(secretData);
        }

        @Test
        @DisplayName("Should silently skip when secret is not found (inactive or missing)")
        void shouldSkip_whenSecretNotFound() {
            when(secretRepository.findActiveByProjectAndNames(Map.of("proj", List.of("missing"))))
                    .thenReturn(List.of());

            BatchGetSecretsResponse response = secretService.batchGetSecrets(List.of(
                    new SecretVersionEntry("proj", "missing", 0)
            ));

            assertThat(response.getUpdatedCount()).isZero();
            assertThat(response.getUpdatedSecrets()).isEmpty();
        }

        @Test
        @DisplayName("Should silently skip when secret data is not found for a changed secret")
        void shouldSkip_whenSecretDataNotFound() {
            Secret secret = Secret.builder()
                    .id("s1").projectId("proj").name("db-pass").currentDataVersion(5).build();

            when(secretRepository.findActiveByProjectAndNames(Map.of("proj", List.of("db-pass"))))
                    .thenReturn(List.of(secret));
            when(secretDataRepository.findBySecretIdAndVersionPairs(Map.of("s1", 5L)))
                    .thenReturn(List.of());

            BatchGetSecretsResponse response = secretService.batchGetSecrets(List.of(
                    new SecretVersionEntry("proj", "db-pass", 2)
            ));

            assertThat(response.getUpdatedCount()).isZero();
            assertThat(response.getUpdatedSecrets()).isEmpty();
        }

        @Test
        @DisplayName("Should fetch all secrets in a single cross-project query")
        void shouldFetchAllSecrets_inSingleQuery() {
            Secret secretA = Secret.builder()
                    .id("sa").projectId("proj-a").name("key-1").currentDataVersion(1).build();
            Secret secretB = Secret.builder()
                    .id("sb").projectId("proj-b").name("key-2").currentDataVersion(4).build();
            SecretData dataB = SecretData.builder().secretId("sb").dataVersion(4).build();
            SecretDataResponse mappedB = SecretDataResponse.builder().dataVersion(4).build();

            Map<String, List<String>> expectedKeys = Map.of(
                    "proj-a", List.of("key-1"),
                    "proj-b", List.of("key-2"));
            when(secretRepository.findActiveByProjectAndNames(expectedKeys))
                    .thenReturn(List.of(secretA, secretB));
            when(secretDataRepository.findBySecretIdAndVersionPairs(Map.of("sb", 4L)))
                    .thenReturn(List.of(dataB));
            when(secretMapper.toSecretDataResponse(secretB, dataB))
                    .thenReturn(mappedB);

            BatchGetSecretsResponse response = secretService.batchGetSecrets(List.of(
                    new SecretVersionEntry("proj-a", "key-1", 1),
                    new SecretVersionEntry("proj-b", "key-2", 2)
            ));

            assertThat(response.getUpdatedCount()).isEqualTo(1);
            assertThat(response.getUpdatedSecrets().get(0).getProjectId()).isEqualTo("proj-b");

            verify(secretRepository, times(1)).findActiveByProjectAndNames(any());
        }

        @Test
        @DisplayName("Should skip decryption for unchanged secrets in a mixed batch")
        void shouldSkipDecryption_forUnchangedSecrets() {
            Secret unchanged = Secret.builder()
                    .id("s1").projectId("proj").name("stable").currentDataVersion(3).build();
            Secret changed = Secret.builder()
                    .id("s2").projectId("proj").name("rotated").currentDataVersion(7).build();
            SecretData changedData = SecretData.builder().secretId("s2").dataVersion(7).build();
            SecretDataResponse mappedChanged = SecretDataResponse.builder().dataVersion(7).build();

            when(secretRepository.findActiveByProjectAndNames(anyMap()))
                    .thenReturn(List.of(unchanged, changed));
            when(secretDataRepository.findBySecretIdAndVersionPairs(Map.of("s2", 7L)))
                    .thenReturn(List.of(changedData));
            when(secretMapper.toSecretDataResponse(changed, changedData))
                    .thenReturn(mappedChanged);

            BatchGetSecretsResponse response = secretService.batchGetSecrets(List.of(
                    new SecretVersionEntry("proj", "stable", 3),
                    new SecretVersionEntry("proj", "rotated", 4)
            ));

            assertThat(response.getUpdatedCount()).isEqualTo(1);
            assertThat(response.getUpdatedSecrets().get(0).getSecretName()).isEqualTo("rotated");

            verify(secretEncryptionUtil).decryptSecretData(changedData);
            verify(secretEncryptionUtil, times(1)).decryptSecretData(any());
        }
    }
}
