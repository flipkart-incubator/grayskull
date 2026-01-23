// Package errors provides custom error types for the Grayskull client
package errors

// BaseError provides common error functionality
type BaseError struct {
	statusCode int
	message    string
	cause      error
}

// Error implements the error interface
func (e *BaseError) Error() string {
	if e.cause != nil {
		return e.message + ": " + e.cause.Error()
	}
	return e.message
}

// StatusCode returns the HTTP status code associated with the error
func (e *BaseError) StatusCode() int {
	return e.statusCode
}

// Unwrap returns the underlying cause of the error
func (e *BaseError) Unwrap() error {
	return e.cause
}

// GrayskullError represents an error that occurs in the Grayskull client.
// It includes a status code and implements the error interface.
type GrayskullError struct {
	BaseError
}

// NewGrayskullError creates a new GrayskullError with status code and message
func NewGrayskullError(statusCode int, message string) *GrayskullError {
	return &GrayskullError{
		BaseError: BaseError{
			statusCode: statusCode,
			message:    message,
		},
	}
}

// NewGrayskullErrorWithCause creates a new GrayskullError with status code, message, and cause
func NewGrayskullErrorWithCause(statusCode int, message string, cause error) *GrayskullError {
	return &GrayskullError{
		BaseError: BaseError{
			statusCode: statusCode,
			message:    message,
			cause:      cause,
		},
	}
}

// NewGrayskullErrorWithMessage creates a new GrayskullError with just a message (status code 0)
func NewGrayskullErrorWithMessage(message string) *GrayskullError {
	return &GrayskullError{
		BaseError: BaseError{
			statusCode: 0,
			message:    message,
		},
	}
}

// RetryableError indicates that an operation failed but can be retried.
// This error is thrown for transient errors such as network timeouts,
// connection failures, or temporary server unavailability (5xx errors).
type RetryableError struct {
	BaseError
}

// NewRetryableErrorWithCause creates a new RetryableError with message and cause
func NewRetryableErrorWithCause(message string, cause error) *RetryableError {
	return &RetryableError{
		BaseError: BaseError{
			statusCode: 0,
			message:    message,
			cause:      cause,
		},
	}
}

// NewRetryableErrorWithStatus creates a new RetryableError with status code and message
func NewRetryableErrorWithStatus(statusCode int, message string) *RetryableError {
	return &RetryableError{
		BaseError: BaseError{
			statusCode: statusCode,
			message:    message,
		},
	}
}
