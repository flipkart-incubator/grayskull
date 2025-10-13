package com.flipkart.grayskull.models.db;

import com.flipkart.grayskull.models.enums.LifecycleState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the SecretData entity model.
 * These tests validate the entity's data structure and Lombok-generated
 * methods.
 */
class SecretDataTest {

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        @DisplayName("Should create SecretData with no-args constructor")
        void shouldCreateWithNoArgsConstructor() {
            // Act
            SecretData secretData = new SecretData();

            // Assert
            assertNotNull(secretData);
            assertNull(secretData.getSecretId());
            assertNull(secretData.getPublicPart());
            assertNull(secretData.getPrivatePart());
        }

        @Test
        @DisplayName("Should create SecretData with all-args constructor")
        void shouldCreateWithAllArgsConstructor() {
            // Arrange
            Instant now = Instant.now();

            // Act
            SecretData secretData = new SecretData(
                    "secret-123",
                    1L,
                    "public-value",
                    "private-value",
                    "kms-key-id",
                    "provider-ref",
                    "provider-version-ref",
                    now,
                    LifecycleState.ACTIVE);

            // Assert
            assertNotNull(secretData);
            assertEquals("secret-123", secretData.getSecretId());
            assertEquals(1L, secretData.getDataVersion());
            assertEquals("public-value", secretData.getPublicPart());
            assertEquals("private-value", secretData.getPrivatePart());
            assertEquals("kms-key-id", secretData.getKmsKeyId());
            assertEquals("provider-ref", secretData.getProviderSecretRef());
            assertEquals("provider-version-ref", secretData.getProviderSecretVersionRef());
            assertEquals(now, secretData.getLastUsed());
            assertEquals(LifecycleState.ACTIVE, secretData.getState());
        }
    }

    @Nested
    @DisplayName("Getter and Setter Tests")
    class GetterSetterTests {

        @Test
        @DisplayName("Should set and get all fields correctly")
        void shouldSetAndGetAllFields() {
            // Arrange
            SecretData secretData = new SecretData();
            Instant now = Instant.now();

            // Act
            secretData.setSecretId("secret-456");
            secretData.setDataVersion(5L);
            secretData.setPublicPart("public-data");
            secretData.setPrivatePart("private-data");
            secretData.setKmsKeyId("kms-123");
            secretData.setProviderSecretRef("aws-secret-ref");
            secretData.setProviderSecretVersionRef("version-abc");
            secretData.setLastUsed(now);
            secretData.setState(LifecycleState.DISABLED);

            // Assert
            assertEquals("secret-456", secretData.getSecretId());
            assertEquals(5L, secretData.getDataVersion());
            assertEquals("public-data", secretData.getPublicPart());
            assertEquals("private-data", secretData.getPrivatePart());
            assertEquals("kms-123", secretData.getKmsKeyId());
            assertEquals("aws-secret-ref", secretData.getProviderSecretRef());
            assertEquals("version-abc", secretData.getProviderSecretVersionRef());
            assertEquals(now, secretData.getLastUsed());
            assertEquals(LifecycleState.DISABLED, secretData.getState());
        }

        @Test
        @DisplayName("Should handle null values in optional fields")
        void shouldHandleNullValues() {
            // Arrange
            SecretData secretData = new SecretData();

            // Act
            secretData.setSecretId("test-id");
            secretData.setDataVersion(1L);
            secretData.setPublicPart(null);
            secretData.setPrivatePart("required-field");
            secretData.setKmsKeyId(null);
            secretData.setProviderSecretRef(null);
            secretData.setProviderSecretVersionRef(null);
            secretData.setLastUsed(null);
            secretData.setState(null);

            // Assert
            assertEquals("test-id", secretData.getSecretId());
            assertEquals(1L, secretData.getDataVersion());
            assertNull(secretData.getPublicPart());
            assertEquals("required-field", secretData.getPrivatePart());
            assertNull(secretData.getKmsKeyId());
            assertNull(secretData.getProviderSecretRef());
            assertNull(secretData.getProviderSecretVersionRef());
            assertNull(secretData.getLastUsed());
            assertNull(secretData.getState());
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
            SecretData data1 = new SecretData(
                    "secret-1", 1L, "public", "private",
                    "kms-1", "ref-1", "ver-1", now, LifecycleState.ACTIVE);

            SecretData data2 = new SecretData(
                    "secret-1", 1L, "public", "private",
                    "kms-1", "ref-1", "ver-1", now, LifecycleState.ACTIVE);

            // Act & Assert
            assertEquals(data1, data2);
            assertEquals(data1.hashCode(), data2.hashCode());
        }

        @Test
        @DisplayName("Should not be equal when secretIds are different")
        void shouldNotBeEqualWhenSecretIdsDifferent() {
            // Arrange
            SecretData data1 = new SecretData();
            data1.setSecretId("secret-1");
            data1.setDataVersion(1L);

            SecretData data2 = new SecretData();
            data2.setSecretId("secret-2");
            data2.setDataVersion(1L);

            // Act & Assert
            assertNotEquals(data1, data2);
            assertNotEquals(data1.hashCode(), data2.hashCode());
        }

        @Test
        @DisplayName("Should not be equal when dataVersions are different")
        void shouldNotBeEqualWhenDataVersionsDifferent() {
            // Arrange
            SecretData data1 = new SecretData();
            data1.setSecretId("secret-1");
            data1.setDataVersion(1L);

            SecretData data2 = new SecretData();
            data2.setSecretId("secret-1");
            data2.setDataVersion(2L);

            // Act & Assert
            assertNotEquals(data1, data2);
        }

        @Test
        @DisplayName("Should not be equal when private parts are different")
        void shouldNotBeEqualWhenPrivatePartsDifferent() {
            // Arrange
            SecretData data1 = new SecretData();
            data1.setSecretId("secret-1");
            data1.setPrivatePart("value-1");

            SecretData data2 = new SecretData();
            data2.setSecretId("secret-1");
            data2.setPrivatePart("value-2");

            // Act & Assert
            assertNotEquals(data1, data2);
        }

        @Test
        @DisplayName("Should be equal to itself")
        void shouldBeEqualToItself() {
            // Arrange
            SecretData secretData = new SecretData();
            secretData.setSecretId("secret-1");
            secretData.setDataVersion(1L);

            // Act & Assert
            assertEquals(secretData, secretData);
            assertEquals(secretData.hashCode(), secretData.hashCode());
        }

        @Test
        @DisplayName("Should not be equal to null")
        void shouldNotBeEqualToNull() {
            // Arrange
            SecretData secretData = new SecretData();
            secretData.setSecretId("secret-1");

            // Act & Assert
            assertNotEquals(null, secretData);
        }
    }

    @Nested
    @DisplayName("ToString Tests")
    class ToStringTests {

        @Test
        @DisplayName("Should generate valid toString output")
        void shouldGenerateValidToString() {
            // Arrange
            SecretData secretData = new SecretData();
            secretData.setSecretId("secret-789");
            secretData.setDataVersion(3L);
            secretData.setPublicPart("public-xyz");
            secretData.setPrivatePart("private-abc");

            // Act
            String toString = secretData.toString();

            // Assert
            assertNotNull(toString);
            assertTrue(toString.contains("secret-789"));
            assertTrue(toString.contains("3"));
            assertTrue(toString.contains("public-xyz"));
            // Note: toString might or might not include the private part depending on
            // Lombok config
        }

        @Test
        @DisplayName("Should handle toString with null fields")
        void shouldHandleToStringWithNullFields() {
            // Arrange
            SecretData secretData = new SecretData();
            secretData.setSecretId("secret-null");

            // Act
            String toString = secretData.toString();

            // Assert
            assertNotNull(toString);
            assertTrue(toString.contains("secret-null"));
        }
    }

    @Nested
    @DisplayName("Data Version Tests")
    class DataVersionTests {

        @Test
        @DisplayName("Should handle small version numbers")
        void shouldHandleSmallVersionNumbers() {
            // Arrange
            SecretData secretData = new SecretData();

            // Act
            secretData.setDataVersion(1L);

            // Assert
            assertEquals(1L, secretData.getDataVersion());
        }

        @Test
        @DisplayName("Should handle large version numbers")
        void shouldHandleLargeVersionNumbers() {
            // Arrange
            SecretData secretData = new SecretData();

            // Act
            secretData.setDataVersion(Long.MAX_VALUE);

            // Assert
            assertEquals(Long.MAX_VALUE, secretData.getDataVersion());
        }

        @Test
        @DisplayName("Should handle zero version number")
        void shouldHandleZeroVersionNumber() {
            // Arrange
            SecretData secretData = new SecretData();

            // Act
            secretData.setDataVersion(0L);

            // Assert
            assertEquals(0L, secretData.getDataVersion());
        }
    }

    @Nested
    @DisplayName("Lifecycle State Tests")
    class LifecycleStateTests {

        @Test
        @DisplayName("Should handle ACTIVE state")
        void shouldHandleActiveState() {
            // Arrange
            SecretData secretData = new SecretData();

            // Act
            secretData.setState(LifecycleState.ACTIVE);

            // Assert
            assertEquals(LifecycleState.ACTIVE, secretData.getState());
        }

        @Test
        @DisplayName("Should handle DISABLED state")
        void shouldHandleDisabledState() {
            // Arrange
            SecretData secretData = new SecretData();

            // Act
            secretData.setState(LifecycleState.DISABLED);

            // Assert
            assertEquals(LifecycleState.DISABLED, secretData.getState());
        }

        @Test
        @DisplayName("Should allow state transitions")
        void shouldAllowStateTransitions() {
            // Arrange
            SecretData secretData = new SecretData();
            secretData.setState(LifecycleState.ACTIVE);

            // Act
            secretData.setState(LifecycleState.DISABLED);

            // Assert
            assertEquals(LifecycleState.DISABLED, secretData.getState());
        }
    }

    @Nested
    @DisplayName("Timestamp Tests")
    class TimestampTests {

        @Test
        @DisplayName("Should handle lastUsed timestamp")
        void shouldHandleLastUsedTimestamp() {
            // Arrange
            SecretData secretData = new SecretData();
            Instant pastTime = Instant.now().minusSeconds(3600);

            // Act
            secretData.setLastUsed(pastTime);

            // Assert
            assertEquals(pastTime, secretData.getLastUsed());
        }

        @Test
        @DisplayName("Should handle future timestamp")
        void shouldHandleFutureTimestamp() {
            // Arrange
            SecretData secretData = new SecretData();
            Instant futureTime = Instant.now().plusSeconds(3600);

            // Act
            secretData.setLastUsed(futureTime);

            // Assert
            assertEquals(futureTime, secretData.getLastUsed());
        }

        @Test
        @DisplayName("Should handle null timestamp")
        void shouldHandleNullTimestamp() {
            // Arrange
            SecretData secretData = new SecretData();

            // Act
            secretData.setLastUsed(null);

            // Assert
            assertNull(secretData.getLastUsed());
        }
    }

    @Nested
    @DisplayName("Provider Integration Tests")
    class ProviderIntegrationTests {

        @Test
        @DisplayName("Should store provider references for external secrets")
        void shouldStoreProviderReferences() {
            // Arrange
            SecretData secretData = new SecretData();

            // Act
            secretData.setProviderSecretRef("arn:aws:secretsmanager:us-east-1:123456789:secret:my-secret");
            secretData.setProviderSecretVersionRef("AWSCURRENT");
            secretData.setKmsKeyId("arn:aws:kms:us-east-1:123456789:key/abc-123");

            // Assert
            assertTrue(secretData.getProviderSecretRef().contains("secretsmanager"));
            assertEquals("AWSCURRENT", secretData.getProviderSecretVersionRef());
            assertTrue(secretData.getKmsKeyId().contains("kms"));
        }

        @Test
        @DisplayName("Should handle self-managed secrets without provider references")
        void shouldHandleSelfManagedSecrets() {
            // Arrange
            SecretData secretData = new SecretData();

            // Act
            secretData.setSecretId("secret-123");
            secretData.setDataVersion(1L);
            secretData.setPrivatePart("self-managed-secret");
            secretData.setProviderSecretRef(null);
            secretData.setProviderSecretVersionRef(null);

            // Assert
            assertEquals("secret-123", secretData.getSecretId());
            assertEquals("self-managed-secret", secretData.getPrivatePart());
            assertNull(secretData.getProviderSecretRef());
            assertNull(secretData.getProviderSecretVersionRef());
        }
    }

    @Nested
    @DisplayName("Public and Private Part Tests")
    class PublicPrivatePartTests {

        @Test
        @DisplayName("Should store both public and private parts")
        void shouldStoreBothParts() {
            // Arrange
            SecretData secretData = new SecretData();

            // Act
            secretData.setPublicPart("username:admin");
            secretData.setPrivatePart("password:secret123");

            // Assert
            assertEquals("username:admin", secretData.getPublicPart());
            assertEquals("password:secret123", secretData.getPrivatePart());
        }

        @Test
        @DisplayName("Should allow empty strings")
        void shouldAllowEmptyStrings() {
            // Arrange
            SecretData secretData = new SecretData();

            // Act
            secretData.setPublicPart("");
            secretData.setPrivatePart("");

            // Assert
            assertEquals("", secretData.getPublicPart());
            assertEquals("", secretData.getPrivatePart());
        }

        @Test
        @DisplayName("Should handle large string values")
        void shouldHandleLargeStringValues() {
            // Arrange
            SecretData secretData = new SecretData();
            String largeString = "x".repeat(10000);

            // Act
            secretData.setPrivatePart(largeString);

            // Assert
            assertEquals(10000, secretData.getPrivatePart().length());
        }
    }
}
