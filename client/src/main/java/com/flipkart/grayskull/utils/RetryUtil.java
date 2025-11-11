package com.flipkart.grayskull.utils;

import com.flipkart.grayskull.models.exceptions.GrayskullException;
import com.flipkart.grayskull.models.exceptions.RetryableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for retrying operations with exponential backoff.
 * <p>
 * This class implements a retry mechanism with exponential backoff for operations
 * that may fail transiently. It will retry operations that throw {@link RetryableException}
 * up to a maximum number of attempts, with increasing delays between attempts.
 * </p>
 */
public final class RetryUtil {
    private static final Logger log = LoggerFactory.getLogger(RetryUtil.class);

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
     * with exponential backoff. Non-retryable exceptions are thrown immediately.
     * </p>
     *
     * @param task The task to execute
     * @param <T>  The return type of the task
     * @return The result of the task
     * @throws Exception if the task fails after all retry attempts or throws a non-retryable exception
     */
    public <T> T retry(CheckedSupplier<T> task) throws Exception {
        long currentInterval = interval;
        Exception lastException = null;
        
        for (int attempt = 1; attempt <= maxAttempt; attempt++) {
            try {
                log.debug("Executing task, attempt {} of {}", attempt, maxAttempt);
                T result = task.get();
                if (attempt > 1) {
                    log.info("Task succeeded on attempt {}", attempt);
                }
                return result;
            } catch (RetryableException e) {
                lastException = e;
                log.warn("Retryable exception on attempt {} of {}: {}", attempt, maxAttempt, e.getMessage());
                
                if (attempt == maxAttempt) {
                    log.error("Max retry attempts reached ({}), throwing exception", maxAttempt);
                    throw e; // Rethrow exception after max attempts
                }
                
                // Sleep before next retry (exponential backoff)
                try {
                    log.debug("Waiting {}ms before retry", currentInterval);
                    Thread.sleep(currentInterval);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new GrayskullException("Retry interrupted", ie);
                }
                currentInterval *= 2; // Exponential backoff
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

