package com.flipkart.grayskull.models.db;

import com.flipkart.grayskull.spi.models.Secret;
import com.flipkart.grayskull.spi.models.SecretData;
import com.flipkart.grayskull.spi.models.enums.LifecycleState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static com.flipkart.grayskull.service.utils.SecretProviderConstants.PROVIDER_SELF;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the Secret entity model.
 * These tests validate the entity's data structure, builder pattern,
 * and Lombok-generated methods.
 */
class SecretTest {

    @Nested
    @DisplayName("Builder Pattern Tests")
    class BuilderPatternTests {

        @Test
        @DisplayName("Should create Secret using builder with all fields")
        void shouldCreateSecretWithAllFields() {
            // Arrange
            Instant now = Instant.now();
            Map<String, String> systemLabels = Map.of("env", "prod", "team", "backend");
            Map<String, Object> providerMeta = Map.of("region", "us-east-1", "replicas", 3);

            // Act
            Secret secret = Secret.builder()
                    .id("secret-123")
                    .projectId("project-456")
                    .name("test-secret")
                    .systemLabels(systemLabels)
                    .currentDataVersion(5)
                    .lastRotated(now)
                    .state(LifecycleState.ACTIVE)
                    .provider("AWS_SECRETS_MANAGER")
                    .providerMeta(providerMeta)
                    .metadataVersion(2)
                    .version(10L)
                    .creationTime(now)
                    .updatedTime(now)
                    .createdBy("user1")
                    .updatedBy("user2")
                    .build();

            // Assert
            assertNotNull(secret);
            assertEquals("secret-123", secret.getId());
            assertEquals("project-456", secret.getProjectId());
            assertEquals("test-secret", secret.getName());
            assertEquals(systemLabels, secret.getSystemLabels());
            assertEquals(5, secret.getCurrentDataVersion());
            assertEquals(now, secret.getLastRotated());
            assertEquals(LifecycleState.ACTIVE, secret.getState());
            assertEquals("AWS_SECRETS_MANAGER", secret.getProvider());
            assertEquals(providerMeta, secret.getProviderMeta());
            assertEquals(2, secret.getMetadataVersion());
            assertEquals(10L, secret.getVersion());
            assertEquals(now, secret.getCreationTime());
            assertEquals(now, secret.getUpdatedTime());
            assertEquals("user1", secret.getCreatedBy());
            assertEquals("user2", secret.getUpdatedBy());
        }

        @Test
        @DisplayName("Should create Secret with minimal fields")
        void shouldCreateSecretWithMinimalFields() {
            // Act
            Secret secret = Secret.builder()
                    .id("secret-min")
                    .projectId("project-min")
                    .name("minimal-secret")
                    .build();

            // Assert
            assertNotNull(secret);
            assertEquals("secret-min", secret.getId());
            assertEquals("project-min", secret.getProjectId());
            assertEquals("minimal-secret", secret.getName());
            // Default state should be ACTIVE
            assertEquals(LifecycleState.ACTIVE, secret.getState());
        }

        @Test
        @DisplayName("Should apply default state as ACTIVE")
        void shouldApplyDefaultState() {
            // Act
            Secret secret = Secret.builder()
                    .id("test-id")
                    .name("test-name")
                    .build();

            // Assert
            assertEquals(LifecycleState.ACTIVE, secret.getState());
        }
    }

    @Nested
    @DisplayName("Getter and Setter Tests")
    class GetterSetterTests {

        @Test
        @DisplayName("Should set and get all fields correctly")
        void shouldSetAndGetFields() {
            // Arrange
            Secret secret = new Secret();
            Instant now = Instant.now();
            Map<String, String> labels = new HashMap<>();
            labels.put("key", "value");

            // Act
            secret.setId("id-1");
            secret.setProjectId("project-1");
            secret.setName("my-secret");
            secret.setSystemLabels(labels);
            secret.setCurrentDataVersion(3);
            secret.setLastRotated(now);
            secret.setState(LifecycleState.DISABLED);
            secret.setProvider(PROVIDER_SELF);
            secret.setMetadataVersion(1);
            secret.setVersion(5L);
            secret.setCreationTime(now);
            secret.setUpdatedTime(now);
            secret.setCreatedBy("creator");
            secret.setUpdatedBy("updater");

            // Assert
            assertEquals("id-1", secret.getId());
            assertEquals("project-1", secret.getProjectId());
            assertEquals("my-secret", secret.getName());
            assertEquals(labels, secret.getSystemLabels());
            assertEquals(3, secret.getCurrentDataVersion());
            assertEquals(now, secret.getLastRotated());
            assertEquals(LifecycleState.DISABLED, secret.getState());
            assertEquals(PROVIDER_SELF, secret.getProvider());
            assertEquals(1, secret.getMetadataVersion());
            assertEquals(5L, secret.getVersion());
            assertEquals(now, secret.getCreationTime());
            assertEquals(now, secret.getUpdatedTime());
            assertEquals("creator", secret.getCreatedBy());
            assertEquals("updater", secret.getUpdatedBy());
        }

