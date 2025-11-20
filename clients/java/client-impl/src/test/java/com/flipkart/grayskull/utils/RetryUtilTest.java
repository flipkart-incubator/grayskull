package com.flipkart.grayskull.utils;

import com.flipkart.grayskull.models.exceptions.GrayskullException;
import com.flipkart.grayskull.models.exceptions.RetryableException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RetryUtil.
 */
class RetryUtilTest {

    @Test
    void testRetry_successOnFirstAttempt() throws Exception {
        // Given
        RetryUtil retryUtil = new RetryUtil(3, 100);
        AtomicInteger attemptCount = new AtomicInteger(0);

        // When
        String result = retryUtil.retry(() -> {
            attemptCount.incrementAndGet();
            return "success";
        });

        // Then
        assertEquals("success", result);
        assertEquals(1, attemptCount.get(), "Should succeed on first attempt");
    }

    @Test
    void testRetry_successOnSecondAttempt() throws Exception {
        // Given
        RetryUtil retryUtil = new RetryUtil(3, 100);
        AtomicInteger attemptCount = new AtomicInteger(0);

        // When
        String result = retryUtil.retry(() -> {
            int attempt = attemptCount.incrementAndGet();
            if (attempt == 1) {
                throw new RetryableException("Transient error");
            }
            return "success";
        });

        // Then
        assertEquals("success", result);
        assertEquals(2, attemptCount.get(), "Should succeed on second attempt");
    }

    @Test
    void testRetry_exhaustsAllAttempts() {
        // Given
        RetryUtil retryUtil = new RetryUtil(3, 100);
        AtomicInteger attemptCount = new AtomicInteger(0);

        // When/Then
        GrayskullException exception = assertThrows(GrayskullException.class, () -> 
            retryUtil.retry(() -> {
                attemptCount.incrementAndGet();
                throw new RetryableException("Always fails");
            })
        );

        assertEquals(3, attemptCount.get(), "Should attempt exactly 3 times");
        assertTrue(exception.getMessage().contains("Failed after 3 retry attempts"));
        assertInstanceOf(RetryableException.class, exception.getCause());
    }

    @Test
    void testRetry_nonRetryableException() {
        // Given
        RetryUtil retryUtil = new RetryUtil(3, 100);
        AtomicInteger attemptCount = new AtomicInteger(0);

        // When/Then
        GrayskullException exception = assertThrows(GrayskullException.class, () ->
            retryUtil.retry(() -> {
                attemptCount.incrementAndGet();
                throw new GrayskullException(404, "Not found");
            })
        );

        assertEquals(1, attemptCount.get(), "Should not retry non-retryable exceptions");
        assertEquals(404, exception.getStatusCode());
        assertEquals("Not found", exception.getMessage());
    }

    @Test
    void testRetry_exponentialBackoff() throws Exception {
        // Given
        RetryUtil retryUtil = new RetryUtil(4, 100);
        List<Long> waitTimes = new ArrayList<>();
        AtomicInteger attemptCount = new AtomicInteger(0);

        // When
        long startTime = System.currentTimeMillis();
        try {
            retryUtil.retry(() -> {
                int attempt = attemptCount.incrementAndGet();
                long currentTime = System.currentTimeMillis();
                
                if (attempt > 1) {
                    waitTimes.add(currentTime - startTime);
                }
                
                if (attempt < 4) {
                    throw new RetryableException("Retry attempt " + attempt);
                }
                return "success";
            });
        } catch (Exception e) {
            fail("Should not throw exception");
        }

        // Then - verify exponential backoff pattern
        assertEquals(4, attemptCount.get(), "Should make 4 attempts");
        assertEquals(3, waitTimes.size(), "Should have 3 wait periods");

        // First retry after ~100ms
        assertTrue(waitTimes.get(0) >= 100 && waitTimes.get(0) < 150, 
                "First retry should be after ~100ms, was: " + waitTimes.get(0));
        
        // Second retry after ~300ms (100 + 200)
        assertTrue(waitTimes.get(1) >= 300 && waitTimes.get(1) < 400,
                "Second retry should be after ~300ms, was: " + waitTimes.get(1));
        
        // Third retry after ~700ms (100 + 200 + 400)
        assertTrue(waitTimes.get(2) >= 700 && waitTimes.get(2) < 900,
                "Third retry should be after ~700ms, was: " + waitTimes.get(2));
    }

    @Test
    void testRetry_backoffCapAt1Minute() {
        // Given - start with large interval to test the cap
        RetryUtil retryUtil = new RetryUtil(3, 35000); // 35 seconds initial
        AtomicInteger attemptCount = new AtomicInteger(0);

        // When/Then - should cap at 60000ms (1 minute)
        long startTime = System.currentTimeMillis();
        
        assertThrows(GrayskullException.class, () ->
            retryUtil.retry(() -> {
                attemptCount.incrementAndGet();
                throw new RetryableException("Always fails");
            })
        );

        long duration = System.currentTimeMillis() - startTime;
        
        // Total wait time should be: 35s + 60s (capped) = 95s (~1.5 minutes)
        // With some margin: should be at least 90s and less than 110s
        assertTrue(duration >= 90000 && duration < 110000,
                "Duration should be capped at 1 minute per retry: " + duration + "ms");
        assertEquals(3, attemptCount.get());
    }

    @Test
    void testRetry_interruptedDuringSleep() {
        // Given
        RetryUtil retryUtil = new RetryUtil(3, 100);
        AtomicInteger attemptCount = new AtomicInteger(0);

        // When - interrupt the thread during retry
        Thread testThread = new Thread(() -> {
            try {
                retryUtil.retry(() -> {
                    int attempt = attemptCount.incrementAndGet();
                    if (attempt == 1) {
                        // Interrupt after first failure
                        Thread.currentThread().interrupt();
                        throw new RetryableException("First attempt failed");
                    }
                    return "success";
                });
                fail("Should throw GrayskullException");
            } catch (GrayskullException e) {
                assertTrue(e.getMessage().contains("Retry interrupted"));
                assertInstanceOf(InterruptedException.class, e.getCause());
            } catch (Exception e) {
                fail("Unexpected exception: " + e);
            }
        });

        testThread.start();
        try {
            testThread.join(5000);
        } catch (InterruptedException e) {
            fail("Test thread interrupted");
        }

        assertEquals(1, attemptCount.get(), "Should stop after first attempt when interrupted");
    }
}

