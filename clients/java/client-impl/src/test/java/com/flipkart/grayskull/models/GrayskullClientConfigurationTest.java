package com.flipkart.grayskull.models;

import com.flipkart.grayskull.workload.DefaultWorkloadIdentityResolver;
import com.flipkart.grayskull.workload.WorkloadIdentityResolver;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for GrayskullClientConfiguration.
 * <p>
 * Focuses on the workload-identity and default-header extension points
 * introduced alongside the Grayskull-Workload header.
 * </p>
 */
class GrayskullClientConfigurationTest {

    @Test
    void testGetWorkloadIdentityResolver_defaultIsDefaultResolver() {
        // Given
        GrayskullClientConfiguration config = new GrayskullClientConfiguration();

        // When
        WorkloadIdentityResolver resolver = config.getWorkloadIdentityResolver();

        // Then - default wiring must be the OSS hostname-based resolver
        assertNotNull(resolver);
        assertEquals(DefaultWorkloadIdentityResolver.class, resolver.getClass());
    }

    @Test
    void testSetWorkloadIdentityResolver_overridesDefault() {
        // Given
        GrayskullClientConfiguration config = new GrayskullClientConfiguration();
        WorkloadIdentityResolver custom = () -> "custom-id";

        // When
        config.setWorkloadIdentityResolver(custom);

        // Then
        assertSame(custom, config.getWorkloadIdentityResolver());
        assertEquals("custom-id", config.getWorkloadIdentityResolver().resolve());
    }

    @Test
    void testSetWorkloadIdentityResolver_null_retainsPreviousResolver() {
        // Given
        GrayskullClientConfiguration config = new GrayskullClientConfiguration();
        WorkloadIdentityResolver custom = () -> "stay";
        config.setWorkloadIdentityResolver(custom);

        // When - null is a defensive no-op so callers can't accidentally erase the resolver
        config.setWorkloadIdentityResolver(null);

        // Then
        assertSame(custom, config.getWorkloadIdentityResolver());
    }

    @Test
    void testSetWorkloadIdentityResolver_null_onFreshConfig_retainsDefault() {
        // Given
        GrayskullClientConfiguration config = new GrayskullClientConfiguration();
        WorkloadIdentityResolver original = config.getWorkloadIdentityResolver();

        // When
        config.setWorkloadIdentityResolver(null);

        // Then - default is still in place
        assertSame(original, config.getWorkloadIdentityResolver());
    }

    @Test
    void testAddDefaultHeader_storesNameAndValue() {
        // Given
        GrayskullClientConfiguration config = new GrayskullClientConfiguration();

        // When
        config.addDefaultHeader("X-Custom", "value-1");

        // Then
        assertEquals("value-1", config.getDefaultHeaders().get("X-Custom"));
        assertEquals(1, config.getDefaultHeaders().size());
    }

    @Test
    void testAddDefaultHeader_multipleHeaders_allStored() {
        // Given
        GrayskullClientConfiguration config = new GrayskullClientConfiguration();

        // When
        config.addDefaultHeader("X-One", "1");
        config.addDefaultHeader("X-Two", "2");

        // Then
        Map<String, String> headers = config.getDefaultHeaders();
        assertEquals(2, headers.size());
        assertEquals("1", headers.get("X-One"));
        assertEquals("2", headers.get("X-Two"));
    }

    @Test
    void testAddDefaultHeader_sameName_overwritesValue() {
        // Given
        GrayskullClientConfiguration config = new GrayskullClientConfiguration();

        // When
        config.addDefaultHeader("X-Key", "v1");
        config.addDefaultHeader("X-Key", "v2");

        // Then
        assertEquals(1, config.getDefaultHeaders().size());
        assertEquals("v2", config.getDefaultHeaders().get("X-Key"));
    }

    @Test
    void testAddDefaultHeader_nullName_ignored() {
        // Given
        GrayskullClientConfiguration config = new GrayskullClientConfiguration();

        // When
        config.addDefaultHeader(null, "value");

        // Then
        assertTrue(config.getDefaultHeaders().isEmpty());
    }

    @Test
    void testAddDefaultHeader_nullValue_ignored() {
        // Given
        GrayskullClientConfiguration config = new GrayskullClientConfiguration();

        // When
        config.addDefaultHeader("X-Name", null);

        // Then
        assertTrue(config.getDefaultHeaders().isEmpty());
    }

    @Test
    void testAddDefaultHeader_bothNull_ignored() {
        // Given
        GrayskullClientConfiguration config = new GrayskullClientConfiguration();

        // When
        config.addDefaultHeader(null, null);

        // Then
        assertTrue(config.getDefaultHeaders().isEmpty());
    }

    @Test
    void testAddDefaultHeader_emptyStrings_stored() {
        // Given - empty string is considered a valid header name/value by the API;
        // only null is filtered so callers can set flag headers without values.
        GrayskullClientConfiguration config = new GrayskullClientConfiguration();

        // When
        config.addDefaultHeader("", "");

        // Then
        assertEquals(1, config.getDefaultHeaders().size());
        assertEquals("", config.getDefaultHeaders().get(""));
    }

    @Test
    void testGetDefaultHeaders_freshConfig_isEmpty() {
        // Given
        GrayskullClientConfiguration config = new GrayskullClientConfiguration();

        // When/Then
        assertNotNull(config.getDefaultHeaders());
        assertTrue(config.getDefaultHeaders().isEmpty());
    }

    @Test
    void testGetDefaultHeaders_returnsUnmodifiableView() {
        // Given
        GrayskullClientConfiguration config = new GrayskullClientConfiguration();
        config.addDefaultHeader("X-Stored", "yes");

        // When
        Map<String, String> view = config.getDefaultHeaders();

        // Then - callers must not be able to mutate configuration by reference
        assertThrows(UnsupportedOperationException.class, () -> view.put("X-Injected", "bad"));
        assertThrows(UnsupportedOperationException.class, () -> view.remove("X-Stored"));
        assertThrows(UnsupportedOperationException.class, view::clear);
    }

    @Test
    void testGetDefaultHeaders_reflectsSubsequentAdds() {
        // Given
        GrayskullClientConfiguration config = new GrayskullClientConfiguration();
        Map<String, String> viewBefore = config.getDefaultHeaders();
        assertTrue(viewBefore.isEmpty());

        // When
        config.addDefaultHeader("X-Later", "added");

        // Then - the unmodifiable view is live; snapshotting is the caller's concern
        assertEquals("added", config.getDefaultHeaders().get("X-Later"));
    }
}
