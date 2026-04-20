package com.flipkart.grayskull.spi;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves the Grayskull Java client SDK version at class-load time from a
 * Maven-filtered resource on the classpath ({@code META-INF/grayskull-client.properties}).
 * <p>
 * The returned value is used for the {@code X-Grayskull-SDK-Version} header on
 * data-plane calls. If the resource is missing or unreadable (unexpected in a
 * normal build, but possible in pathological shaded/repackaged environments),
 * we fall back to {@code "java/unknown"} and log at debug — never {@code null},
 * never throw, and never block request flow.
 */
public final class SdkVersion {

    private static final Logger log = LoggerFactory.getLogger(SdkVersion.class);
    static final String RESOURCE_PATH = "META-INF/grayskull-client.properties";
    static final String PREFIX = "java/";
    static final String UNKNOWN = PREFIX + "unknown";

    private static final String VERSION = loadFromClassLoader(SdkVersion.class.getClassLoader());

    private SdkVersion() {
        // Utility class; not instantiable.
    }

    /**
     * Returns a string of the form {@code "java/<version>"} (for example
     * {@code "java/0.1.2"}); {@code "java/unknown"} on bootstrap failure.
     */
    public static String get() {
        return VERSION;
    }

    // Package-private for testing every branch (null classloader, missing
    // resource, IO failure, blank/placeholder value).
    static String loadFromClassLoader(ClassLoader cl) {
        if (cl == null) {
            return UNKNOWN;
        }
        try (InputStream in = cl.getResourceAsStream(RESOURCE_PATH)) {
            return parseVersion(in);
        } catch (IOException e) {
            log.debug("Failed to load SDK version resource; falling back to '{}'", UNKNOWN, e);
            return UNKNOWN;
        }
    }

    // Package-private for direct exercise of the parse branches.
    static String parseVersion(InputStream in) throws IOException {
        if (in == null) {
            log.debug("Classpath resource '{}' not found; falling back to '{}'",
                    RESOURCE_PATH, UNKNOWN);
            return UNKNOWN;
        }
        Properties props = new Properties();
        props.load(in);
        String raw = props.getProperty("version");
        if (raw == null || raw.trim().isEmpty() || raw.contains("${")) {
            // A literal "${project.version}" means Maven resource filtering
            // did not run (e.g. running straight from IDE before a build);
            // treat it as unknown rather than echo the placeholder.
            log.debug("Resolved SDK version is empty or unfiltered ('{}'); falling back", raw);
            return UNKNOWN;
        }
        return PREFIX + raw.trim();
    }

    // Package-private helper for tests that want a synthetic properties stream
    // without touching the real classpath.
    static InputStream propertiesStream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }
}
