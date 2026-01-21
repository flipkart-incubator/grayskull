package errors_test

import (
	"errors"
	"testing"

	grayskullErrors "github.com/flipkart-incubator/grayskull/clients/go/client-impl/models/errors"
	"github.com/stretchr/testify/assert"
)

func TestBaseError(t *testing.T) {
	t.Run("Error with message", func(t *testing.T) {
		err := grayskullErrors.NewGrayskullError(0, "test error")
		assert.Equal(t, "test error", err.Error())
	})

	t.Run("Error with cause", func(t *testing.T) {
		cause := errors.New("root cause")
		err := grayskullErrors.NewGrayskullErrorWithCause(0, "test error", cause)
		assert.Equal(t, "test error: root cause", err.Error())
		assert.Equal(t, cause, err.Unwrap())
	})

	t.Run("Error with status code", func(t *testing.T) {
		err := grayskullErrors.NewGrayskullError(404, "test error")
		assert.Equal(t, 404, err.StatusCode())
	})
}

func TestGrayskullError(t *testing.T) {
	t.Run("NewGrayskullError", func(t *testing.T) {
		err := grayskullErrors.NewGrayskullError(400, "bad request")
		assert.Equal(t, "bad request", err.Error())
		assert.Equal(t, 400, err.StatusCode())
	})

	t.Run("NewGrayskullErrorWithCause", func(t *testing.T) {
		cause := errors.New("root cause")
		err := grayskullErrors.NewGrayskullErrorWithCause(500, "server error", cause)
		assert.Equal(t, "server error: root cause", err.Error())
		assert.Equal(t, 500, err.StatusCode())
	})

	t.Run("NewGrayskullErrorWithMessage", func(t *testing.T) {
		err := grayskullErrors.NewGrayskullErrorWithMessage("custom error")
		assert.Equal(t, "custom error", err.Error())
		assert.Equal(t, 0, err.StatusCode())
	})

}

func TestRetryableError(t *testing.T) {
	t.Run("NewRetryableErrorWithCause", func(t *testing.T) {
		cause := errors.New("root cause")
		err := grayskullErrors.NewRetryableErrorWithCause("retryable error", cause)
		assert.Equal(t, "retryable error: root cause", err.Error())
	})

	t.Run("NewRetryableErrorWithStatus", func(t *testing.T) {
		err := grayskullErrors.NewRetryableErrorWithStatus(503, "service unavailable")
		assert.Equal(t, "service unavailable", err.Error())
		assert.Equal(t, 503, err.StatusCode())
	})

}
