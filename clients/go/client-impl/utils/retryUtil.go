package utils

import (
	"context"
	"errors"
	"math"
	"math/rand"
	"time"

	"github.com/flipkart-incubator/grayskull/client-impl/models/exceptions"
)

// RetryConfig holds configuration for retry behavior
type RetryConfig struct {
	MaxAttempts   int
	InitialDelay  time.Duration
	MaxRetryDelay time.Duration
}

// RetryUtil provides retry functionality with exponential backoff
type RetryUtil struct {
	config RetryConfig
}

// NewRetryUtil creates a new RetryUtil with the given configuration
func NewRetryUtil(config RetryConfig) *RetryUtil {
	if config.MaxAttempts <= 0 {
		config.MaxAttempts = 3
	}
	if config.InitialDelay <= 0 {
		config.InitialDelay = 100 * time.Millisecond
	}
	if config.MaxRetryDelay <= 0 {
		config.MaxRetryDelay = time.Minute
	}

	return &RetryUtil{
		config: config,
	}
}

// Retry executes the task with retry logic
func (r *RetryUtil) Retry(ctx context.Context, task func() (interface{}, error)) (interface{}, error) {
	var lastErr error
	delay := r.config.InitialDelay

	for attempt := 1; attempt <= r.config.MaxAttempts; attempt++ {
		// Check if context is done before each attempt
		if err := ctx.Err(); err != nil {
			return nil, exceptions.NewRetryableErrorWithCause("operation canceled", err)
		}

		// Execute the task
		result, err := task()
		if err == nil {
			return result, nil
		}

		// Check if error is retryable
		var retryableErr *exceptions.RetryableError
		if !errors.As(err, &retryableErr) {
			return nil, err // Non-retryable error
		}

		lastErr = retryableErr
		if attempt >= r.config.MaxAttempts {
			break
		}

		// Calculate backoff with jitter
		delay = time.Duration(
			math.Min(
				float64(r.config.InitialDelay)*math.Pow(2, float64(attempt-1)),
				float64(r.config.MaxRetryDelay),
			),
		)
		// Add jitter (up to 25% of the delay)
		jitter := time.Duration(rand.Float64() * 0.25 * float64(delay))
		delay += jitter

		// Wait before next retry
		select {
		case <-time.After(delay):
		case <-ctx.Done():
			return nil, exceptions.NewRetryableErrorWithCause("operation canceled during retry", ctx.Err())
		}
	}

	// If we've exhausted all retry attempts, return a GrayskullError
	return nil, exceptions.NewGrayskullErrorWithMessageAndCause(
		"max retry attempts reached",
		lastErr,
	)
}
