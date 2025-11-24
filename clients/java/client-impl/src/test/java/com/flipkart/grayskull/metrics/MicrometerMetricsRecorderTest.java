package com.flipkart.grayskull.metrics;

import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class MicrometerMetricsRecorderTest {
    
    private MicrometerMetricsRecorder recorder;
    private MeterRegistry meterRegistry;
    
    @BeforeEach
    void setUp() {
        // Clear the global registry before each test
        Metrics.globalRegistry.clear();
        
        // Add a simple meter registry for testing
        meterRegistry = new SimpleMeterRegistry();
        Metrics.globalRegistry.add(meterRegistry);
        
        recorder = new MicrometerMetricsRecorder();
    }
    
    @Test
    void testGetRecorderName() {
        assertEquals("Micrometer", recorder.getRecorderName());
    }
    
    @Test
    void testRecordRequest() {
        String metricName = "test.request";
        int statusCode = 200;
        long duration = 100L;
        
        recorder.recordRequest(metricName, statusCode, duration);
        
        // Find the timer
        Timer timer = meterRegistry.find("grayskull_client_request")
                .tag("operation", metricName)
                .tag("status", String.valueOf(statusCode))
                .timer();
        
        assertNotNull(timer);
        assertEquals(1L, timer.count());
        assertEquals(100.0, timer.totalTime(TimeUnit.MILLISECONDS), 0.01);
    }
    
    @Test
    void testRecordMultipleRequests() {
        String metricName = "test.multiple";
        int statusCode = 200;
        
        recorder.recordRequest(metricName, statusCode, 100L);
        recorder.recordRequest(metricName, statusCode, 200L);
        recorder.recordRequest(metricName, statusCode, 150L);
        
        Timer timer = meterRegistry.find("grayskull_client_request")
                .tag("operation", metricName)
                .tag("status", String.valueOf(statusCode))
                .timer();
        
        assertNotNull(timer);
        assertEquals(3L, timer.count());
        assertEquals(450.0, timer.totalTime(TimeUnit.MILLISECONDS), 0.01);
        assertEquals(150.0, timer.mean(TimeUnit.MILLISECONDS), 0.01);
    }
    
    @Test
    void testRecordRequestWithDifferentStatusCodes() {
        String metricName = "test.status";
        
        recorder.recordRequest(metricName, 200, 100L);
        recorder.recordRequest(metricName, 404, 50L);
        recorder.recordRequest(metricName, 500, 200L);
        
        // Check 200 status timer
        Timer timer200 = meterRegistry.find("grayskull_client_request")
                .tag("operation", metricName)
                .tag("status", "200")
                .timer();
        assertNotNull(timer200);
        assertEquals(1L, timer200.count());
        assertEquals(100.0, timer200.totalTime(TimeUnit.MILLISECONDS), 0.01);
        
        // Check 404 status timer
        Timer timer404 = meterRegistry.find("grayskull_client_request")
                .tag("operation", metricName)
                .tag("status", "404")
                .timer();
        assertNotNull(timer404);
        assertEquals(1L, timer404.count());
        assertEquals(50.0, timer404.totalTime(TimeUnit.MILLISECONDS), 0.01);
        
        // Check 500 status timer
        Timer timer500 = meterRegistry.find("grayskull_client_request")
                .tag("operation", metricName)
                .tag("status", "500")
                .timer();
        assertNotNull(timer500);
        assertEquals(1L, timer500.count());
        assertEquals(200.0, timer500.totalTime(TimeUnit.MILLISECONDS), 0.01);
    }
    
    @Test
    void testRecordRetry() {
        String url = "http://localhost:8080/v1/secrets";
        
        recorder.recordRetry(url, 2, true);
        
        String path = "/v1/secrets";
        Counter counter = meterRegistry.find("grayskull_client_retry")
                .tag("path", path)
                .tag("attempt", "2")
                .tag("status", "success")
                .counter();
        
        assertNotNull(counter);
        assertEquals(1.0, counter.count(), 0.01);
    }
    
    @Test
    void testRecordMultipleRetries() {
        String url = "http://localhost:8080/v1/secrets";
        String path = "/v1/secrets";
        
        recorder.recordRetry(url, 1, true);
        recorder.recordRetry(url, 2, true);
        recorder.recordRetry(url, 2, true);
        
        // Check attempt 1
        Counter counter1 = meterRegistry.find("grayskull_client_retry")
                .tag("path", path)
                .tag("attempt", "1")
                .tag("status", "success")
                .counter();
        assertNotNull(counter1);
        assertEquals(1.0, counter1.count(), 0.01);
        
        // Check attempt 2 (should have 2 counts)
        Counter counter2 = meterRegistry.find("grayskull_client_retry")
                .tag("path", path)
                .tag("attempt", "2")
                .tag("status", "success")
                .counter();
        assertNotNull(counter2);
        assertEquals(2.0, counter2.count(), 0.01);
    }
    
    @Test
    void testRecordRetryWithSuccessAndFailure() {
        String url = "http://localhost:8080/v1/secrets";
        String path = "/v1/secrets";
        
        recorder.recordRetry(url, 2, true);
        recorder.recordRetry(url, 3, false);
        recorder.recordRetry(url, 2, true);
        
        // Check success counter
        Counter successCounter = meterRegistry.find("grayskull_client_retry")
                .tag("path", path)
                .tag("attempt", "2")
                .tag("status", "success")
                .counter();
        assertNotNull(successCounter);
        assertEquals(2.0, successCounter.count(), 0.01);
        
        // Check failure counter
        Counter failureCounter = meterRegistry.find("grayskull_client_retry")
                .tag("path", path)
                .tag("attempt", "3")
                .tag("status", "failure")
                .counter();
        assertNotNull(failureCounter);
        assertEquals(1.0, failureCounter.count(), 0.01);
    }
    
    @Test
    void testRecordRequestCreatesTimerOnce() {
        String metricName = "test.cache";
        int statusCode = 200;
        
        recorder.recordRequest(metricName, statusCode, 100L);
        recorder.recordRequest(metricName, statusCode, 200L);
        
        // Should reuse the same timer
        long timerCount = meterRegistry.find("grayskull_client_request")
                .tag("operation", metricName)
                .tag("status", String.valueOf(statusCode))
                .timers()
                .size();
        
        assertEquals(1L, timerCount);
    }
}

