package com.flipkart.grayskull.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;

import org.junit.jupiter.api.Test;

class SdkVersionTest {

    @Test
    void get_isNonNullAndPrefixed() {
        String v = SdkVersion.get();
        assertNotNull(v);
        assertTrue(v.startsWith("java/"), "Expected 'java/<version>' prefix, got: " + v);
    }

    @Test
    void get_isStable() {
        // Version is resolved once at class load; subsequent calls must be identical.
        assertEquals(SdkVersion.get(), SdkVersion.get());
    }

    @Test
    void get_isNotLiteralPlaceholder() {
        // If Maven resource filtering ran correctly, the version must not be
        // the literal ${project.version} placeholder.
        assertTrue(!SdkVersion.get().contains("${"),
                "SDK version looks unfiltered: " + SdkVersion.get());
    }

    @Test
    void get_isEitherResolvedOrFallbackSentinel() {
        // In a proper build the version will be "java/<semver>". When running
        // directly from a classpath that has not been filtered (very unusual
        // in CI) the fallback sentinel is acceptable.
        String v = SdkVersion.get();
        assertTrue(v.matches("^java/(unknown|[\\w\\-.]+)$"),
                "Unexpected version format: " + v);
    }

    @Test
    void parseVersion_nullStream_returnsUnknown() throws IOException {
        assertEquals(SdkVersion.UNKNOWN, SdkVersion.parseVersion(null));
    }

    @Test
    void parseVersion_missingVersionKey_returnsUnknown() throws IOException {
        try (InputStream in = SdkVersion.propertiesStream("other=1.0\n")) {
            assertEquals(SdkVersion.UNKNOWN, SdkVersion.parseVersion(in));
        }
    }

    @Test
    void parseVersion_blankVersion_returnsUnknown() throws IOException {
        try (InputStream in = SdkVersion.propertiesStream("version=   \n")) {
            assertEquals(SdkVersion.UNKNOWN, SdkVersion.parseVersion(in));
        }
    }

    @Test
    void parseVersion_unfilteredPlaceholder_returnsUnknown() throws IOException {
        try (InputStream in = SdkVersion.propertiesStream("version=${project.version}\n")) {
            assertEquals(SdkVersion.UNKNOWN, SdkVersion.parseVersion(in));
        }
    }

    @Test
    void parseVersion_validVersion_returnsPrefixedValue() throws IOException {
        try (InputStream in = SdkVersion.propertiesStream("version=9.9.9\n")) {
            assertEquals("java/9.9.9", SdkVersion.parseVersion(in));
        }
    }

    @Test
    void parseVersion_valueIsTrimmed() throws IOException {
        try (InputStream in = SdkVersion.propertiesStream("version=  1.2.3  \n")) {
            assertEquals("java/1.2.3", SdkVersion.parseVersion(in));
        }
    }

    @Test
    void loadFromClassLoader_null_returnsUnknown() {
        assertEquals(SdkVersion.UNKNOWN, SdkVersion.loadFromClassLoader(null));
    }

    @Test
    void loadFromClassLoader_missingResource_returnsUnknown() {
        ClassLoader alwaysNull = new ClassLoader(null) {
            @Override
            public InputStream getResourceAsStream(String name) {
                return null;
            }
        };
        assertEquals(SdkVersion.UNKNOWN, SdkVersion.loadFromClassLoader(alwaysNull));
    }

    @Test
    void loadFromClassLoader_resourceIOError_returnsUnknown() {
        ClassLoader brokenStream = new ClassLoader(null) {
            @Override
            public InputStream getResourceAsStream(String name) {
                return new InputStream() {
                    @Override
                    public int read() throws IOException {
                        throw new IOException("forced");
                    }
                };
            }

            @Override
            public Enumeration<URL> getResources(String name) {
                return Collections.emptyEnumeration();
            }
        };
        assertEquals(SdkVersion.UNKNOWN, SdkVersion.loadFromClassLoader(brokenStream));
    }

    @Test
    void loadFromClassLoader_validResource_returnsPrefixedValue() {
        ClassLoader good = new ClassLoader(null) {
            @Override
            public InputStream getResourceAsStream(String name) {
                return SdkVersion.propertiesStream("version=1.2.3-TEST\n");
            }
        };
        assertEquals("java/1.2.3-TEST", SdkVersion.loadFromClassLoader(good));
    }
}
