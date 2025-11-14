package com.flipkart.grayskull.metrics;

import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JMX-based metrics recorder using MBeans.
 * Provides basic metrics: counters, min, max, average.
 */
final class JmxMetricsRecorder implements MetricsRecorder {
    
    private final ConcurrentHashMap<String, DurationTracker> durationTrackers = new ConcurrentHashMap<>();

    @Override
    public void recordRequest(String event, int statusCode, long durationMs, String secretRef) {
        // Record to two trackers: one with status (granular), one without (overall)
        
        // 1. Granular tracker with status code - per-status metrics
        String statusKey = event + "." + statusCode + "." + secretRef;
        durationTrackers.computeIfAbsent(statusKey, k -> {
            try {
                ObjectName name = new ObjectName("Grayskull:type=HttpClientMetrics,name=" + ObjectName.quote(k));
                DurationTracker tracker = new DurationTracker();
                ManagementFactory.getPlatformMBeanServer().registerMBean(tracker, name);
                return tracker;
            } catch (Exception e) {
                throw new RuntimeException("Failed to register JMX duration tracker for: " + k, e);
            }
        }).record(durationMs);
        
        // 2. Overall tracker without status code - combined metrics across all statuses
        String overallKey = event + "." + secretRef;
        durationTrackers.computeIfAbsent(overallKey, k -> {
            try {
                ObjectName name = new ObjectName("Grayskull:type=HttpClientMetrics,name=" + ObjectName.quote(k));
                DurationTracker tracker = new DurationTracker();
                ManagementFactory.getPlatformMBeanServer().registerMBean(tracker, name);
                return tracker;
            } catch (Exception e) {
                throw new RuntimeException("Failed to register JMX overall duration tracker for: " + k, e);
            }
        }).record(durationMs);
    }

    @Override
    public String getRecorderName() {
        return "JMX";
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
    public static final class DurationTracker implements DurationTrackerMBean {
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

