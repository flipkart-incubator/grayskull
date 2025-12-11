package com.flipkart.grayskull.mappers;

import com.flipkart.grayskull.entities.SecretDataEntity;
import com.flipkart.grayskull.entities.SecretEntity;
import com.flipkart.grayskull.models.dto.request.CreateSecretRequest;
import com.flipkart.grayskull.models.dto.request.SecretDataPayload;
import com.flipkart.grayskull.models.dto.request.UpgradeSecretDataRequest;
import com.flipkart.grayskull.models.dto.response.SecretDataResponse;
import com.flipkart.grayskull.models.dto.response.SecretDataVersionResponse;
import com.flipkart.grayskull.models.dto.response.SecretMetadata;
import com.flipkart.grayskull.models.dto.response.SecretResponse;
import com.flipkart.grayskull.spi.models.Secret;
import com.flipkart.grayskull.spi.models.SecretData;
import com.flipkart.grayskull.spi.models.enums.LifecycleState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the SecretMapper interface.
 * These tests verify that MapStruct correctly generates the mapping
 * implementation.
 */
class SecretMapperTest {

    private SecretMapper secretMapper;

    @BeforeEach
    void setUp() {
        // Get the MapStruct-generated implementation
        secretMapper = Mappers.getMapper(SecretMapper.class);
    }

    @Nested
    @DisplayName("Request to Entity Mappings")
    class RequestToEntityTests {

        @Test
        @DisplayName("Should map CreateSecretRequest to Secret entity with correct defaults")
        void shouldMapCreateSecretRequestToSecret() {
            // Arrange
            SecretDataPayload payload = new SecretDataPayload("public-value", "private-value");
            CreateSecretRequest request = new CreateSecretRequest();
            request.setName("test-secret");
            request.setProvider("SELF");
            request.setProviderMeta(Map.of("key1", "value1"));
            request.setData(payload);

            String projectId = "project-123";
            String systemUser = "admin";

            // Act
            SecretEntity secret = secretMapper.requestToSecret(request, projectId, systemUser);

            // Assert
            assertNotNull(secret);
            assertEquals("test-secret", secret.getName());
            assertEquals(projectId, secret.getProjectId());
            assertEquals(systemUser, secret.getCreatedBy());
            assertEquals(systemUser, secret.getUpdatedBy());
            assertEquals(1, secret.getMetadataVersion());
            assertEquals(1, secret.getCurrentDataVersion());
            assertEquals("SELF", secret.getProvider());
            assertEquals(Map.of("key1", "value1"), secret.getProviderMeta());
        }

        @Test
        @DisplayName("Should map CreateSecretRequest to SecretData entity")
        void shouldMapCreateSecretRequestToSecretData() {
            // Arrange
            SecretDataPayload payload = new SecretDataPayload("public-part", "private-part");
            CreateSecretRequest request = new CreateSecretRequest();
            request.setName("test-secret");
            request.setData(payload);

            String secretId = "secret-123";

            // Act
            SecretDataEntity secretData = secretMapper.requestToSecretData(request, secretId);

            // Assert
            assertNotNull(secretData);
            assertEquals(secretId, secretData.getSecretId());
            assertEquals("public-part", secretData.getPublicPart());
            assertEquals("private-part", secretData.getPrivatePart());
            assertEquals(1L, secretData.getDataVersion());
        }

        @Test
        @DisplayName("Should map UpgradeSecretDataRequest to SecretData entity")
        void shouldMapUpgradeSecretDataRequestToSecretData() {
            // Arrange
            UpgradeSecretDataRequest request = new UpgradeSecretDataRequest();
            request.setPublicPart("new-public");
            request.setPrivatePart("new-private");

            Secret secret = Secret.builder()
                    .id("secret-456")
                    .name("test-secret")
                    .projectId("project-123")
                    .currentDataVersion(1)
                    .build();

            int newVersion = 2;

            // Act
            SecretDataEntity secretData = secretMapper.upgradeRequestToSecretData(request, secret, newVersion);

            // Assert
            assertNotNull(secretData);
            assertEquals("secret-456", secretData.getSecretId());
            assertEquals("new-public", secretData.getPublicPart());
            assertEquals("new-private", secretData.getPrivatePart());
            assertEquals(2, secretData.getDataVersion());
        }

