package exceptions_test

import (
	"errors"
	"testing"

	"github.com/flipkart-incubator/grayskull/client-impl/models/exceptions"
	"github.com/stretchr/testify/assert"
)

func TestBaseError(t *testing.T) {
	t.Run("Error with message", func(t *testing.T) {
		err := &exceptions.BaseError{Message: "test error"}
		assert.Equal(t, "test error", err.Error())
	})

	t.Run("Error with cause", func(t *testing.T) {
		cause := errors.New("root cause")
		err := &exceptions.BaseError{
			Message: "test error",
			Cause:   cause,
		}
		assert.Equal(t, "test error: root cause", err.Error())
		assert.Equal(t, cause, err.Unwrap())
	})

	t.Run("Error with status code", func(t *testing.T) {
		err := &exceptions.BaseError{
			Message:    "test error",
			StatusCode: 404,
		}
		assert.Equal(t, 404, err.StatusCode)
	})
}

func TestGrayskullError(t *testing.T) {
	t.Run("NewGrayskullError", func(t *testing.T) {
		err := exceptions.NewGrayskullError(400, "bad request")
		assert.Equal(t, "bad request", err.Error())
		assert.Equal(t, 400, err.StatusCode)
	})

	t.Run("NewGrayskullErrorWithCause", func(t *testing.T) {
		cause := errors.New("root cause")
		err := exceptions.NewGrayskullErrorWithCause(500, "server error", cause)
		assert.Equal(t, "server error: root cause", err.Error())
		assert.Equal(t, 500, err.StatusCode)
		assert.Equal(t, cause, err.Cause)
	})

	t.Run("NewGrayskullErrorFromException", func(t *testing.T) {
		cause := errors.New("underlying error")
		err := exceptions.NewGrayskullErrorFromException(401, cause)
		// The error message includes the cause's error message
		assert.Equal(t, "underlying error: underlying error", err.Error())
		assert.Equal(t, 401, err.StatusCode)
		assert.Equal(t, cause, err.Cause)
	})

	t.Run("NewGrayskullErrorWithMessage", func(t *testing.T) {
		err := exceptions.NewGrayskullErrorWithMessage("custom error")
		assert.Equal(t, "custom error", err.Error())
		assert.Equal(t, 0, err.StatusCode)
	})

	t.Run("NewGrayskullErrorWithMessageAndCause", func(t *testing.T) {
		cause := errors.New("underlying issue")
		err := exceptions.NewGrayskullErrorWithMessageAndCause("something went wrong", cause)
		assert.Equal(t, "something went wrong: underlying issue", err.Error())
		assert.Equal(t, 0, err.StatusCode)
		assert.Equal(t, cause, err.Cause)
	})
}

func TestRetryableError(t *testing.T) {
	t.Run("NewRetryableError", func(t *testing.T) {
		err := exceptions.NewRetryableError("retryable error")
		assert.Equal(t, "retryable error", err.Error())
		assert.Equal(t, 0, err.StatusCode)
	})

	t.Run("NewRetryableErrorWithCause", func(t *testing.T) {
		cause := errors.New("root cause")
		err := exceptions.NewRetryableErrorWithCause("retryable error", cause)
		assert.Equal(t, "retryable error: root cause", err.Error())
		assert.Equal(t, cause, err.Cause)
	})

	t.Run("NewRetryableErrorWithStatus", func(t *testing.T) {
		err := exceptions.NewRetryableErrorWithStatus(503, "service unavailable")
		assert.Equal(t, "service unavailable", err.Error())
		assert.Equal(t, 503, err.StatusCode)
	})

	t.Run("NewRetryableErrorWithStatusAndCause", func(t *testing.T) {
		cause := errors.New("connection timeout")
		err := exceptions.NewRetryableErrorWithStatusAndCause(504, "gateway timeout", cause)
		assert.Equal(t, "gateway timeout: connection timeout", err.Error())
		assert.Equal(t, 504, err.StatusCode)
		assert.Equal(t, cause, err.Cause)
	})
}
