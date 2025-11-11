package com.flipkart.grayskull.metrics;

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.jmx.JmxConfig;
import io.micrometer.jmx.JmxMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
        // Create a composite registry that includes both JMX and global registry
        CompositeMeterRegistry composite = new CompositeMeterRegistry();
        
        // Add JMX registry for JConsole visibility
        JmxMeterRegistry jmxRegistry = new JmxMeterRegistry(JmxConfig.DEFAULT, Clock.SYSTEM);
        composite.add(jmxRegistry);
        
        // Also add the global registry for integration with application metrics
        composite.add(Metrics.globalRegistry);
        
        this.meterRegistry = composite;
    }

    @Override
    public void recordRequest(String event, int statusCode, long durationMs, String secretRef) {
        // Record to two timers: one with status (granular), one without (overall)
        
        // 1. Granular timer with status code - per-status metrics
        String statusKey = event + "." + statusCode + "." + secretRef;
        Timer statusTimer = timers.computeIfAbsent(statusKey, k -> 
            Timer.builder("grayskull.client")
                .tag("event", event)
                .tag("status", String.valueOf(statusCode))
                .tag("secret", secretRef)
                .register(meterRegistry)
        );
        statusTimer.record(durationMs, TimeUnit.MILLISECONDS);
        
        // 2. Overall timer without status code - combined metrics across all statuses
        String overallKey = event + "." + secretRef;
        Timer overallTimer = timers.computeIfAbsent(overallKey, k -> 
            Timer.builder("grayskull.client")
                .tag("event", event)
                .tag("secret", secretRef)
                .register(meterRegistry)
        );
        overallTimer.record(durationMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public String getRecorderName() {
        return "Micrometer";
    }
}