        @Test
        @DisplayName("Should handle null providerMeta and systemLabels gracefully")
        void shouldHandleNullFieldsGracefully() {
            // Arrange
            SecretDataPayload payload = new SecretDataPayload("public", "private");
            CreateSecretRequest request = new CreateSecretRequest();
            request.setName("minimal-secret");
            request.setData(payload);
            // providerMeta and systemLabels are null

            // Act
            SecretEntity secret = secretMapper.requestToSecret(request, "proj-1", "user1");

            // Assert
            assertNotNull(secret);
            assertEquals("minimal-secret", secret.getName());
            assertNull(secret.getSystemLabels());
        }
    }

    @Nested
    @DisplayName("Entity to Response Mappings")
    class EntityToResponseTests {

        @Test
        @DisplayName("Should map Secret to SecretMetadata response")
        void shouldMapSecretToSecretMetadata() {
            // Arrange
            Instant now = Instant.now();
            Secret secret = Secret.builder()
                    .id("secret-789")
                    .projectId("project-123")
                    .name("metadata-secret")
                    .currentDataVersion(3)
                    .metadataVersion(1)
                    .state(LifecycleState.ACTIVE)
                    .provider("SELF")
                    .lastRotated(now)
                    .creationTime(now)
                    .updatedTime(now)
                    .createdBy("user1")
                    .updatedBy("user2")
                    .systemLabels(Map.of("env", "staging"))
                    .providerMeta(Map.of("team", "backend"))
                    .build();

            // Act
            SecretMetadata metadata = secretMapper.secretToSecretMetadata(secret);

            // Assert
            assertNotNull(metadata);
            assertEquals("project-123", metadata.getProjectId());
            assertEquals("metadata-secret", metadata.getName());
            assertEquals(3, metadata.getCurrentDataVersion());
            assertEquals(1, metadata.getMetadataVersion());
            assertEquals("ACTIVE", metadata.getState());
            assertEquals("SELF", metadata.getProvider());
            assertEquals(now, metadata.getLastRotated());
            assertEquals(now, metadata.getCreationTime());
            assertEquals(now, metadata.getUpdatedTime());
            assertEquals("user1", metadata.getCreatedBy());
            assertEquals("user2", metadata.getUpdatedBy());
            assertEquals(Map.of("env", "staging"), metadata.getSystemLabels());
            assertEquals(Map.of("team", "backend"), metadata.getProviderMeta());
        }

        @Test
        @DisplayName("Should map Secret to SecretResponse")
        void shouldMapSecretToSecretResponse() {
            // Arrange
            Instant now = Instant.now();
            Secret secret = Secret.builder()
                    .id("secret-101")
                    .name("created-secret")
                    .projectId("project-456")
                    .currentDataVersion(1)
                    .metadataVersion(1)
                    .state(LifecycleState.ACTIVE)
                    .creationTime(now)
                    .updatedTime(now)
                    .createdBy("creator")
                    .updatedBy("creator")
                    .build();

            // Act
            SecretResponse response = secretMapper.secretToSecretResponse(secret);

            // Assert
            assertNotNull(response);
            assertEquals("created-secret", response.getName());
            assertEquals(1, response.getCurrentDataVersion());
        }

