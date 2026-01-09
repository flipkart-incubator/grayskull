package utils

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"time"

	"github.com/google/uuid"
	"github.com/grayskull/go-client/client-impl/constants"
	"github.com/grayskull/go-client/client-impl/models/exceptions"
)

const (
	// MaxWaitTime is the maximum wait time between retries (1 minute)
	MaxWaitTime = 60 * time.Second
)

// RetryUtil provides retry functionality with exponential backoff.
// It implements a retry mechanism for operations that may fail transiently.
type RetryUtil struct {
	maxAttempts int
	initialWait time.Duration
}

// NewRetryUtil creates a new RetryUtil instance with the specified parameters.
// maxAttempts: Maximum number of retry attempts
// initialWait: Initial wait time between retries
func NewRetryUtil(maxAttempts int, initialWait time.Duration) *RetryUtil {
	return &RetryUtil{
		maxAttempts: maxAttempts,
		initialWait: initialWait,
	}
}

// TaskFunc represents a function that can be retried.
type TaskFunc func() (interface{}, error)

// Retry executes the provided task with retry logic.
// It will retry the task if it returns a RetryableError, up to the maximum number of attempts.
// Returns the result of the task or an error if all attempts fail.
func (r *RetryUtil) Retry(ctx context.Context, task TaskFunc) (interface{}, error) {
	currentWait := r.initialWait
	requestID := getRequestID(ctx)

	for attempt := 1; attempt <= r.maxAttempts; attempt++ {
		slog.DebugContext(ctx, "Executing task",
			slog.String("requestID", requestID),
			slog.Int("attempt", attempt),
			slog.Int("maxAttempts", r.maxAttempts),
		)

		// Execute the task
		result, err := task()

		// If no error, return the result
		if err == nil {
			if attempt > 1 {
				slog.InfoContext(ctx, "Task succeeded on attempt",
					slog.String("requestID", requestID),
					slog.Int("attempt", attempt),
				)
			}
			return result, nil
		}

		// Check if the error is retryable
		var retryableErr *exceptions.RetryableError
		if !errors.As(err, &retryableErr) {
			// Non-retryable error, return immediately
			return nil, err
		}

		slog.WarnContext(ctx, "Retryable error occurred",
			slog.String("requestID", requestID),
			slog.Int("attempt", attempt),
			slog.Int("maxAttempts", r.maxAttempts),
			slog.Any("error", err),
		)

		if attempt >= r.maxAttempts {
			errMsg := fmt.Sprintf("failed after %d retry attempts: %v", r.maxAttempts, err)
			slog.ErrorContext(ctx, "Max retry attempts reached",
				slog.String("requestID", requestID),
			)
			return nil, exceptions.NewGrayskullErrorWithCause(retryableErr.StatusCode(), errMsg, err)
		}

		// Wait before next retry with exponential backoff
		slog.DebugContext(ctx, "Waiting before retry",
			slog.String("requestID", requestID),
			slog.Duration("waitTime", currentWait),
		)

		select {
		case <-ctx.Done():
			return nil, exceptions.NewGrayskullErrorWithCause(0, "retry interrupted", ctx.Err())
		case <-time.After(currentWait):
			// Continue with the next attempt
		}

		// Exponential backoff with max cap
		currentWait = time.Duration(float64(currentWait) * 1.5)
		if currentWait > MaxWaitTime {
			currentWait = MaxWaitTime
		}
	}
	return nil, exceptions.NewGrayskullError(500, "max retry attempts reached")
}

// getRequestID extracts the request ID from the context or generates a new one.
func getRequestID(ctx context.Context) string {
	if ctx == nil {
		return uuid.New().String()
	}

	if reqID, ok := ctx.Value(constants.GrayskullRequestID).(string); ok && reqID != "" {
		return reqID
	}

	return uuid.New().String()
}
