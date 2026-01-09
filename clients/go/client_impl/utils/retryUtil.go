package utils

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"math"
	"time"

	"github.com/grayskull/client_impl/constants"
	"github.com/grayskull/client_impl/models/exceptions"
)

const (
	maxWaitTime = 1 * time.Minute // 1 minute
)

// RetryUtil implements a retry mechanism with exponential backoff.
// It retries operations that return RetryableError up to a maximum number of attempts,
// with increasing delays between attempts. The wait time is capped at 1 minute.
type RetryUtil struct {
	maxAttempts int
	initialWait time.Duration
	logger      *slog.Logger
}

// NewRetryUtil creates a new RetryUtil with the specified parameters.
func NewRetryUtil(maxAttempts int, initialWait time.Duration, logger *slog.Logger) *RetryUtil {
	if logger == nil {
		logger = slog.Default()
	}
	return &RetryUtil{
		maxAttempts: maxAttempts,
		initialWait: initialWait,
		logger:      logger,
	}
}

// Retry executes the provided function with retry logic.
// It will retry on RetryableError up to the maximum number of attempts.
func (r *RetryUtil) Retry(ctx context.Context, fn func() (interface{}, error)) (interface{}, error) {
	currentWait := r.initialWait

	for attempt := 1; attempt <= r.maxAttempts; attempt++ {
		// Get request ID from context
		requestID := ""
		if ctx != nil {
			if v := ctx.Value(constants.GrayskullRequestID); v != nil {
				if id, ok := v.(string); ok {
					requestID = id
				}
			}
		}

		// Log attempt
		r.logger.DebugContext(ctx, "executing task",
			"attempt", fmt.Sprintf("%d/%d", attempt, r.maxAttempts),
			"requestID", requestID,
		)

		// Execute the function
		result, err := fn()
		if err == nil {
			if attempt > 1 {
				r.logger.InfoContext(ctx, "task succeeded on retry",
					"attempt", attempt,
					"requestID", requestID,
				)
			}
			return result, nil
		}

		// Check if error is retryable
		var retryableErr *exceptions.RetryableError
		if !errors.As(err, &retryableErr) {
			return nil, err // Return non-retryable errors immediately
		}

		if attempt == r.maxAttempts {
			r.logger.ErrorContext(ctx, "max retry attempts reached",
				"maxAttempts", r.maxAttempts,
				"requestID", requestID,
				"error", err,
			)
			return nil, exceptions.NewGrayskullErrorWithCause(
				retryableErr.StatusCode,
				fmt.Sprintf("failed after %d retry attempts", r.maxAttempts),
				err,
			)
		}

		// Log warning and wait before retry
		r.logger.WarnContext(ctx, "retryable error, will retry",
			"attempt", fmt.Sprintf("%d/%d", attempt, r.maxAttempts),
			"waitTime", currentWait,
			"requestID", requestID,
			"error", err,
		)

		// Wait with exponential backoff
		select {
		case <-time.After(currentWait):
			// Continue with next attempt
		case <-ctx.Done():
			return nil, exceptions.NewGrayskullErrorWithCause(0, "retry operation canceled", ctx.Err())
		}

		// Calculate next wait time with exponential backoff, capped at maxWaitTime
		currentWait = time.Duration(math.Min(float64(currentWait*2), float64(maxWaitTime)))
	}
	// Return an error when max attempts are reached without success
	return nil, fmt.Errorf("maximum number of retry attempts (%d) reached", r.maxAttempts)
}
