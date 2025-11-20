package com.flipkart.grayskull.metrics;

/**
 * Interface for recording metrics to different backends.
 * Implementations provide support for JMX and Micrometer-based metrics.
 */
interface MetricsRecorder {
    
    /**
     * Record a request with its duration.
     * The recorder automatically tracks both count and duration statistics.
     *
     * @param name The URL path
     * @param statusCode The HTTP status code
     * @param durationMs The duration in milliseconds
     */
    void recordRequest(String name, int statusCode, long durationMs);
    
    /**
     * Record a retry attempt for a request.
     *
     * @param url The URL path
     * @param attemptNumber The attempt number (1-indexed)
     * @param success Whether the retry eventually succeeded
     */
    void recordRetry(String url, int attemptNumber, boolean success);
    
    /**
     * Get the name of this metrics recorder implementation.
     *
     * @return recorder name (e.g., "JMX", "Micrometer")
     */
    String getRecorderName();
}

