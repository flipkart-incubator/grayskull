package com.flipkart.grayskull.spimpl.audit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RequestIdAuditEnhancerTest {

    private final RequestIdAuditEnhancer enhancer = new RequestIdAuditEnhancer();

    @Test
    void getAdditionalMetadata_WhenHeaderIsNull_ShouldReturnNull() {
        // Arrange
        MockHttpServletRequest request = new MockHttpServletRequest();

        // Act
        Map<String, String> result = enhancer.getAdditionalMetadata(request);

        // Assert
        assertNull(result);
    }

    @Test
    void getAdditionalMetadata_WhenHeaderIsPresent_ShouldReturnMetadataMap() {
        // Arrange
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "req-123");

        // Act
        Map<String, String> result = enhancer.getAdditionalMetadata(request);

        // Assert
        assertNotNull(result);
        assertEquals(Map.of("RequestId", "req-123"), result);
    }

    @Test
    void getAdditionalMetadata_WhenHeaderIsEmpty_ShouldReturnMetadataWithEmptyValue() {
        // Arrange
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "");

        // Act
        Map<String, String> result = enhancer.getAdditionalMetadata(request);

        // Assert
        assertNull(result);
    }
}
