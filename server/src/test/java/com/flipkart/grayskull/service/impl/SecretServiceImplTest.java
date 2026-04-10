package com.flipkart.grayskull.service.impl;

import com.flipkart.grayskull.configuration.KmsConfig;
import com.flipkart.grayskull.exception.BadRequestException;
import com.flipkart.grayskull.exception.NotFoundException;
import com.flipkart.grayskull.mappers.SecretMapper;
import com.flipkart.grayskull.models.dto.request.BulkPollSecretEntry;
import com.flipkart.grayskull.models.dto.response.BulkPollResponse;
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
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
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
    @DisplayName("bulkPollSecrets")
    class BulkPollSecretsTest {

        @Test
        @DisplayName("Should return empty list when all secrets are up-to-date")
        void shouldReturnEmpty_whenAllSecretsUpToDate() {
            Secret secret = Secret.builder()
                    .id("s1").projectId("proj").name("db-pass").currentDataVersion(3).build();
            when(secretRepository.findByProjectIdAndNamesAndState("proj", List.of("db-pass"), LifecycleState.ACTIVE))
                    .thenReturn(List.of(secret));

            BulkPollResponse response = secretService.bulkPollSecrets(List.of(
                    new BulkPollSecretEntry("proj", "db-pass", 3)
            ));

            assertThat(response.getUpdatedSecrets()).isEmpty();
            verify(secretDataRepository, never()).getBySecretIdAndDataVersion(any(), anyLong());
        }

        @Test
        @DisplayName("Should return updated secret when version has changed")
        void shouldReturnUpdatedSecret_whenVersionChanged() {
            Secret secret = Secret.builder()
                    .id("s1").projectId("proj").name("db-pass").currentDataVersion(5).build();
            SecretData secretData = SecretData.builder().secretId("s1").dataVersion(5).build();
            SecretDataResponse mappedResponse = SecretDataResponse.builder().dataVersion(5).publicPart("pub").build();

            when(secretRepository.findByProjectIdAndNamesAndState("proj", List.of("db-pass"), LifecycleState.ACTIVE))
                    .thenReturn(List.of(secret));
            when(secretDataRepository.getBySecretIdAndDataVersion("s1", 5))
                    .thenReturn(Optional.of(secretData));
            when(secretMapper.toSecretDataResponse(secret, secretData))
                    .thenReturn(mappedResponse);

            BulkPollResponse response = secretService.bulkPollSecrets(List.of(
                    new BulkPollSecretEntry("proj", "db-pass", 2)
            ));

            assertThat(response.getUpdatedSecrets()).hasSize(1);
            assertThat(response.getUpdatedSecrets().get(0).getProjectId()).isEqualTo("proj");
            assertThat(response.getUpdatedSecrets().get(0).getSecretName()).isEqualTo("db-pass");
            assertThat(response.getUpdatedSecrets().get(0).getSecretValue()).isEqualTo(mappedResponse);
            verify(secretEncryptionUtil).decryptSecretData(secretData);
        }

        @Test
        @DisplayName("Should fail fast with 404 when any secret is not found")
        void shouldFailFast_whenSecretNotFound() {
            when(secretRepository.findByProjectIdAndNamesAndState("proj", List.of("missing"), LifecycleState.ACTIVE))
                    .thenReturn(List.of());

            assertThatThrownBy(() -> secretService.bulkPollSecrets(List.of(
                    new BulkPollSecretEntry("proj", "missing", 0)
            )))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("Active secret not found");
        }

        @Test
        @DisplayName("Should fail fast with 404 when secret data is not found for a changed secret")
        void shouldFailFast_whenSecretDataNotFound() {
            Secret secret = Secret.builder()
                    .id("s1").projectId("proj").name("db-pass").currentDataVersion(5).build();

            when(secretRepository.findByProjectIdAndNamesAndState("proj", List.of("db-pass"), LifecycleState.ACTIVE))
                    .thenReturn(List.of(secret));
            when(secretDataRepository.getBySecretIdAndDataVersion("s1", 5))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> secretService.bulkPollSecrets(List.of(
                    new BulkPollSecretEntry("proj", "db-pass", 2)
            )))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("Secret data not found");
        }

        @Test
        @DisplayName("Should handle cross-project queries with one bulk fetch per project")
        void shouldGroupByProject_andFetchOncePerProject() {
            Secret secretA = Secret.builder()
                    .id("sa").projectId("proj-a").name("key-1").currentDataVersion(1).build();
            Secret secretB = Secret.builder()
                    .id("sb").projectId("proj-b").name("key-2").currentDataVersion(4).build();
            SecretData dataB = SecretData.builder().secretId("sb").dataVersion(4).build();
            SecretDataResponse mappedB = SecretDataResponse.builder().dataVersion(4).build();

            when(secretRepository.findByProjectIdAndNamesAndState("proj-a", List.of("key-1"), LifecycleState.ACTIVE))
                    .thenReturn(List.of(secretA));
            when(secretRepository.findByProjectIdAndNamesAndState("proj-b", List.of("key-2"), LifecycleState.ACTIVE))
                    .thenReturn(List.of(secretB));
            when(secretDataRepository.getBySecretIdAndDataVersion("sb", 4))
                    .thenReturn(Optional.of(dataB));
            when(secretMapper.toSecretDataResponse(secretB, dataB))
                    .thenReturn(mappedB);

            BulkPollResponse response = secretService.bulkPollSecrets(List.of(
                    new BulkPollSecretEntry("proj-a", "key-1", 1),
                    new BulkPollSecretEntry("proj-b", "key-2", 2)
            ));

            assertThat(response.getUpdatedSecrets()).hasSize(1);
            assertThat(response.getUpdatedSecrets().get(0).getProjectId()).isEqualTo("proj-b");

            verify(secretRepository).findByProjectIdAndNamesAndState("proj-a", List.of("key-1"), LifecycleState.ACTIVE);
            verify(secretRepository).findByProjectIdAndNamesAndState("proj-b", List.of("key-2"), LifecycleState.ACTIVE);
            verify(secretDataRepository, never()).getBySecretIdAndDataVersion(eq("sa"), anyLong());
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

            when(secretRepository.findByProjectIdAndNamesAndState(eq("proj"), anyList(), eq(LifecycleState.ACTIVE)))
                    .thenReturn(List.of(unchanged, changed));
            when(secretDataRepository.getBySecretIdAndDataVersion("s2", 7))
                    .thenReturn(Optional.of(changedData));
            when(secretMapper.toSecretDataResponse(changed, changedData))
                    .thenReturn(mappedChanged);

            BulkPollResponse response = secretService.bulkPollSecrets(List.of(
                    new BulkPollSecretEntry("proj", "stable", 3),
                    new BulkPollSecretEntry("proj", "rotated", 4)
            ));

            assertThat(response.getUpdatedSecrets()).hasSize(1);
            assertThat(response.getUpdatedSecrets().get(0).getSecretName()).isEqualTo("rotated");

            verify(secretDataRepository, never()).getBySecretIdAndDataVersion(eq("s1"), anyLong());
            verify(secretEncryptionUtil).decryptSecretData(changedData);
            verify(secretEncryptionUtil, times(1)).decryptSecretData(any());
        }
    }
}
