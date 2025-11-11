package com.flipkart.grayskull.metrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MetricsPublisher is responsible for recording and exposing metrics.
 * <p>
 * This publisher uses micrometer for metrics if avaiable in the classpath, otherwise defaults to Mbeans.
 * </p>
 *
 * <p>
 * Example usage:
 * <pre>
 *     MetricsPublisher publisher = new MetricsPublisher();
 *     publisher.recordRequest("getSecret", 200, 150, "project1:secret1");
 * </pre>
 * </p>
 *
 * Metrics are enabled by default but can be disabled through SDK configuration.
 */
public final class MetricsPublisher {
    
    private static final Logger log = LoggerFactory.getLogger(MetricsPublisher.class);
    private static volatile boolean metricsEnabled = true;
    
    private final MetricsRecorder recorder;

    public MetricsPublisher() {
        this.recorder = detectRecorder();
    }

    /**
     * Configure metrics collection globally.
     * @param enabled {@code true} to enable metrics collection, {@code false} to disable
     */
    public static void configure(boolean enabled) {
        metricsEnabled = enabled;
    }

    /**
     * Record a metrics event.
     * If metrics are disabled through configuration, this method will be a no-op.
     *
     * @param event      The SDK method name (e.g., "getSecret", "registerRefreshHook")
     * @param statusCode The HTTP status code (e.g., 200, 404, 500)
     * @param durationMs The request duration in milliseconds
     * @param secretRef  The secret reference (e.g., "project1:secret1")
     */
    public void recordRequest(String event, int statusCode, long durationMs, String secretRef) {
        if (!metricsEnabled) {
            return;
        }
        recorder.recordRequest(event, statusCode, durationMs, secretRef);
    }

    /**
     * Detects which metrics recorder to use based on classpath availability.
     * <p>
     * JMX is used as the fallback since it's always available in Java (no external dependencies).
     * </p>
     *
     * @return the appropriate metrics recorder (never {@code null})
     */
    private static MetricsRecorder detectRecorder() {
        try {
            Class.forName("io.micrometer.jmx.JmxMeterRegistry");
            return new MicrometerMetricsRecorder();
            
        } catch (ClassNotFoundException e) {
            log.debug("micrometer-registry-jmx not available on classpath, using basic JMX MBeans");
            return new JmxMetricsRecorder();
            
        } catch (Exception e) {
            log.warn("Failed to initialize Micrometer metrics, falling back to basic JMX MBeans", e);
            return new JmxMetricsRecorder();
        }
    }
}
