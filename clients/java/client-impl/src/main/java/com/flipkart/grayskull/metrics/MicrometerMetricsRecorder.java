package com.flipkart.grayskull.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Micrometer-based metrics recorder.
 * Provides advanced metrics including P50, P95, P99, P999 percentiles.
 * <p>
 * This recorder uses Micrometer Timers for both counting and duration tracking,
 * providing granular insights into performance by status code and overall.
 * If users don't include Micrometer in their classpath, the system automatically 
 * falls back to JMX metrics.
 * </p>
 */
final class MicrometerMetricsRecorder implements MetricsRecorder {
    
    // Static map shared across all instances to match global MeterRegistry
    private static final ConcurrentHashMap<String, Timer> timers = new ConcurrentHashMap<>();
    private final MeterRegistry meterRegistry;

    MicrometerMetricsRecorder() {
        // Use the global registry for integration with application metrics
        this.meterRegistry = Metrics.globalRegistry;
    }

    @Override
    public void recordRequest(String name, int statusCode, long durationMs) {
        // Extract path from URL (e.g., /v1/project/test-project/secrets/test/data)
        String timerKey = name + "." + statusCode;

        Timer timer = timers.computeIfAbsent(timerKey, k -> 
            Timer.builder("grayskull_client_request")
                .tag("operation", name)
                .tag("status", String.valueOf(statusCode))
                .register(meterRegistry)
        );
        timer.record(durationMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void recordRetry(String url, int attemptNumber, boolean success) {
        String path = URLNormalizer.normalize(url);
        
        // Record retry counter
        meterRegistry.counter("grayskull_client_retry",
                "path", path,
                "attempt", String.valueOf(attemptNumber),
                "status", success ? "success" : "failure")
                .increment();
    }

    @Override
    public String getRecorderName() {
        return "Micrometer";
    }
}

