package com.flipkart.grayskull.constants;

/**
 * Constants for MDC (Mapped Diagnostic Context) keys used throughout the Grayskull client.
 * <p>
 * These keys are automatically added to the logging context for correlation and tracing.
 * Applications can reference these keys in their logging patterns.
 * </p>
 */
public final class MDCKeys {
    
    /**
     * Unique request identifier for correlating logs across the request lifecycle.
     * Also sent as the X-Request-Id header to enable end-to-end tracing.
     */
    public static final String GRAYSKULL_REQUEST_ID = "grayskullRequestId";
    
    /**
     * Grayskull project ID being accessed.
     */
    public static final String PROJECT_ID = "projectId";
    
    /**
     * Name of the secret being accessed.
     */
    public static final String SECRET_NAME = "secretName";
    
    private MDCKeys() {
        throw new AssertionError("Cannot instantiate constants class");
    }
}

