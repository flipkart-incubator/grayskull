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
     * @param event      The SDK method name
     * @param statusCode The HTTP status code
     * @param durationMs The duration in milliseconds
     * @param secretRef  The secret reference
     */
    void recordRequest(String event, int statusCode, long durationMs, String secretRef);
    
    /**
     * Get the name of this metrics recorder implementation.
     *
     * @return recorder name (e.g., "JMX", "Micrometer")
     */
    String getRecorderName();
}

