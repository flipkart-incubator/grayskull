package com.flipkart.grayskull.spimpl.audit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RequestIdAuditEnhancerTest {

    @Test
    void getAdditionalMetadata_WhenHeaderIsNull_ShouldReturnNull() {
        // Arrange
        MockHttpServletRequest request = new MockHttpServletRequest();

        RequestIdAuditEnhancer enhancer = new RequestIdAuditEnhancer(request);

        // Act
        Map<String, String> result = enhancer.getAdditionalMetadata();

        // Assert
        assertNull(result);
    }

    @Test
    void getAdditionalMetadata_WhenHeaderIsPresent_ShouldReturnMetadataMap() {
        // Arrange
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "req-123");

        RequestIdAuditEnhancer enhancer = new RequestIdAuditEnhancer(request);

        // Act
        Map<String, String> result = enhancer.getAdditionalMetadata();

        // Assert
        assertNotNull(result);
        assertEquals(Map.of("RequestId", "req-123"), result);
    }

    @Test
    void getAdditionalMetadata_WhenHeaderIsEmpty_ShouldReturnMetadataWithEmptyValue() {
        // Arrange
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "");

        RequestIdAuditEnhancer enhancer = new RequestIdAuditEnhancer(request);

        // Act
        Map<String, String> result = enhancer.getAdditionalMetadata();

        // Assert
        assertNull(result);
    }
}
