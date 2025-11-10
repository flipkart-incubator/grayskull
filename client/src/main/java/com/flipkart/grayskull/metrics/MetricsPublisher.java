package com.flipkart.grayskull.metrics;

import javax.management.*;
import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MetricsPublisher is responsible for recording and exposing JMX metrics for Grayskull HTTP client events.
 * Each event is tracked as a JMX counter, keyed by event type, HTTP status series (2XX, 4XX, 5XX), and secret.
 *
 * Example usage:
 *     MetricsPublisher publisher = new MetricsPublisher();
 *     publisher.record("getSecret", 200, "project1:secret1");  // Creates: getSecret.200.project1:secret1
 *     publisher.record("getSecret", 404, "project2:secret2");  // Creates: getSecret.404.project2:secret2
 *     publisher.recordDuration("getSecret", 150, "project1:secret1");
 *
 * You can view these metrics in JConsole, VisualVM, or any JMX-compatible tool under the domain com.flipkart.grayskull:type=HttpClientMetrics.
 * Metrics are enabled by default but can be disabled through SDK configuration.
 */
public class MetricsPublisher {
    private static final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, DurationTracker> durationTrackers = new ConcurrentHashMap<>();
    private static volatile boolean metricsEnabled = true;

    /**
     * Configure metrics collection. By default, metrics are enabled.
     * This method should only be called through the SDK configuration.
     *
     * @param enabled true to enable metrics collection, false to disable
     */
    public static void configure(boolean enabled) {
        metricsEnabled = enabled;
    }

    /**
     * Record a metrics event. Increments the JMX counter for the given event, status code, and secret.
     * If metrics are disabled through configuration, this method will be a no-op.
     *
     * @param event      The SDK method name (e.g., "getSecret", "registerRefreshHook")
     * @param statusCode The HTTP status code (use 0 for non-HTTP events)
     * @param secretRef  The secret reference (e.g., "project1:secret1")
     */
    public void record(String event, int statusCode, String secretRef) {
        if (!metricsEnabled) {
            return;
        }

        String key = event + "." + statusCode + "." + secretRef;
        counters.computeIfAbsent(key, k -> {
            try {
                ObjectName name = new ObjectName("Grayskull:type=HttpClientMetrics,name=" + ObjectName.quote(k));
                AtomicLong counter = new AtomicLong(0);
                ManagementFactory.getPlatformMBeanServer().registerMBean(new Counter(counter), name);
                return counter;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).incrementAndGet();
    }

    /**
     * Record request duration. Updates duration statistics for the given event and secret.
     *
     * @param event      The SDK method name (e.g., "getSecret", "registerRefreshHook")
     * @param durationMs The duration in milliseconds
     * @param secretRef  The secret reference (e.g., "project1:secret1")
     */
    public void recordDuration(String event, long durationMs, String secretRef) {
        if (!metricsEnabled) {
            return;
        }

        String key = event + "." + secretRef;
        durationTrackers.computeIfAbsent(key, k -> {
            try {
                ObjectName name = new ObjectName("Grayskull:type=HttpClientMetrics,name=" + ObjectName.quote(k));
                DurationTracker tracker = new DurationTracker();
                ManagementFactory.getPlatformMBeanServer().registerMBean(tracker, name);
                return tracker;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).record(durationMs);
    }

    /**
     * JMX MBean interface for exposing counter values.
     */
    public interface CounterMBean {
        long getCount();
    }

    /**
     * JMX MBean implementation for counters.
     */
    public static class Counter implements CounterMBean {
        private final AtomicLong counter;

        public Counter(AtomicLong counter) {
            this.counter = counter;
        }

        @Override
        public long getCount() {
            return counter.get();
        }
    }

    /**
     * JMX MBean interface for exposing duration metrics.
     */
    public interface DurationTrackerMBean {
        long getTotalDurationMs();
        long getAverageDurationMs();
        long getMaxDurationMs();
        long getMinDurationMs();
        long getCount();
    }

    /**
     * JMX MBean implementation for duration tracking.
     */
    public static class DurationTracker implements DurationTrackerMBean {
        private final AtomicLong count = new AtomicLong(0);
        private final AtomicLong totalDuration = new AtomicLong(0);
        private final AtomicLong maxDuration = new AtomicLong(0);
        private final AtomicLong minDuration = new AtomicLong(Long.MAX_VALUE);

        public void record(long durationMs) {
            count.incrementAndGet();
            totalDuration.addAndGet(durationMs);

            // Update max duration
            long currentMax;
            do {
                currentMax = maxDuration.get();
                if (durationMs <= currentMax) {
                    break;
                }
            } while (!maxDuration.compareAndSet(currentMax, durationMs));

            // Update min duration
            long currentMin;
            do {
                currentMin = minDuration.get();
                if (durationMs >= currentMin) {
                    break;
                }
            } while (!minDuration.compareAndSet(currentMin, durationMs));
        }

        @Override
        public long getTotalDurationMs() {
            return totalDuration.get();
        }

        @Override
        public long getAverageDurationMs() {
            long c = count.get();
            return c > 0 ? totalDuration.get() / c : 0;
        }

        @Override
        public long getMaxDurationMs() {
            long max = maxDuration.get();
            return max == 0 ? 0 : max;
        }

        @Override
        public long getMinDurationMs() {
            long min = minDuration.get();
            return min == Long.MAX_VALUE ? 0 : min;
        }

        @Override
        public long getCount() {
            return count.get();
        }
    }
}
