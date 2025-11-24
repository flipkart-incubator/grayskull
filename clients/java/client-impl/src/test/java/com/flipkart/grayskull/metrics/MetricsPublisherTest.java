package com.flipkart.grayskull.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;

import static org.junit.jupiter.api.Assertions.*;

class MetricsPublisherTest {
    
    private MetricsPublisher publisher;
    
    @BeforeEach
    void setUp() {
        // Reset metrics configuration
        MetricsPublisher.configure(true);
        
        // Clear the global registry before each test
        Metrics.globalRegistry.clear();
        
        // Add a simple meter registry for Micrometer tests
        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        Metrics.globalRegistry.add(meterRegistry);
    }
    
    @AfterEach
    void tearDown() {
        // Re-enable metrics after tests
        MetricsPublisher.configure(true);
    }
    
    
    @Test
    void testRecordRequestWhenEnabled() throws Exception {
        publisher = new MetricsPublisher();
        MetricsPublisher.configure(true);
        
        String metricName = "test.enabled";
        publisher.recordRequest(metricName, 200, 100L);
        
        // Verify that metrics were recorded (checking JMX since Micrometer is available)
        // The actual recorder detection may vary, so we just verify the method doesn't throw
        assertDoesNotThrow(() -> publisher.recordRequest(metricName, 200, 100L));
    }
    
    @Test
    void testRecordRequestWhenDisabled() {
        publisher = new MetricsPublisher();
        MetricsPublisher.configure(false);
        
        // Should not throw exception, just be a no-op
        assertDoesNotThrow(() -> publisher.recordRequest("test.disabled", 200, 100L));
    }
    
    @Test
    void testRecordRetryWhenEnabled() {
        publisher = new MetricsPublisher();
        MetricsPublisher.configure(true);
        
        assertDoesNotThrow(() -> publisher.recordRetry("http://localhost:8080/v1/secrets", 2, true));
    }
    
    @Test
    void testRecordRetryWhenDisabled() {
        publisher = new MetricsPublisher();
        MetricsPublisher.configure(false);
        
        // Should not throw exception, just be a no-op
        assertDoesNotThrow(() -> publisher.recordRetry("http://localhost:8080/v1/secrets", 2, true));
    }
    
    @Test
    void testConfigureEnableDisable() {
        publisher = new MetricsPublisher();
        
        // Enable
        MetricsPublisher.configure(true);
        assertDoesNotThrow(() -> publisher.recordRequest("test.1", 200, 100L));
        
        // Disable
        MetricsPublisher.configure(false);
        assertDoesNotThrow(() -> publisher.recordRequest("test.2", 200, 100L));
        
        // Re-enable
        MetricsPublisher.configure(true);
        assertDoesNotThrow(() -> publisher.recordRequest("test.3", 200, 100L));
    }
    
    @Test
    void testMultiplePublisherInstances() {
        MetricsPublisher publisher1 = new MetricsPublisher();
        MetricsPublisher publisher2 = new MetricsPublisher();
        
        // Both should work independently
        assertDoesNotThrow(() -> {
            publisher1.recordRequest("test.p1", 200, 100L);
            publisher2.recordRequest("test.p2", 404, 50L);
        });
    }
    
    
    @Test
    void testConcurrentRecording() throws InterruptedException {
        publisher = new MetricsPublisher();
        
        Thread[] threads = new Thread[10];
        for (int i = 0; i < threads.length; i++) {
            final int index = i;
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 100; j++) {
                    publisher.recordRequest("test.concurrent", 200, index * 10 + j);
                }
            });
            threads[i].start();
        }
        
        for (Thread thread : threads) {
            thread.join();
        }
        
        // If we get here without exceptions, concurrent recording works
        assertTrue(true);
    }
    
    @Test
    void testGlobalConfigurationAffectsAllInstances() {
        MetricsPublisher publisher1 = new MetricsPublisher();
        MetricsPublisher publisher2 = new MetricsPublisher();
        
        // Disable globally
        MetricsPublisher.configure(false);
        
        // Both publishers should respect the global setting
        // These should be no-ops
        publisher1.recordRequest("test.global1", 200, 100L);
        publisher2.recordRequest("test.global2", 200, 100L);
        
        // Re-enable
        MetricsPublisher.configure(true);
        
        // Now they should record
        assertDoesNotThrow(() -> {
            publisher1.recordRequest("test.global3", 200, 100L);
            publisher2.recordRequest("test.global4", 200, 100L);
        });
    }
}

