package com.flipkart.grayskull.metrics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.management.MBeanServer;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class JmxMetricsRecorderTest {
    
    private JmxMetricsRecorder recorder;
    private MBeanServer mBeanServer;
    
    @BeforeEach
    void setUp() {
        recorder = new JmxMetricsRecorder();
        mBeanServer = ManagementFactory.getPlatformMBeanServer();
    }
    
    @AfterEach
    void tearDown() throws Exception {
        // Unregister all Grayskull MBeans to avoid conflicts between tests
        Set<ObjectInstance> mbeans = mBeanServer.queryMBeans(
            new ObjectName("Grayskull:*"), null);
        for (ObjectInstance mbean : mbeans) {
            mBeanServer.unregisterMBean(mbean.getObjectName());
        }
    }
    
    @Test
    void testGetRecorderName() {
        assertEquals("JMX", recorder.getRecorderName());
    }
    
    @Test
    void testRecordRequest() throws Exception {
        String metricName = "test.request";
        int statusCode = 200;
        long duration = 100L;
        
        recorder.recordRequest(metricName, statusCode, duration);
        
        // Verify granular tracker (with status code)
        String statusKey = metricName + "." + statusCode;
        ObjectName statusObjectName = new ObjectName("Grayskull:type=HttpClientMetrics,name=\"" + statusKey + "\"");
        assertTrue(mBeanServer.isRegistered(statusObjectName));
        
        Long count = (Long) mBeanServer.getAttribute(statusObjectName, "Count");
        assertEquals(1L, count);
        
        Long totalDuration = (Long) mBeanServer.getAttribute(statusObjectName, "TotalDurationMs");
        assertEquals(duration, totalDuration);
        
        // Verify overall tracker (without status code)
        ObjectName overallObjectName = new ObjectName("Grayskull:type=HttpClientMetrics,name=\"" + metricName + "\"");
        assertTrue(mBeanServer.isRegistered(overallObjectName));
        
        Long overallCount = (Long) mBeanServer.getAttribute(overallObjectName, "Count");
        assertEquals(1L, overallCount);
    }
    
    @Test
    void testRecordMultipleRequests() throws Exception {
        String metricName = "test.multiple";
        int statusCode = 200;
        
        recorder.recordRequest(metricName, statusCode, 100L);
        recorder.recordRequest(metricName, statusCode, 200L);
        recorder.recordRequest(metricName, statusCode, 150L);
        
        String statusKey = metricName + "." + statusCode;
        ObjectName objectName = new ObjectName("Grayskull:type=HttpClientMetrics,name=\"" + statusKey + "\"");
        
        Long count = (Long) mBeanServer.getAttribute(objectName, "Count");
        assertEquals(3L, count);
        
        Long totalDuration = (Long) mBeanServer.getAttribute(objectName, "TotalDurationMs");
        assertEquals(450L, totalDuration);
        
        Long avgDuration = (Long) mBeanServer.getAttribute(objectName, "AverageDurationMs");
        assertEquals(150L, avgDuration);
        
        Long maxDuration = (Long) mBeanServer.getAttribute(objectName, "MaxDurationMs");
        assertEquals(200L, maxDuration);
        
        Long minDuration = (Long) mBeanServer.getAttribute(objectName, "MinDurationMs");
        assertEquals(100L, minDuration);
    }
    
    @Test
    void testRecordRequestWithDifferentStatusCodes() throws Exception {
        String metricName = "test.status";
        
        recorder.recordRequest(metricName, 200, 100L);
        recorder.recordRequest(metricName, 404, 50L);
        recorder.recordRequest(metricName, 500, 200L);
        
        // Check 200 status tracker
        ObjectName obj200 = new ObjectName("Grayskull:type=HttpClientMetrics,name=\"" + metricName + ".200\"");
        assertEquals(1L, (Long) mBeanServer.getAttribute(obj200, "Count"));
        assertEquals(100L, (Long) mBeanServer.getAttribute(obj200, "TotalDurationMs"));
        
        // Check 404 status tracker
        ObjectName obj404 = new ObjectName("Grayskull:type=HttpClientMetrics,name=\"" + metricName + ".404\"");
        assertEquals(1L, (Long) mBeanServer.getAttribute(obj404, "Count"));
        assertEquals(50L, (Long) mBeanServer.getAttribute(obj404, "TotalDurationMs"));
        
        // Check 500 status tracker
        ObjectName obj500 = new ObjectName("Grayskull:type=HttpClientMetrics,name=\"" + metricName + ".500\"");
        assertEquals(1L, (Long) mBeanServer.getAttribute(obj500, "Count"));
        assertEquals(200L, (Long) mBeanServer.getAttribute(obj500, "TotalDurationMs"));
        
        // Check overall tracker
        ObjectName objOverall = new ObjectName("Grayskull:type=HttpClientMetrics,name=\"" + metricName + "\"");
        assertEquals(3L, (Long) mBeanServer.getAttribute(objOverall, "Count"));
        assertEquals(350L, (Long) mBeanServer.getAttribute(objOverall, "TotalDurationMs"));
    }
    
    @Test
    void testRecordRetry() throws Exception {
        String url = "http://localhost:8080/v1/secrets";
        
        recorder.recordRetry(url, 2, true);
        
        String path = "/v1/secrets";
        
        // Verify path-level tracker
        ObjectName pathObjectName = new ObjectName("Grayskull:type=HttpClientRetryMetrics,name=\"path." + path + "\"");
        assertTrue(mBeanServer.isRegistered(pathObjectName));
        
        Long totalRetries = (Long) mBeanServer.getAttribute(pathObjectName, "TotalRetries");
        assertEquals(1L, totalRetries);
        
        Long maxAttempts = (Long) mBeanServer.getAttribute(pathObjectName, "MaxAttempts");
        assertEquals(2L, maxAttempts);
        
        // Verify granular tracker (with status)
        ObjectName granularObjectName = new ObjectName("Grayskull:type=HttpClientRetryMetrics,name=\"path." + path + ".status.success\"");
        assertTrue(mBeanServer.isRegistered(granularObjectName));
        
        Long granularRetries = (Long) mBeanServer.getAttribute(granularObjectName, "TotalRetries");
        assertEquals(1L, granularRetries);
    }
    
    @Test
    void testRecordMultipleRetries() throws Exception {
        String url = "http://localhost:8080/v1/secrets";
        
        recorder.recordRetry(url, 1, true);
        recorder.recordRetry(url, 2, true);
        recorder.recordRetry(url, 3, false);
        
        String path = "/v1/secrets";
        ObjectName pathObjectName = new ObjectName("Grayskull:type=HttpClientRetryMetrics,name=\"path." + path + "\"");
        
        Long totalRetries = (Long) mBeanServer.getAttribute(pathObjectName, "TotalRetries");
        assertEquals(3L, totalRetries);
        
        Long maxAttempts = (Long) mBeanServer.getAttribute(pathObjectName, "MaxAttempts");
        assertEquals(3L, maxAttempts);
        
        Double avgAttempts = (Double) mBeanServer.getAttribute(pathObjectName, "AverageAttempts");
        assertEquals(2.0, avgAttempts, 0.01);
    }
    
    @Test
    void testRecordRetryWithSuccessAndFailure() throws Exception {
        String url = "http://localhost:8080/v1/secrets";
        String path = "/v1/secrets";
        
        recorder.recordRetry(url, 2, true);
        recorder.recordRetry(url, 3, false);
        
        // Check success tracker
        ObjectName successObjectName = new ObjectName("Grayskull:type=HttpClientRetryMetrics,name=\"path." + path + ".status.success\"");
        Long successRetries = (Long) mBeanServer.getAttribute(successObjectName, "TotalRetries");
        assertEquals(1L, successRetries);
        
        // Check failure tracker
        ObjectName failureObjectName = new ObjectName("Grayskull:type=HttpClientRetryMetrics,name=\"path." + path + ".status.failure\"");
        Long failureRetries = (Long) mBeanServer.getAttribute(failureObjectName, "TotalRetries");
        assertEquals(1L, failureRetries);
    }
}