        @Test
        @DisplayName("Should handle null values in optional fields")
        void shouldHandleNullValues() {
            // Arrange
            Secret secret = new Secret();

            // Act
            secret.setId("test-id");
            secret.setProjectId("test-project");
            secret.setName("test-name");
            secret.setSystemLabels(null);
            secret.setLastRotated(null);
            secret.setProvider(null);
            secret.setProviderMeta(null);

            // Assert
            assertEquals("test-id", secret.getId());
            assertEquals("test-project", secret.getProjectId());
            assertEquals("test-name", secret.getName());
            assertNull(secret.getSystemLabels());
            assertNull(secret.getLastRotated());
            assertNull(secret.getProvider());
            assertNull(secret.getProviderMeta());
        }
    }

    @Nested
    @DisplayName("Equals and HashCode Tests")
    class EqualsHashCodeTests {

        @Test
        @DisplayName("Should be equal when all fields are the same")
        void shouldBeEqualWhenFieldsAreSame() {
            // Arrange
            Instant now = Instant.now();
            Secret secret1 = Secret.builder()
                    .id("id-1")
                    .projectId("project-1")
                    .name("secret-name")
                    .currentDataVersion(1)
                    .metadataVersion(1)
                    .state(LifecycleState.ACTIVE)
                    .creationTime(now)
                    .build();

            Secret secret2 = Secret.builder()
                    .id("id-1")
                    .projectId("project-1")
                    .name("secret-name")
                    .currentDataVersion(1)
                    .metadataVersion(1)
                    .state(LifecycleState.ACTIVE)
                    .creationTime(now)
                    .build();

            // Act & Assert
            assertEquals(secret1, secret2);
            assertEquals(secret1.hashCode(), secret2.hashCode());
        }

        @Test
        @DisplayName("Should not be equal when ids are different")
        void shouldNotBeEqualWhenIdsDifferent() {
            // Arrange
            Secret secret1 = Secret.builder()
                    .id("id-1")
                    .name("secret-name")
                    .build();

            Secret secret2 = Secret.builder()
                    .id("id-2")
                    .name("secret-name")
                    .build();

            // Act & Assert
            assertNotEquals(secret1, secret2);
            assertNotEquals(secret1.hashCode(), secret2.hashCode());
        }

        @Test
        @DisplayName("Should not be equal when names are different")
        void shouldNotBeEqualWhenNamesDifferent() {
            // Arrange
            Secret secret1 = Secret.builder()
                    .id("id-1")
                    .name("secret-1")
                    .build();

            Secret secret2 = Secret.builder()
                    .id("id-1")
                    .name("secret-2")
                    .build();

            // Act & Assert
            assertNotEquals(secret1, secret2);
        }

        @Test
        @DisplayName("Should be equal to itself")
        void shouldBeEqualToItself() {
            // Arrange
            Secret secret = Secret.builder()
                    .id("id-1")
                    .name("secret-name")
                    .build();

            // Act & Assert
            assertEquals(secret, secret);
            assertEquals(secret.hashCode(), secret.hashCode());
        }

        @Test
        @DisplayName("Should not be equal to null")
        void shouldNotBeEqualToNull() {
            // Arrange
            Secret secret = Secret.builder()
                    .id("id-1")
                    .build();

            // Act & Assert
            assertNotEquals(null, secret);
        }
    }

    @Nested
    @DisplayName("ToString Tests")
    class ToStringTests {

        @Test
        @DisplayName("Should generate valid toString output")
        void shouldGenerateValidToString() {
            // Arrange
            Secret secret = Secret.builder()
                    .id("secret-123")
                    .projectId("project-456")
                    .name("test-secret")
                    .currentDataVersion(1)
                    .state(LifecycleState.ACTIVE)
                    .build();

            // Act
            String toString = secret.toString();

            // Assert
            assertNotNull(toString);
            assertTrue(toString.contains("secret-123"));
            assertTrue(toString.contains("project-456"));
            assertTrue(toString.contains("test-secret"));
            assertTrue(toString.contains("ACTIVE"));
        }
    }

    @Nested
    @DisplayName("Transient Field Tests")
    class TransientFieldTests {

