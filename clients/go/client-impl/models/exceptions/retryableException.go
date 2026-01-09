package exceptions

import "fmt"

// RetryableError represents an error that indicates an operation can be retried.
// It's used for transient errors like network timeouts, connection failures,
// or temporary server unavailability (5xx errors).
type RetryableError struct {
	message    string
	statusCode int
	cause      error
}

// Error returns the error message, implementing the error interface.
func (e *RetryableError) Error() string {
	if e.cause != nil {
		return fmt.Sprintf("%s: %v", e.message, e.cause)
	}
	return e.message
}

// Unwrap returns the underlying error, if any.
func (e *RetryableError) Unwrap() error {
	return e.cause
}

// StatusCode returns the HTTP status code associated with the error, if any.
func (e *RetryableError) StatusCode() int {
	return e.statusCode
}

// NewRetryableError creates a new RetryableError with the given message.
func NewRetryableError(message string) *RetryableError {
	return &RetryableError{
		message:    message,
		statusCode: 0,
	}
}

// NewRetryableErrorWithCause creates a new RetryableError with a message and underlying cause.
func NewRetryableErrorWithCause(message string, cause error) *RetryableError {
	return &RetryableError{
		message:    message,
		statusCode: 0,
		cause:      cause,
	}
}

// NewRetryableErrorWithStatus creates a new RetryableError with a message and status code.
func NewRetryableErrorWithStatus(statusCode int, message string) *RetryableError {
	return &RetryableError{
		message:    message,
		statusCode: statusCode,
	}
}

// NewRetryableErrorWithStatusAndCause creates a new RetryableError with a message, status code, and underlying cause.
func NewRetryableErrorWithStatusAndCause(statusCode int, message string, cause error) *RetryableError {
	return &RetryableError{
		message:    message,
		statusCode: statusCode,
		cause:      cause,
	}
}
