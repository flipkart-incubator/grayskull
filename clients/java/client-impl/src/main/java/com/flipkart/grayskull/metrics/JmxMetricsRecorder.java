package com.flipkart.grayskull.metrics;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JMX-based metrics recorder using MBeans.
 * Provides basic metrics: counters, min, max, average.
 */
final class JmxMetricsRecorder implements MetricsRecorder {
    
    // Static maps shared across all instances to match global JMX MBean registration
    private static final ConcurrentHashMap<String, DurationTracker> durationTrackers = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, RetryTracker> retryTrackers = new ConcurrentHashMap<>();

    @Override
    public void recordRequest(String name, int statusCode, long durationMs) {
        // Record to two trackers: one with status (granular), one without (overall)

        // Granular tracker with status code - per-status metrics
        String statusKey = name + "." + statusCode;
        durationTrackers.computeIfAbsent(statusKey, k -> {
            try {
                ObjectName metricName = new ObjectName("Grayskull:type=HttpClientMetrics,name=" + ObjectName.quote(k));
                DurationTracker tracker = new DurationTracker();
                MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();
                
                // Only register if not already registered
                if (!mBeanServer.isRegistered(metricName)) {
                    mBeanServer.registerMBean(tracker, metricName);
                }
                
                return tracker;
            } catch (Exception e) {
                throw new RuntimeException("Failed to register JMX duration tracker for: " + k, e);
            }
        }).record(durationMs);
        
        // Overall tracker without status code - combined metrics across all statuses
        durationTrackers.computeIfAbsent(name, k -> {
            try {
                ObjectName metricName = new ObjectName("Grayskull:type=HttpClientMetrics,name=" + ObjectName.quote(k));
                DurationTracker tracker = new DurationTracker();
                MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();
                
                // Only register if not already registered 
                if (!mBeanServer.isRegistered(metricName)) {
                    mBeanServer.registerMBean(tracker, metricName);
                }

                return tracker;
            } catch (Exception e) {
                throw new RuntimeException("Failed to register JMX overall duration tracker for: " + k, e);
            }
        }).record(durationMs);
    }

    @Override
    public void recordRetry(String url, int attemptNumber, boolean success) {
        String path = URLNormalizer.normalize(url);
        String status = success ? "success" : "failure";
        
        // Path-level tracker (per path, combining success and failure)
        retryTrackers.computeIfAbsent("path." + path, k -> {
            try {
                ObjectName name = new ObjectName("Grayskull:type=HttpClientRetryMetrics,name=" + ObjectName.quote(k));
                RetryTracker tracker = new RetryTracker();
                MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();
                
                // Only register if not already registered
                if (!mBeanServer.isRegistered(name)) {
                    mBeanServer.registerMBean(tracker, name);
                }
                
                return tracker;
            } catch (Exception e) {
                throw new RuntimeException("Failed to register JMX path-level retry tracker for: " + k, e);
            }
        }).record(attemptNumber);
        
        // Path + status tracker (most granular)
        String granularKey = "path." + path + ".status." + status;
        retryTrackers.computeIfAbsent(granularKey, k -> {
            try {
                ObjectName name = new ObjectName("Grayskull:type=HttpClientRetryMetrics,name=" + ObjectName.quote(k));
                RetryTracker tracker = new RetryTracker();
                MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();
                
                // Only register if not already registered 
                if (!mBeanServer.isRegistered(name)) {
                    mBeanServer.registerMBean(tracker, name);
                }
                
                return tracker;
            } catch (Exception e) {
                throw new RuntimeException("Failed to register JMX granular retry tracker for: " + k, e);
            }
        }).record(attemptNumber);
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
    
    /**
     * JMX MBean interface for exposing retry metrics.
     */
    public interface RetryTrackerMBean {
        long getTotalRetries();
        long getMaxAttempts();
        double getAverageAttempts();
    }
    
    /**
     * JMX MBean implementation for retry tracking.
     */
    public static final class RetryTracker implements RetryTrackerMBean {
        private final AtomicLong count = new AtomicLong(0);
        private final AtomicLong totalAttempts = new AtomicLong(0);
        private final AtomicLong maxAttempts = new AtomicLong(0);
        
        public void record(int attemptNumber) {
            count.incrementAndGet();
            totalAttempts.addAndGet(attemptNumber);
            
            // Update max attempts
            long currentMax;
            do {
                currentMax = maxAttempts.get();
                if (attemptNumber <= currentMax) {
                    break;
                }
            } while (!maxAttempts.compareAndSet(currentMax, attemptNumber));
        }
        
        @Override
        public long getTotalRetries() {
            return count.get();
        }
        
        @Override
        public long getMaxAttempts() {
            return maxAttempts.get();
        }
        
        @Override
        public double getAverageAttempts() {
            long c = count.get();
            return c > 0 ? (double) totalAttempts.get() / c : 0;
        }
    }
}

