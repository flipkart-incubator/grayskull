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
    
    private final ConcurrentHashMap<String, Timer> timers = new ConcurrentHashMap<>();
    private final MeterRegistry meterRegistry;

    MicrometerMetricsRecorder() {
        // Use the global registry for integration with application metrics
        this.meterRegistry = Metrics.globalRegistry;
    }

    @Override
    public void recordRequest(String event, int statusCode, long durationMs, String secretRef) {
        // Metric name: event.secretRef (e.g., "getSecret.project1:secret1")
        // Tag: status (e.g., "200", "404", "500")
        // This gives users both status-code-level and secret-level metrics
        
        String metricName = event + "_" + secretRef;
        String timerKey = metricName + "." + statusCode;
        
        Timer timer = timers.computeIfAbsent(timerKey, k -> 
            Timer.builder("grayskull_client_" + metricName)
                .tag("status", String.valueOf(statusCode))
                .register(meterRegistry)
        );
        timer.record(durationMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public String getRecorderName() {
        return "Micrometer";
    }
}

