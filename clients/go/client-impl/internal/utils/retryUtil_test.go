package utils

import (
	"context"
	"errors"
	"testing"
	"time"

	grayskullErrors "github.com/flipkart-incubator/grayskull/clients/go/client-impl/models/errors"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestNewRetryUtil(t *testing.T) {
	t.Run("creates retry util with valid config", func(t *testing.T) {
		config := RetryConfig{
			MaxAttempts:   5,
			InitialDelay:  200 * time.Millisecond,
			MaxRetryDelay: 2 * time.Minute,
		}

		retryUtil := NewRetryUtil(config)

		require.NotNil(t, retryUtil)
		assert.Equal(t, 5, retryUtil.config.MaxAttempts)
		assert.Equal(t, 200*time.Millisecond, retryUtil.config.InitialDelay)
		assert.Equal(t, 2*time.Minute, retryUtil.config.MaxRetryDelay)
	})

	t.Run("uses default max attempts when zero", func(t *testing.T) {
		config := RetryConfig{
			MaxAttempts:   0,
			InitialDelay:  100 * time.Millisecond,
			MaxRetryDelay: time.Minute,
		}

		retryUtil := NewRetryUtil(config)

		assert.Equal(t, 3, retryUtil.config.MaxAttempts)
	})

	t.Run("uses default max attempts when negative", func(t *testing.T) {
		config := RetryConfig{
			MaxAttempts:   -5,
			InitialDelay:  100 * time.Millisecond,
			MaxRetryDelay: time.Minute,
		}

		retryUtil := NewRetryUtil(config)

		assert.Equal(t, 3, retryUtil.config.MaxAttempts)
	})

	t.Run("uses default initial delay when zero", func(t *testing.T) {
		config := RetryConfig{
			MaxAttempts:   3,
			InitialDelay:  0,
			MaxRetryDelay: time.Minute,
		}

		retryUtil := NewRetryUtil(config)

		assert.Equal(t, 100*time.Millisecond, retryUtil.config.InitialDelay)
	})

	t.Run("uses default initial delay when negative", func(t *testing.T) {
		config := RetryConfig{
			MaxAttempts:   3,
			InitialDelay:  -100 * time.Millisecond,
			MaxRetryDelay: time.Minute,
		}

		retryUtil := NewRetryUtil(config)

		assert.Equal(t, 100*time.Millisecond, retryUtil.config.InitialDelay)
	})

	t.Run("uses default max retry delay when zero", func(t *testing.T) {
		config := RetryConfig{
			MaxAttempts:   3,
			InitialDelay:  100 * time.Millisecond,
			MaxRetryDelay: 0,
		}

		retryUtil := NewRetryUtil(config)

		assert.Equal(t, time.Minute, retryUtil.config.MaxRetryDelay)
	})

	t.Run("uses default max retry delay when negative", func(t *testing.T) {
		config := RetryConfig{
			MaxAttempts:   3,
			InitialDelay:  100 * time.Millisecond,
			MaxRetryDelay: -time.Minute,
		}

		retryUtil := NewRetryUtil(config)

		assert.Equal(t, time.Minute, retryUtil.config.MaxRetryDelay)
	})

	t.Run("uses all defaults when config is empty", func(t *testing.T) {
		config := RetryConfig{}

		retryUtil := NewRetryUtil(config)

		assert.Equal(t, 3, retryUtil.config.MaxAttempts)
		assert.Equal(t, 100*time.Millisecond, retryUtil.config.InitialDelay)
		assert.Equal(t, time.Minute, retryUtil.config.MaxRetryDelay)
	})
}

func TestRetry_Success(t *testing.T) {
	t.Run("succeeds on first attempt", func(t *testing.T) {
		config := RetryConfig{
			MaxAttempts:   3,
			InitialDelay:  10 * time.Millisecond,
			MaxRetryDelay: time.Second,
		}
		retryUtil := NewRetryUtil(config)

		attemptCount := 0
		task := func() (string, error) {
			attemptCount++
			return "success", nil
		}

		result, err := Retry(context.Background(), retryUtil, task)

		assert.NoError(t, err)
		assert.Equal(t, "success", result)
		assert.Equal(t, 1, attemptCount)
	})

	t.Run("succeeds on second attempt", func(t *testing.T) {
		config := RetryConfig{
			MaxAttempts:   3,
			InitialDelay:  10 * time.Millisecond,
			MaxRetryDelay: time.Second,
		}
		retryUtil := NewRetryUtil(config)

		attemptCount := 0
		task := func() (string, error) {
			attemptCount++
			if attemptCount == 1 {
				return "", grayskullErrors.NewRetryableErrorWithStatus(0, "temporary failure")
			}
			return "success", nil
		}

		result, err := Retry(context.Background(), retryUtil, task)

		assert.NoError(t, err)
		assert.Equal(t, "success", result)
		assert.Equal(t, 2, attemptCount)
	})

	t.Run("succeeds on last attempt", func(t *testing.T) {
		config := RetryConfig{
			MaxAttempts:   3,
			InitialDelay:  10 * time.Millisecond,
			MaxRetryDelay: time.Second,
		}
		retryUtil := NewRetryUtil(config)

		attemptCount := 0
		task := func() (string, error) {
			attemptCount++
			if attemptCount < 3 {
				return "", grayskullErrors.NewRetryableErrorWithStatus(0, "temporary failure")
			}
			return "success", nil
		}

		result, err := Retry(context.Background(), retryUtil, task)

		assert.NoError(t, err)
		assert.Equal(t, "success", result)
		assert.Equal(t, 3, attemptCount)
	})
}

func TestRetry_Failure(t *testing.T) {
	t.Run("fails with non-retryable error", func(t *testing.T) {
		config := RetryConfig{
			MaxAttempts:   3,
			InitialDelay:  10 * time.Millisecond,
			MaxRetryDelay: time.Second,
		}
		retryUtil := NewRetryUtil(config)

		attemptCount := 0
		task := func() (interface{}, error) {
			attemptCount++
			return nil, grayskullErrors.NewGrayskullErrorWithMessage("permanent error")
		}

		result, err := Retry(context.Background(), retryUtil, task)

		assert.Error(t, err)
		assert.Nil(t, result)
		assert.Equal(t, 1, attemptCount)
		assert.Contains(t, err.Error(), "permanent error")
	})

	t.Run("exhausts all retry attempts", func(t *testing.T) {
		config := RetryConfig{
			MaxAttempts:   3,
			InitialDelay:  10 * time.Millisecond,
			MaxRetryDelay: time.Second,
		}
		retryUtil := NewRetryUtil(config)

		attemptCount := 0
		task := func() (interface{}, error) {
			attemptCount++
			return nil, grayskullErrors.NewRetryableErrorWithStatus(0, "always fails")
		}

		result, err := Retry(context.Background(), retryUtil, task)

		assert.Error(t, err)
		assert.Nil(t, result)
		assert.Equal(t, 3, attemptCount)
		assert.Contains(t, err.Error(), "max retry attempts reached")
	})

	t.Run("returns GrayskullError after exhausting retries", func(t *testing.T) {
		config := RetryConfig{
			MaxAttempts:   2,
			InitialDelay:  10 * time.Millisecond,
			MaxRetryDelay: time.Second,
		}
		retryUtil := NewRetryUtil(config)

		task := func() (interface{}, error) {
			return nil, grayskullErrors.NewRetryableErrorWithStatus(0, "retryable error")
		}

		result, err := Retry(context.Background(), retryUtil, task)

		assert.Error(t, err)
		assert.Nil(t, result)

		var grayskullErr *grayskullErrors.GrayskullError
		assert.True(t, errors.As(err, &grayskullErr))
	})
}

func TestRetry_ContextCancellation(t *testing.T) {
	t.Run("returns error when context is canceled before first attempt", func(t *testing.T) {
		config := RetryConfig{
			MaxAttempts:   3,
			InitialDelay:  10 * time.Millisecond,
			MaxRetryDelay: time.Second,
		}
		retryUtil := NewRetryUtil(config)

		ctx, cancel := context.WithCancel(context.Background())
		cancel() // Cancel immediately

		task := func() (string, error) {
			return "should not execute", nil
		}

		result, err := Retry(ctx, retryUtil, task)

		assert.Error(t, err)
		assert.Equal(t, "", result)
		assert.Contains(t, err.Error(), "operation canceled")
	})

	t.Run("returns error when context is canceled during retry", func(t *testing.T) {
		config := RetryConfig{
			MaxAttempts:   5,
			InitialDelay:  50 * time.Millisecond,
			MaxRetryDelay: time.Second,
		}
		retryUtil := NewRetryUtil(config)

		ctx, cancel := context.WithCancel(context.Background())

		attemptCount := 0
		task := func() (interface{}, error) {
			attemptCount++
			if attemptCount == 2 {
				cancel() // Cancel after second attempt
			}
			return nil, grayskullErrors.NewRetryableErrorWithStatus(0, "temporary failure")
		}

		result, err := Retry(ctx, retryUtil, task)

		assert.Error(t, err)
		assert.Nil(t, result)
		assert.Contains(t, err.Error(), "operation canceled during retry")
	})

	t.Run("respects context timeout", func(t *testing.T) {
		config := RetryConfig{
			MaxAttempts:   10,
			InitialDelay:  100 * time.Millisecond,
			MaxRetryDelay: time.Second,
		}
		retryUtil := NewRetryUtil(config)

		ctx, cancel := context.WithTimeout(context.Background(), 150*time.Millisecond)
		defer cancel()

		attemptCount := 0
		task := func() (interface{}, error) {
			attemptCount++
			return nil, grayskullErrors.NewRetryableErrorWithStatus(0, "temporary failure")
		}

		result, err := Retry(ctx, retryUtil, task)

		assert.Error(t, err)
		assert.Nil(t, result)
		assert.Less(t, attemptCount, 10) // Should not complete all attempts
	})
}

func TestRetry_ExponentialBackoff(t *testing.T) {
	t.Run("applies exponential backoff with jitter", func(t *testing.T) {
		config := RetryConfig{
			MaxAttempts:   4,
			InitialDelay:  50 * time.Millisecond,
			MaxRetryDelay: time.Second,
		}
		retryUtil := NewRetryUtil(config)

		attemptTimes := []time.Time{}
		task := func() (string, error) {
			attemptTimes = append(attemptTimes, time.Now())
			if len(attemptTimes) < 4 {
				return "", grayskullErrors.NewRetryableErrorWithStatus(0, "retry")
			}
			return "success", nil
		}

		startTime := time.Now()
		result, err := Retry(context.Background(), retryUtil, task)

		assert.NoError(t, err)
		assert.Equal(t, "success", result)
		assert.Len(t, attemptTimes, 4)

		// Verify total time is reasonable (with backoff)
		totalTime := time.Since(startTime)
		assert.Greater(t, totalTime, 150*time.Millisecond) // At least some backoff
		assert.Less(t, totalTime, 2*time.Second)           // Not too long
	})

	t.Run("respects max retry delay", func(t *testing.T) {
		config := RetryConfig{
			MaxAttempts:   10,
			InitialDelay:  100 * time.Millisecond,
			MaxRetryDelay: 200 * time.Millisecond, // Low max to test capping
		}
		retryUtil := NewRetryUtil(config)

		attemptTimes := []time.Time{}
		task := func() (string, error) {
			attemptTimes = append(attemptTimes, time.Now())
			if len(attemptTimes) < 5 {
				return "", grayskullErrors.NewRetryableErrorWithStatus(0, "retry")
			}
			return "success", nil
		}

		startTime := time.Now()
		_, err := Retry(context.Background(), retryUtil, task)

		assert.NoError(t, err)
		totalTime := time.Since(startTime)

		// With max delay of 200ms and 4 retries, total should be reasonable
		assert.Less(t, totalTime, 2*time.Second)
	})
}

func TestRetry_EdgeCases(t *testing.T) {
	t.Run("handles task returning nil result with no error", func(t *testing.T) {
		config := RetryConfig{
			MaxAttempts:   3,
			InitialDelay:  10 * time.Millisecond,
			MaxRetryDelay: time.Second,
		}
		retryUtil := NewRetryUtil(config)

		task := func() (interface{}, error) {
			return nil, nil
		}

		result, err := Retry(context.Background(), retryUtil, task)

		assert.NoError(t, err)
		assert.Nil(t, result)
	})

	t.Run("handles different return types", func(t *testing.T) {
		config := RetryConfig{
			MaxAttempts:   3,
			InitialDelay:  10 * time.Millisecond,
			MaxRetryDelay: time.Second,
		}
		retryUtil := NewRetryUtil(config)

		// Test with struct
		type TestStruct struct {
			Value string
		}

		task := func() (TestStruct, error) {
			return TestStruct{Value: "test"}, nil
		}

		result, err := Retry(context.Background(), retryUtil, task)

		assert.NoError(t, err)
		assert.Equal(t, TestStruct{Value: "test"}, result)
	})

	t.Run("handles single attempt configuration", func(t *testing.T) {
		config := RetryConfig{
			MaxAttempts:   1,
			InitialDelay:  10 * time.Millisecond,
			MaxRetryDelay: time.Second,
		}
		retryUtil := NewRetryUtil(config)

		attemptCount := 0
		task := func() (interface{}, error) {
			attemptCount++
			return nil, grayskullErrors.NewRetryableErrorWithStatus(0, "error")
		}

		result, err := Retry(context.Background(), retryUtil, task)

		assert.Error(t, err)
		assert.Nil(t, result)
		assert.Equal(t, 1, attemptCount)
	})
}

func TestRetry_E2E_RealWorldScenarios(t *testing.T) {
	t.Run("simulates network timeout recovery", func(t *testing.T) {
		config := RetryConfig{
			MaxAttempts:   3,
			InitialDelay:  50 * time.Millisecond,
			MaxRetryDelay: time.Second,
		}
		retryUtil := NewRetryUtil(config)

		attemptCount := 0
		task := func() (map[string]string, error) {
			attemptCount++
			if attemptCount <= 2 {
				return nil, grayskullErrors.NewRetryableErrorWithCause("network timeout", grayskullErrors.NewGrayskullErrorWithMessage("timeout"))
			}
			return map[string]string{"status": "ok"}, nil
		}

		result, err := Retry(context.Background(), retryUtil, task)

		assert.NoError(t, err)
		assert.NotNil(t, result)
		assert.Equal(t, 3, attemptCount)
	})

	t.Run("simulates service unavailable then recovery", func(t *testing.T) {
		config := RetryConfig{
			MaxAttempts:   5,
			InitialDelay:  20 * time.Millisecond,
			MaxRetryDelay: time.Second,
		}
		retryUtil := NewRetryUtil(config)

		attemptCount := 0
		task := func() (string, error) {
			attemptCount++
			if attemptCount < 4 {
				return "", grayskullErrors.NewRetryableErrorWithStatus(503, "service unavailable")
			}
			return "service recovered", nil
		}

		result, err := Retry(context.Background(), retryUtil, task)

		assert.NoError(t, err)
		assert.Equal(t, "service recovered", result)
		assert.Equal(t, 4, attemptCount)
	})

	t.Run("simulates permanent failure after retries", func(t *testing.T) {
		config := RetryConfig{
			MaxAttempts:   3,
			InitialDelay:  10 * time.Millisecond,
			MaxRetryDelay: time.Second,
		}
		retryUtil := NewRetryUtil(config)

		attemptCount := 0
		task := func() (interface{}, error) {
			attemptCount++
			return nil, grayskullErrors.NewRetryableErrorWithStatus(500, "internal server error")
		}

		result, err := Retry(context.Background(), retryUtil, task)

		assert.Error(t, err)
		assert.Nil(t, result)
		assert.Equal(t, 3, attemptCount)
		assert.Contains(t, err.Error(), "max retry attempts reached")
	})
}

func BenchmarkRetry_Success(b *testing.B) {
	config := RetryConfig{
		MaxAttempts:   3,
		InitialDelay:  10 * time.Millisecond,
		MaxRetryDelay: time.Second,
	}
	retryUtil := NewRetryUtil(config)

	task := func() (string, error) {
		return "success", nil
	}

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		_, _ = Retry(context.Background(), retryUtil, task)
	}
}

func BenchmarkRetry_WithRetries(b *testing.B) {
	config := RetryConfig{
		MaxAttempts:   3,
		InitialDelay:  1 * time.Millisecond,
		MaxRetryDelay: time.Second,
	}
	retryUtil := NewRetryUtil(config)

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		attemptCount := 0
		task := func() (string, error) {
			attemptCount++
			if attemptCount < 2 {
				return "", grayskullErrors.NewRetryableErrorWithStatus(0, "retry")
			}
			return "success", nil
		}
		_, _ = Retry(context.Background(), retryUtil, task)
	}
}
