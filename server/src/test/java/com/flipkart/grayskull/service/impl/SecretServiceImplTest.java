package com.flipkart.grayskull.service.impl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.flipkart.grayskull.configuration.KmsConfig;
import com.flipkart.grayskull.entities.SecretDataEntity;
import com.flipkart.grayskull.entities.SecretEntity;
import com.flipkart.grayskull.exception.BadRequestException;
import com.flipkart.grayskull.exception.NotFoundException;
import com.flipkart.grayskull.mappers.SecretMapper;
import com.flipkart.grayskull.models.dto.request.CreateSecretRequest;
import com.flipkart.grayskull.models.dto.request.SecretVersionEntry;
import com.flipkart.grayskull.models.dto.request.UpgradeSecretDataRequest;
import com.flipkart.grayskull.models.dto.response.BatchGetSecretsResponse;
import com.flipkart.grayskull.models.dto.response.BatchSecretItem;
import com.flipkart.grayskull.models.dto.response.SecretDataResponse;
import com.flipkart.grayskull.service.utils.AuthnUtil;
import com.flipkart.grayskull.service.utils.SecretEncryptionUtil;
import com.flipkart.grayskull.spi.models.Secret;
import com.flipkart.grayskull.spi.models.SecretData;
import com.flipkart.grayskull.spi.models.enums.LifecycleState;
import com.flipkart.grayskull.spi.repositories.ProjectRepository;
import com.flipkart.grayskull.spi.repositories.SecretDataRepository;
import com.flipkart.grayskull.spi.repositories.SecretRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
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
            verify(secretDataRepository, never()).getBySecretIdAndDataVersion(any(), anyLong());
        }

        @Test
        @DisplayName("Should return updated secret when version has changed")
        void shouldReturnUpdatedSecret_whenVersionChanged() {
            Secret secret = Secret.builder()
                    .id("s1").projectId("proj").name("db-pass").currentDataVersion(5).build();
            SecretData secretData = SecretData.builder().secretId("s1").dataVersion(5).build();
            BatchSecretItem mapped = BatchSecretItem.builder()
                    .projectId("proj").secretName("db-pass")
                    .dataVersion(5).publicPart("pub").privatePart("priv").build();

            when(secretRepository.findActiveByProjectAndNames(Map.of("proj", List.of("db-pass"))))
                    .thenReturn(List.of(secret));
            when(secretDataRepository.getBySecretIdAndDataVersion("s1", 5L))
                    .thenReturn(Optional.of(secretData));
            when(secretMapper.toBatchSecretItem(secret, secretData)).thenReturn(mapped);

            BatchGetSecretsResponse response = secretService.batchGetSecrets(List.of(
                    new SecretVersionEntry("proj", "db-pass", 2)
            ));

            assertThat(response.getUpdatedCount()).isEqualTo(1);
            assertThat(response.getUpdatedSecrets()).hasSize(1);
            assertThat(response.getUpdatedSecrets().get(0).getProjectId()).isEqualTo("proj");
            assertThat(response.getUpdatedSecrets().get(0).getSecretName()).isEqualTo("db-pass");
            assertThat(response.getUpdatedSecrets().get(0).getDataVersion()).isEqualTo(5);
            assertThat(response.getUpdatedSecrets().get(0).getPublicPart()).isEqualTo("pub");
            assertThat(response.getUpdatedSecrets().get(0).getPrivatePart()).isEqualTo("priv");
            verify(secretEncryptionUtil).decryptSecretData(secretData);
        }

        @Test
        @DisplayName("Should always return the current value when lastKnownVersion is null")
        void shouldReturnLatest_whenLastKnownVersionIsNull() {
            Secret secret = Secret.builder()
                    .id("s1").projectId("proj").name("db-pass").currentDataVersion(7).build();
            SecretData secretData = SecretData.builder().secretId("s1").dataVersion(7).build();
            BatchSecretItem mapped = BatchSecretItem.builder()
                    .projectId("proj").secretName("db-pass").dataVersion(7).build();

            when(secretRepository.findActiveByProjectAndNames(Map.of("proj", List.of("db-pass"))))
                    .thenReturn(List.of(secret));
            when(secretDataRepository.getBySecretIdAndDataVersion("s1", 7L))
                    .thenReturn(Optional.of(secretData));
            when(secretMapper.toBatchSecretItem(secret, secretData)).thenReturn(mapped);

            BatchGetSecretsResponse response = secretService.batchGetSecrets(List.of(
                    new SecretVersionEntry("proj", "db-pass", null)
            ));

            assertThat(response.getUpdatedCount()).isEqualTo(1);
            assertThat(response.getUpdatedSecrets().get(0).getDataVersion()).isEqualTo(7);
        }

        @Test
        @DisplayName("Should fail-fast with NotFoundException when a requested secret is missing")
        void shouldThrowNotFound_whenSecretMissingFromBulkFetch() {
            when(secretRepository.findActiveByProjectAndNames(Map.of("proj", List.of("missing"))))
                    .thenReturn(List.of());

            assertThatThrownBy(() -> secretService.batchGetSecrets(List.of(
                    new SecretVersionEntry("proj", "missing", 0)
            )))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining("missing");

            verify(secretDataRepository, never()).getBySecretIdAndDataVersion(any(), anyLong());
        }

        @Test
        @DisplayName("Should fail-fast when duplicate entries shrink the map below request size")
        void shouldThrowNotFound_whenDuplicateEntriesLeaveFewerUniqueSecretsThanRequests() {
            Secret secret = Secret.builder()
                    .id("s1").projectId("proj").name("same").currentDataVersion(1).build();
            when(secretRepository.findActiveByProjectAndNames(Map.of("proj", List.of("same", "same"))))
                    .thenReturn(List.of(secret));

            assertThatThrownBy(() -> secretService.batchGetSecrets(List.of(
                    new SecretVersionEntry("proj", "same", 1),
                    new SecretVersionEntry("proj", "same", 1)
            )))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining("<unknown>");

            verify(secretDataRepository, never()).getBySecretIdAndDataVersion(any(), anyLong());
        }

        @Test
        @DisplayName("Should fail-fast with NotFoundException when secret data is missing for a changed secret")
        void shouldThrowNotFound_whenSecretDataMissing() {
            Secret secret = Secret.builder()
                    .id("s1").projectId("proj").name("db-pass").currentDataVersion(5).build();

            when(secretRepository.findActiveByProjectAndNames(Map.of("proj", List.of("db-pass"))))
                    .thenReturn(List.of(secret));
            when(secretDataRepository.getBySecretIdAndDataVersion("s1", 5L))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> secretService.batchGetSecrets(List.of(
                    new SecretVersionEntry("proj", "db-pass", 2)
            )))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining("s1");
        }

        @Test
        @DisplayName("Should perform a single bulk metadata query across projects")
        void shouldFetchAllSecrets_inSingleQuery() {
            Secret secretA = Secret.builder()
                    .id("sa").projectId("proj-a").name("key-1").currentDataVersion(1).build();
            Secret secretB = Secret.builder()
                    .id("sb").projectId("proj-b").name("key-2").currentDataVersion(4).build();
            SecretData dataB = SecretData.builder().secretId("sb").dataVersion(4).build();
            BatchSecretItem mappedB = BatchSecretItem.builder()
                    .projectId("proj-b").secretName("key-2").dataVersion(4).build();

            Map<String, List<String>> expectedKeys = Map.of(
                    "proj-a", List.of("key-1"),
                    "proj-b", List.of("key-2"));
            when(secretRepository.findActiveByProjectAndNames(expectedKeys))
                    .thenReturn(List.of(secretA, secretB));
            when(secretDataRepository.getBySecretIdAndDataVersion("sb", 4L))
                    .thenReturn(Optional.of(dataB));
            when(secretMapper.toBatchSecretItem(secretB, dataB)).thenReturn(mappedB);

            BatchGetSecretsResponse response = secretService.batchGetSecrets(List.of(
                    new SecretVersionEntry("proj-a", "key-1", 1),
                    new SecretVersionEntry("proj-b", "key-2", 2)
            ));

            assertThat(response.getUpdatedCount()).isEqualTo(1);
            assertThat(response.getUpdatedSecrets().get(0).getProjectId()).isEqualTo("proj-b");

            verify(secretRepository, times(1)).findActiveByProjectAndNames(any());
            // Unchanged secret must not cause any SecretData lookup.
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
            BatchSecretItem mappedChanged = BatchSecretItem.builder()
                    .projectId("proj").secretName("rotated").dataVersion(7).build();

            when(secretRepository.findActiveByProjectAndNames(anyMap()))
                    .thenReturn(List.of(unchanged, changed));
            when(secretDataRepository.getBySecretIdAndDataVersion("s2", 7L))
                    .thenReturn(Optional.of(changedData));
            when(secretMapper.toBatchSecretItem(changed, changedData)).thenReturn(mappedChanged);

            BatchGetSecretsResponse response = secretService.batchGetSecrets(List.of(
                    new SecretVersionEntry("proj", "stable", 3),
                    new SecretVersionEntry("proj", "rotated", 4)
            ));

            assertThat(response.getUpdatedCount()).isEqualTo(1);
            assertThat(response.getUpdatedSecrets().get(0).getSecretName()).isEqualTo("rotated");

            verify(secretEncryptionUtil).decryptSecretData(changedData);
            verify(secretEncryptionUtil, times(1)).decryptSecretData(any());
            verify(secretDataRepository, never()).getBySecretIdAndDataVersion(eq("s1"), anyLong());
        }
    }

    @Nested
    @DisplayName("Secret name logging across all secret APIs")
    class SecretApiLoggingTest {

        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        private ch.qos.logback.classic.Logger logger;

        @BeforeEach
        void attachAppender() {
            logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(SecretServiceImpl.class);
            appender.start();
            logger.addAppender(appender);
            when(authnUtil.getCurrentUsername()).thenReturn("ldap:alice");
        }

        @AfterEach
        void detachAppender() {
            logger.detachAppender(appender);
        }

        private String singleLogLine() {
            assertThat(appender.list).hasSize(1);
            ILoggingEvent event = appender.list.get(0);
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            return event.getFormattedMessage();
        }

        @Test
        @DisplayName("createSecret should log the secret name and user")
        void createSecret_logsSecretName() {
            CreateSecretRequest request = new CreateSecretRequest();
            request.setName("db-password");

            when(secretRepository.findByProjectIdAndName("project", "db-password")).thenReturn(Optional.empty());
            when(projectRepository.findById("project")).thenReturn(Optional.empty());
            when(kmsConfig.getDefaultKeyId()).thenReturn("key");
            when(projectRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            SecretEntity secret = new SecretEntity();
            secret.setId("sid");
            secret.setName("db-password");
            when(secretMapper.requestToSecret(request, "project", "ldap:alice")).thenReturn(secret);
            when(secretRepository.save(secret)).thenReturn(secret);
            when(secretMapper.requestToSecretData(request, "sid")).thenReturn(new SecretDataEntity());

            secretService.createSecret("project", request);

            assertThat(singleLogLine())
                    .isEqualTo("Created secret for project: project, secret: db-password, user: ldap:alice");
        }

        @Test
        @DisplayName("upgradeSecretData should log the secret name and the new version")
        void upgradeSecretData_logsNewVersion() {
            SecretEntity secret = new SecretEntity();
            secret.setName("db-password");
            secret.setState(LifecycleState.ACTIVE);
            secret.setCurrentDataVersion(3);
            when(secretRepository.findByProjectIdAndNameAndState("project", "db-password", LifecycleState.ACTIVE))
                    .thenReturn(Optional.of(secret));
            when(projectRepository.findById("project")).thenReturn(Optional.empty());
            when(kmsConfig.getDefaultKeyId()).thenReturn("key");
            when(projectRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
            UpgradeSecretDataRequest request = new UpgradeSecretDataRequest();
            when(secretMapper.upgradeRequestToSecretData(request, secret, 4)).thenReturn(new SecretDataEntity());

            secretService.upgradeSecretData("project", "db-password", request);

            assertThat(singleLogLine())
                    .isEqualTo("Updated secret data for project: project, secret: db-password, version: 4, user: ldap:alice");
        }

        @Test
        @DisplayName("deleteSecret should log a soft delete with the secret name")
        void deleteSecret_logsSoftDelete() {
            Secret secret = new Secret();
            secret.setName("db-password");
            secret.setState(LifecycleState.ACTIVE);
            when(secretRepository.findByProjectIdAndNameAndState("project", "db-password", LifecycleState.ACTIVE))
                    .thenReturn(Optional.of(secret));

            secretService.deleteSecret("project", "db-password");

            assertThat(singleLogLine())
                    .isEqualTo("Soft deleted secret for project: project, secret: db-password, user: ldap:alice");
        }

        @Test
        @DisplayName("destroySecret should log a destroy distinctly from a soft delete")
        void destroySecret_logsDestroy() {
            Secret secret = new Secret();
            secret.setName("db-password");
            secret.setState(LifecycleState.DISABLED);
            when(secretRepository.findByProjectIdAndName("project", "db-password")).thenReturn(Optional.of(secret));

            secretService.destroySecret("project", "db-password");

            assertThat(singleLogLine())
                    .isEqualTo("Destroyed secret for project: project, secret: db-password, user: ldap:alice");
        }

        @Test
        @DisplayName("listSecrets should log the project and the number of secrets returned")
        void listSecrets_logsCount() {
            SecretEntity secret = new SecretEntity();
            secret.setName("db-password");
            when(secretRepository.findByProjectIdAndState("project", LifecycleState.ACTIVE, 0, 10))
                    .thenReturn(List.of(secret));
            when(secretRepository.countByProjectIdAndState("project", LifecycleState.ACTIVE)).thenReturn(1L);

            secretService.listSecrets("project", 0, 10);

            assertThat(singleLogLine())
                    .isEqualTo("Listed secrets for project: project, count: 1, user: ldap:alice");
        }

        @Test
        @DisplayName("readSecretMetadata should log the secret name")
        void readSecretMetadata_logsSecretName() {
            SecretEntity secret = new SecretEntity();
            secret.setName("db-password");
            secret.setState(LifecycleState.ACTIVE);
            when(secretRepository.findByProjectIdAndNameAndState("project", "db-password", LifecycleState.ACTIVE))
                    .thenReturn(Optional.of(secret));

            secretService.readSecretMetadata("project", "db-password");

            assertThat(singleLogLine())
                    .isEqualTo("Read secret metadata for project: project, secret: db-password, user: ldap:alice");
        }

        @Test
        @DisplayName("readSecretValue should log the secret name and the version read")
        void readSecretValue_logsSecretNameAndVersion() {
            SecretEntity secret = new SecretEntity();
            secret.setId("sid");
            secret.setName("db-password");
            secret.setState(LifecycleState.ACTIVE);
            secret.setCurrentDataVersion(3);
            when(secretRepository.findByProjectIdAndNameAndState("project", "db-password", LifecycleState.ACTIVE))
                    .thenReturn(Optional.of(secret));
            when(secretDataRepository.getBySecretIdAndDataVersion("sid", 3)).thenReturn(Optional.of(new SecretDataEntity()));

            secretService.readSecretValue("project", "db-password");

            assertThat(singleLogLine())
                    .isEqualTo("Read secret value for project: project, secret: db-password, version: 3, user: ldap:alice");
        }

        @Test
        @DisplayName("getSecretDataVersion should log the requested version")
        void getSecretDataVersion_logsRequestedVersion() {
            SecretEntity secret = new SecretEntity();
            secret.setId("sid");
            secret.setName("db-password");
            secret.setState(LifecycleState.ACTIVE);
            secret.setCurrentDataVersion(3);
            when(secretRepository.findByProjectIdAndNameAndState("project", "db-password", LifecycleState.ACTIVE))
                    .thenReturn(Optional.of(secret));
            when(secretDataRepository.getBySecretIdAndDataVersion("sid", 2)).thenReturn(Optional.of(new SecretDataEntity()));

            secretService.getSecretDataVersion("project", "db-password", 2, Optional.empty());

            assertThat(singleLogLine())
                    .isEqualTo("Read secret version for project: project, secret: db-password, version: 2, user: ldap:alice");
        }

        @Test
        @DisplayName("batchGetSecrets should log the requested and updated counts")
        void batchGetSecrets_logsCounts() {
            Secret secret = Secret.builder()
                    .id("s1").projectId("proj").name("db-pass").currentDataVersion(3).build();
            when(secretRepository.findActiveByProjectAndNames(Map.of("proj", List.of("db-pass"))))
                    .thenReturn(List.of(secret));

            secretService.batchGetSecrets(List.of(new SecretVersionEntry("proj", "db-pass", 3)));

            assertThat(singleLogLine())
                    .isEqualTo("Batch read secrets, requested: 1, updated: 0, user: ldap:alice");
        }

        @Test
        @DisplayName("a failed operation should not be logged as a successful one")
        void failedDestroy_isNotLogged() {
            Secret secret = new Secret();
            secret.setName("db-password");
            secret.setState(LifecycleState.ACTIVE);
            when(secretRepository.findByProjectIdAndName("project", "db-password")).thenReturn(Optional.of(secret));

            assertThatThrownBy(() -> secretService.destroySecret("project", "db-password"))
                    .isInstanceOf(BadRequestException.class);

            assertThat(appender.list).isEmpty();
        }
    }
}