        @Test
        @DisplayName("Should map Secret and SecretData to SecretDataResponse")
        void shouldMapToSecretDataResponse() {
            // Arrange
            Instant now = Instant.now();
            Secret secret = Secret.builder()
                    .id("secret-202")
                    .name("data-secret")
                    .state(LifecycleState.ACTIVE)
                    .lastRotated(now.minusSeconds(3600))
                    .creationTime(now.minusSeconds(7200))
                    .updatedTime(now)
                    .createdBy("user1")
                    .updatedBy("user2")
                    .build();

            SecretData secretData = SecretData.builder()
                    .secretId("secret-202")
                    .dataVersion(2L)
                    .publicPart("public-data")
                    .privatePart("private-data")
                    .state(LifecycleState.ACTIVE)
                    .build();

            // Act
            SecretDataResponse response = secretMapper.toSecretDataResponse(secret, secretData);

            // Assert
            assertNotNull(response);
            assertEquals(2L, response.getDataVersion());
            assertEquals("public-data", response.getPublicPart());
            assertEquals("private-data", response.getPrivatePart());
            assertEquals("ACTIVE", response.getState());
            assertEquals(now.minusSeconds(3600), response.getLastRotated());
            assertEquals(now.minusSeconds(7200), response.getCreationTime());
            assertEquals(now, response.getUpdatedTime());
            assertEquals("user1", response.getCreatedBy());
            assertEquals("user2", response.getUpdatedBy());
        }

        @Test
        @DisplayName("Should map SecretData to SecretDataVersionResponse")
        void shouldMapSecretDataToSecretDataVersionResponse() {
            // Arrange
            Instant now = Instant.now();
            Secret secret = Secret.builder()
                    .state(LifecycleState.DISABLED)
                    .lastRotated(now)
                    .creationTime(now)
                    .updatedTime(now)
                    .createdBy("admin")
                    .updatedBy("admin")
                    .build();

            SecretData secretData = SecretData.builder()
                    .dataVersion(1L)
                    .publicPart("old-public")
                    .privatePart("old-private")
                    .build();

            // Act
            SecretDataVersionResponse response = secretMapper.secretDataToSecretDataVersionResponse(secret, secretData);

            // Assert
            assertNotNull(response);
            assertEquals(1L, response.getDataVersion());
            assertEquals("old-public", response.getPublicPart());
            assertEquals("old-private", response.getPrivatePart());
            assertEquals("DISABLED", response.getState());
            assertEquals(now, response.getLastRotated());
        }
    }

    @Nested
    @DisplayName("Utility Method Tests")
    class UtilityMethodTests {

        @Test
        @DisplayName("Should convert LifecycleState to string correctly")
        void shouldConvertLifecycleStateToString() {
            // Act & Assert
            assertEquals("ACTIVE", secretMapper.lifecycleStateToString(LifecycleState.ACTIVE));
            assertEquals("DISABLED", secretMapper.lifecycleStateToString(LifecycleState.DISABLED));
        }

        @Test
        @DisplayName("Should handle null LifecycleState")
        void shouldHandleNullLifecycleState() {
            // Act & Assert
            assertNull(secretMapper.lifecycleStateToString(null));
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Should handle empty strings in requests")
        void shouldHandleEmptyStrings() {
            // Arrange
            SecretDataPayload payload = new SecretDataPayload("", "");
            CreateSecretRequest request = new CreateSecretRequest();
            request.setName("");
            request.setData(payload);

            // Act
            SecretDataEntity secretData = secretMapper.requestToSecretData(request, "");

            // Assert
            assertNotNull(secretData);
            assertEquals("", secretData.getSecretId());
            assertEquals("", secretData.getPublicPart());
            assertEquals("", secretData.getPrivatePart());
        }

        @Test
        @DisplayName("Should handle large version numbers")
        void shouldHandleLargeVersionNumbers() {
            // Arrange
            UpgradeSecretDataRequest request = new UpgradeSecretDataRequest();
            request.setPublicPart("public");
            request.setPrivatePart("private");

            Secret secret = Secret.builder()
                    .id("secret-999")
                    .currentDataVersion(Integer.MAX_VALUE - 1)
                    .build();

            int largeVersion = Integer.MAX_VALUE;

            // Act
            SecretDataEntity secretData = secretMapper.upgradeRequestToSecretData(request, secret, largeVersion);

            // Assert
            assertNotNull(secretData);
            assertEquals(Integer.MAX_VALUE, secretData.getDataVersion());
        }
    }
}
