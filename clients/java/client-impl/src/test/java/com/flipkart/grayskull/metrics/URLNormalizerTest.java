package com.flipkart.grayskull.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class URLNormalizerTest {
    
    @Test
    void testNormalizeWithValidUrl() {
        String result = URLNormalizer.normalize("http://localhost:8080/v1/project/test-project/secrets");
        assertEquals("/v1/project/test-project/secrets", result);
    }
    
    @Test
    void testNormalizeWithOnlyPath() {
        String result = URLNormalizer.normalize("/v1/project/test-project/secrets");
        assertEquals("/v1/project/test-project/secrets", result);
    }
    
    @Test
    void testNormalizeWithQueryParams() {
        String result = URLNormalizer.normalize("http://localhost:8080/v1/secrets?param=value");
        assertEquals("/v1/secrets", result);
    }
    
    @Test
    void testNormalizeWithFragment() {
        String result = URLNormalizer.normalize("http://localhost:8080/v1/secrets#section");
        assertEquals("/v1/secrets", result);
    }
    
    @Test
    void testNormalizeWithEmptyString() {
        String result = URLNormalizer.normalize("");
        assertEquals("unknown", result);
    }
    
    @Test
    void testNormalizeWithNull() {
        String result = URLNormalizer.normalize(null);
        assertEquals("unknown", result);
    }
    
    @Test
    void testNormalizeWithNoPath() {
        String result = URLNormalizer.normalize("http://localhost:8080");
        assertEquals("http://localhost:8080", result);
    }
    
    @Test
    void testNormalizeWithInvalidUrl() {
        // Should return the original URL if parsing fails
        String invalidUrl = "not a valid url at all";
        String result = URLNormalizer.normalize(invalidUrl);
        assertEquals(invalidUrl, result);
    }
}

