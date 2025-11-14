package com.flipkart.grayskull.utils;

import com.flipkart.grayskull.models.exceptions.GrayskullException;
import com.flipkart.grayskull.models.exceptions.RetryableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Utility class for retrying operations with exponential backoff.
 * <p>
 * This class implements a retry mechanism with exponential backoff for operations
 * that may fail transiently. It will retry operations that throw {@link RetryableException}
 * up to a maximum number of attempts, with increasing delays between attempts.
 * The wait time is capped at a maximum of 1 minute.
 * </p>
 */
public final class RetryUtil {
    private static final Logger log = LoggerFactory.getLogger(RetryUtil.class);
    private static final long MAX_WAIT_TIME_MS = 60000; // 1 minute

    private final int maxAttempt;
    private final int interval;

    /**
     * Constructor to initialize the RetryUtil with the number of attempts and the interval between retries.
     *
     * @param maxAttempt The maximum number of retry attempts.
     * @param interval   The initial interval between retries in milliseconds.
     */
    public RetryUtil(int maxAttempt, int interval) {
        this.maxAttempt = maxAttempt;
        this.interval = interval;
    }

    /**
     * Executes the given task with retry logic.
     * <p>
     * If the task throws a {@link RetryableException}, it will be retried up to maxAttempt times
     * with exponential backoff. If all retry attempts are exhausted, the {@link RetryableException}
     * is wrapped in a {@link GrayskullException}. Non-retryable exceptions are thrown immediately.
     * The wait time between retries is capped at a maximum of 1 minute.
     * </p>
     *
     * @param task The task to execute
     * @param <T>  The return type of the task
     * @return The result of the task
     * @throws GrayskullException if the task fails after all retry attempts
     * @throws Exception if the task throws a non-retryable exception
     */
    public <T> T retry(CheckedSupplier<T> task) throws Exception {
        // Generate a short retry ID to track this retry sequence in logs
        String retryId = UUID.randomUUID().toString().substring(0, 8);
        long currentInterval = interval;
        Exception lastException = null;
        
        for (int attempt = 1; attempt <= maxAttempt; attempt++) {
            try {
                log.debug("[retry:{}] Executing task, attempt {} of {}", retryId, attempt, maxAttempt);
                T result = task.get();
                if (attempt > 1) {
                    log.info("[retry:{}] Task succeeded on attempt {}", retryId, attempt);
                }
                return result;
            } catch (RetryableException e) {
                lastException = e;
                log.warn("[retry:{}] Retryable exception on attempt {} of {}: {}", retryId, attempt, maxAttempt, e.getMessage());
                
                if (attempt == maxAttempt) {
                    log.error("[retry:{}] Max retry attempts reached ({}), throwing exception", retryId, maxAttempt);
                    throw new GrayskullException(e.getStatusCode(), "Failed after " + maxAttempt + " retry attempts: " + e.getMessage(), e);
                }
                
                // Sleep before next retry (exponential backoff)
                try {
                    log.debug("[retry:{}] Waiting {}ms before retry", retryId, currentInterval);
                    Thread.sleep(currentInterval);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new GrayskullException("Retry interrupted", ie);
                }
                currentInterval = Math.min(currentInterval * 2, MAX_WAIT_TIME_MS); // Exponential backoff capped at 1 minute
            } 
        }
        throw new GrayskullException("Reached maximum attempts: " + maxAttempt, lastException);
    }

    /**
     * Functional interface representing a supplier that can throw an exception.
     *
     * @param <T> The type of the result supplied by this supplier.
     */
    @FunctionalInterface
    public interface CheckedSupplier<T> {
        T get() throws Exception;
    }
}