        @Test
        @DisplayName("Should handle transient data field")
        void shouldHandleTransientDataField() {
            // Arrange
            SecretData secretData = new SecretData();
            secretData.setSecretId("secret-123");
            secretData.setDataVersion(1L);
            secretData.setPrivatePart("sensitive-data");

            Secret secret = Secret.builder()
                    .id("secret-123")
                    .name("test-secret")
                    .build();

            // Act
            secret.setData(secretData);

            // Assert
            assertNotNull(secret.getData());
            assertEquals("secret-123", secret.getData().getSecretId());
            assertEquals(1L, secret.getData().getDataVersion());
            assertEquals("sensitive-data", secret.getData().getPrivatePart());
        }

        @Test
        @DisplayName("Should allow null transient data field")
        void shouldAllowNullTransientDataField() {
            // Arrange
            Secret secret = Secret.builder()
                    .id("secret-123")
                    .name("test-secret")
                    .build();

            // Act & Assert
            assertNull(secret.getData());
        }
    }

    @Nested
    @DisplayName("Version Field Tests")
    class VersionFieldTests {

        @Test
        @DisplayName("Should handle optimistic locking version field")
        void shouldHandleVersionField() {
            // Arrange & Act
            Secret secret = Secret.builder()
                    .id("secret-123")
                    .version(1L)
                    .build();

            // Assert
            assertEquals(1L, secret.getVersion());
        }

        @Test
        @DisplayName("Should allow null version field initially")
        void shouldAllowNullVersionInitially() {
            // Arrange & Act
            Secret secret = Secret.builder()
                    .id("secret-123")
                    .build();

            // Assert
            assertNull(secret.getVersion());
        }

        @Test
        @DisplayName("Should allow version to be updated")
        void shouldAllowVersionToBeUpdated() {
            // Arrange
            Secret secret = Secret.builder()
                    .id("secret-123")
                    .version(1L)
                    .build();

            // Act
            secret.setVersion(2L);

            // Assert
            assertEquals(2L, secret.getVersion());
        }
    }

    @Nested
    @DisplayName("Complex Field Tests")
    class ComplexFieldTests {

        @Test
        @DisplayName("Should handle complex systemLabels map")
        void shouldHandleComplexSystemLabels() {
            // Arrange
            Map<String, String> labels = new HashMap<>();
            labels.put("environment", "production");
            labels.put("team", "platform");
            labels.put("compliance", "pci-dss");
            labels.put("data-classification", "confidential");

            // Act
            Secret secret = Secret.builder()
                    .id("secret-123")
                    .systemLabels(labels)
                    .build();

            // Assert
            assertEquals(4, secret.getSystemLabels().size());
            assertEquals("production", secret.getSystemLabels().get("environment"));
            assertEquals("platform", secret.getSystemLabels().get("team"));
            assertEquals("pci-dss", secret.getSystemLabels().get("compliance"));
            assertEquals("confidential", secret.getSystemLabels().get("data-classification"));
        }

        @Test
        @DisplayName("Should handle complex providerMeta map")
        void shouldHandleComplexProviderMeta() {
            // Arrange
            Map<String, Object> providerMeta = new HashMap<>();
            providerMeta.put("region", "us-west-2");
            providerMeta.put("replication_enabled", true);
            providerMeta.put("retention_days", 90);
            providerMeta.put("kms_key_id", "arn:aws:kms:us-west-2:123456789:key/abc-123");

            // Act
            Secret secret = Secret.builder()
                    .id("secret-123")
                    .providerMeta(providerMeta)
                    .build();

            // Assert
            assertEquals(4, secret.getProviderMeta().size());
            assertEquals("us-west-2", secret.getProviderMeta().get("region"));
            assertEquals(true, secret.getProviderMeta().get("replication_enabled"));
            assertEquals(90, secret.getProviderMeta().get("retention_days"));
            assertTrue(secret.getProviderMeta().get("kms_key_id").toString().contains("arn:aws:kms"));
        }

        @Test
        @DisplayName("Should handle mutable collections")
        void shouldHandleMutableCollections() {
            // Arrange
            Map<String, String> labels = new HashMap<>();
            labels.put("env", "dev");

            Secret secret = Secret.builder()
                    .id("secret-123")
                    .systemLabels(labels)
                    .build();

            // Act - modify the labels after creation
            secret.getSystemLabels().put("team", "backend");

            // Assert
            assertEquals(2, secret.getSystemLabels().size());
            assertEquals("dev", secret.getSystemLabels().get("env"));
            assertEquals("backend", secret.getSystemLabels().get("team"));
        }
    }
}
