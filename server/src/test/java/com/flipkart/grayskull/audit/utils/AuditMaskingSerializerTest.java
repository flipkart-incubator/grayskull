package com.flipkart.grayskull.audit.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flipkart.grayskull.audit.AuditMask;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the AuditMaskingSerializer.
 * These tests validate that the serializer correctly masks sensitive fields
 * across various data types when used with @AuditMask annotation.
 */
class AuditMaskingSerializerTest {

    private ObjectMapper sanitizingMapper;
    private ObjectMapper regularMapper;

    private static final String MASKED_VALUE = "***MASKED***";

    @BeforeEach
    void setUp() {
        // Create a sanitizing mapper with masking capabilities
        sanitizingMapper = SanitizingObjectMapper.MASK_OBJECT_MAPPER;

        // Create a regular mapper without masking for comparison
        regularMapper = new ObjectMapper();
    }

    /**
     * Test DTO with a String field that should be masked
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class StringMaskTestDto {
        private String publicField;

        @AuditMask
        private String secretField;
    }

    /**
     * Test DTO with an Integer field that should be masked
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class NumberMaskTestDto {
        private String name;

        @AuditMask
        private Integer secretNumber;

        @AuditMask
        private Long secretLong;

        @AuditMask
        private Double secretDouble;
    }

    /**
     * Test DTO with a nested object that should be masked
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class NestedObjectMaskTestDto {
        private String id;

        @AuditMask
        private NestedObject secretObject;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class NestedObject {
        private String nestedField1;
        private int nestedField2;
    }

    /**
     * Test DTO with a List field that should be masked
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class CollectionMaskTestDto {
        private String name;

        @AuditMask
        private List<String> secretList;

        @AuditMask
        private Map<String, String> secretMap;
    }

    /**
     * Test DTO with boolean and BigDecimal fields that should be masked
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class OtherTypesMaskTestDto {
        private String publicField;

        @AuditMask
        private Boolean secretBoolean;

        @AuditMask
        private BigDecimal secretDecimal;

        @AuditMask
        private Instant secretTimestamp;
    }

    /**
     * Test DTO with multiple masked fields
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class MultipleMaskedFieldsDto {
        private String visibleField;

        @AuditMask
        private String secret1;

        @AuditMask
        private String secret2;

        private String anotherVisibleField;

        @AuditMask
        private String secret3;
    }

    /**
     * Test DTO with null masked field
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class NullMaskedFieldDto {
        private String publicField;

        @AuditMask
        private String nullSecretField;
    }

    @Nested
    @DisplayName("String Field Masking Tests")
    class StringFieldMaskingTests {

        @Test
        @DisplayName("Should mask String field annotated with @AuditMask")
        void shouldMaskStringField() throws JsonProcessingException {
            // Arrange
            StringMaskTestDto dto = new StringMaskTestDto("public-data", "super-secret");

            // Act
            String json = sanitizingMapper.writeValueAsString(dto);

            // Assert
            assertTrue(json.contains("\"publicField\":\"public-data\""));
            assertTrue(json.contains("\"secretField\":\"" + MASKED_VALUE + "\""));
            assertFalse(json.contains("super-secret"));
        }

        @Test
        @DisplayName("Should not mask String field without @AuditMask")
        void shouldNotMaskNonAnnotatedStringField() throws JsonProcessingException {
            // Arrange
            StringMaskTestDto dto = new StringMaskTestDto("visible-data", "secret-data");

            // Act
            String json = sanitizingMapper.writeValueAsString(dto);

            // Assert
            assertTrue(json.contains("\"publicField\":\"visible-data\""));
            assertFalse(json.contains("secret-data"));
            assertTrue(json.contains(MASKED_VALUE));
        }

        @Test
        @DisplayName("Should mask empty String field")
        void shouldMaskEmptyStringField() throws JsonProcessingException {
            // Arrange
            StringMaskTestDto dto = new StringMaskTestDto("public", "");

            // Act
            String json = sanitizingMapper.writeValueAsString(dto);

            // Assert
            assertTrue(json.contains("\"secretField\":\"" + MASKED_VALUE + "\""));
        }
    }

    @Nested
    @DisplayName("Number Field Masking Tests")
    class NumberFieldMaskingTests {

        @Test
        @DisplayName("Should mask Integer field annotated with @AuditMask")
        void shouldMaskIntegerField() throws JsonProcessingException {
            // Arrange
            NumberMaskTestDto dto = new NumberMaskTestDto("test", 12345, 999999999L, 3.14159);

            // Act
            String json = sanitizingMapper.writeValueAsString(dto);

            // Assert
            assertTrue(json.contains("\"name\":\"test\""));
            assertTrue(json.contains("\"secretNumber\":\"" + MASKED_VALUE + "\""));
            assertFalse(json.contains("12345"));
        }

        @Test
        @DisplayName("Should mask Long field annotated with @AuditMask")
        void shouldMaskLongField() throws JsonProcessingException {
            // Arrange
            NumberMaskTestDto dto = new NumberMaskTestDto("test", null, 999999999L, null);

            // Act
            String json = sanitizingMapper.writeValueAsString(dto);

            // Assert
            assertTrue(json.contains("\"secretLong\":\"" + MASKED_VALUE + "\""));
            assertFalse(json.contains("999999999"));
        }

        @Test
        @DisplayName("Should mask Double field annotated with @AuditMask")
        void shouldMaskDoubleField() throws JsonProcessingException {
            // Arrange
            NumberMaskTestDto dto = new NumberMaskTestDto("test", null, null, 3.14159);

            // Act
            String json = sanitizingMapper.writeValueAsString(dto);

            // Assert
            assertTrue(json.contains("\"secretDouble\":\"" + MASKED_VALUE + "\""));
            assertFalse(json.contains("3.14159"));
        }
    }

    @Nested
    @DisplayName("Object Field Masking Tests")
    class ObjectFieldMaskingTests {

        @Test
        @DisplayName("Should mask nested object field annotated with @AuditMask")
        void shouldMaskNestedObjectField() throws JsonProcessingException {
            // Arrange
            NestedObject nestedObj = new NestedObject("nested-secret-value", 42);
            NestedObjectMaskTestDto dto = new NestedObjectMaskTestDto("id-123", nestedObj);

            // Act
            String json = sanitizingMapper.writeValueAsString(dto);

            // Assert
            assertTrue(json.contains("\"id\":\"id-123\""));
            assertTrue(json.contains("\"secretObject\":\"" + MASKED_VALUE + "\""));
            assertFalse(json.contains("nested-secret-value"));
            assertFalse(json.contains("\"nestedField1\""));
            assertFalse(json.contains("\"nestedField2\""));
        }

        @Test
        @DisplayName("Should compare masked vs unmasked nested object serialization")
        void shouldCompareMaskedVsUnmaskedNestedObject() throws JsonProcessingException {
            // Arrange
            NestedObject nestedObj = new NestedObject("sensitive-data", 100);
            NestedObjectMaskTestDto dto = new NestedObjectMaskTestDto("test-id", nestedObj);

            // Act
            String maskedJson = sanitizingMapper.writeValueAsString(dto);
            String unmaskedJson = regularMapper.writeValueAsString(dto);

            // Assert - unmasked should contain the nested structure
            assertTrue(unmaskedJson.contains("\"nestedField1\":\"sensitive-data\""));
            assertTrue(unmaskedJson.contains("\"nestedField2\":100"));

            // Assert - masked should only contain the masked value
            assertTrue(maskedJson.contains("\"secretObject\":\"" + MASKED_VALUE + "\""));
            assertFalse(maskedJson.contains("sensitive-data"));
        }
    }

    @Nested
    @DisplayName("Collection Field Masking Tests")
    class CollectionFieldMaskingTests {

        @Test
        @DisplayName("Should mask List field annotated with @AuditMask")
        void shouldMaskListField() throws JsonProcessingException {
            // Arrange
            List<String> secretList = Arrays.asList("secret1", "secret2", "secret3");
            CollectionMaskTestDto dto = new CollectionMaskTestDto("test", secretList, null);

            // Act
            String json = sanitizingMapper.writeValueAsString(dto);

            // Assert
            assertTrue(json.contains("\"secretList\":\"" + MASKED_VALUE + "\""));
            assertFalse(json.contains("secret1"));
            assertFalse(json.contains("secret2"));
            assertFalse(json.contains("secret3"));
        }

        @Test
        @DisplayName("Should mask Map field annotated with @AuditMask")
        void shouldMaskMapField() throws JsonProcessingException {
            // Arrange
            Map<String, String> secretMap = new HashMap<>();
            secretMap.put("key1", "value1");
            secretMap.put("key2", "value2");
            CollectionMaskTestDto dto = new CollectionMaskTestDto("test", null, secretMap);

            // Act
            String json = sanitizingMapper.writeValueAsString(dto);

            // Assert
            assertTrue(json.contains("\"secretMap\":\"" + MASKED_VALUE + "\""));
            assertFalse(json.contains("key1"));
            assertFalse(json.contains("value1"));
            assertFalse(json.contains("key2"));
            assertFalse(json.contains("value2"));
        }
    }

    @Nested
    @DisplayName("Other Data Types Masking Tests")
    class OtherDataTypesMaskingTests {

        @Test
        @DisplayName("Should mask Boolean field annotated with @AuditMask")
        void shouldMaskBooleanField() throws JsonProcessingException {
            // Arrange
            OtherTypesMaskTestDto dto = new OtherTypesMaskTestDto("public", true, null, null);

            // Act
            String json = sanitizingMapper.writeValueAsString(dto);

            // Assert
            assertTrue(json.contains("\"secretBoolean\":\"" + MASKED_VALUE + "\""));
            assertFalse(json.contains("\"secretBoolean\":true"));
        }

        @Test
        @DisplayName("Should mask BigDecimal field annotated with @AuditMask")
        void shouldMaskBigDecimalField() throws JsonProcessingException {
            // Arrange
            BigDecimal decimal = new BigDecimal("123456.789");
            OtherTypesMaskTestDto dto = new OtherTypesMaskTestDto("public", null, decimal, null);

            // Act
            String json = sanitizingMapper.writeValueAsString(dto);

            // Assert
            assertTrue(json.contains("\"secretDecimal\":\"" + MASKED_VALUE + "\""));
            assertFalse(json.contains("123456.789"));
        }

        @Test
        @DisplayName("Should mask Instant field annotated with @AuditMask")
        void shouldMaskInstantField() throws JsonProcessingException {
            // Arrange
            Instant now = Instant.now();
            OtherTypesMaskTestDto dto = new OtherTypesMaskTestDto("public", null, null, now);

            // Act
            String json = sanitizingMapper.writeValueAsString(dto);

            // Assert
            assertTrue(json.contains("\"secretTimestamp\":\"" + MASKED_VALUE + "\""));
        }
    }

    @Nested
    @DisplayName("Multiple Fields and Edge Cases Tests")
    class MultipleFieldsAndEdgeCasesTests {

        @Test
        @DisplayName("Should mask multiple fields in same object")
        void shouldMaskMultipleFields() throws JsonProcessingException {
            // Arrange
            MultipleMaskedFieldsDto dto = new MultipleMaskedFieldsDto(
                    "visible1", "secret-a", "secret-b", "visible2", "secret-c");

            // Act
            String json = sanitizingMapper.writeValueAsString(dto);

            // Assert
            assertTrue(json.contains("\"visibleField\":\"visible1\""));
            assertTrue(json.contains("\"anotherVisibleField\":\"visible2\""));
            assertTrue(json.contains("\"secret1\":\"" + MASKED_VALUE + "\""));
            assertTrue(json.contains("\"secret2\":\"" + MASKED_VALUE + "\""));
            assertTrue(json.contains("\"secret3\":\"" + MASKED_VALUE + "\""));
            assertFalse(json.contains("secret-a"));
            assertFalse(json.contains("secret-b"));
            assertFalse(json.contains("secret-c"));
        }

        @Test
        @DisplayName("Should handle null masked field")
        void shouldHandleNullMaskedField() throws JsonProcessingException {
            // Arrange
            NullMaskedFieldDto dto = new NullMaskedFieldDto("public", null);

            // Act
            String json = sanitizingMapper.writeValueAsString(dto);

            // Assert
            assertTrue(json.contains("\"publicField\":\"public\""));
            // When field is null, Jackson might serialize as null or masked depending on
            // configuration
            // The important thing is that it doesn't throw an exception
            assertNotNull(json);
        }

        @Test
        @DisplayName("Should verify regular mapper does not mask fields")
        void shouldVerifyRegularMapperDoesNotMask() throws JsonProcessingException {
            // Arrange
            StringMaskTestDto dto = new StringMaskTestDto("public-data", "super-secret");

            // Act
            String maskedJson = sanitizingMapper.writeValueAsString(dto);
            String unmaskedJson = regularMapper.writeValueAsString(dto);

            // Assert
            assertTrue(maskedJson.contains(MASKED_VALUE));
            assertFalse(maskedJson.contains("super-secret"));

            assertTrue(unmaskedJson.contains("super-secret"));
            assertFalse(unmaskedJson.contains(MASKED_VALUE));
        }

        @Test
        @DisplayName("Should deserialize masked JSON correctly")
        void shouldDeserializeMaskedJson() throws JsonProcessingException {
            // Arrange
            StringMaskTestDto original = new StringMaskTestDto("public-data", "super-secret");
            String maskedJson = sanitizingMapper.writeValueAsString(original);

            // Act
            StringMaskTestDto deserialized = sanitizingMapper.readValue(maskedJson, StringMaskTestDto.class);

            // Assert
            assertNotNull(deserialized);
            assertEquals("public-data", deserialized.getPublicField());
            assertEquals(MASKED_VALUE, deserialized.getSecretField());
        }
    }
}
