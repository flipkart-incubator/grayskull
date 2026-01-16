package exceptions

// RetryableError indicates that an operation failed but can be retried.
// This error is thrown for transient errors such as network timeouts,
// connection failures, or temporary server unavailability (5xx errors).
type RetryableError struct {
	BaseError
}

// NewRetryableError creates a new RetryableError with a message
func NewRetryableError(message string) *RetryableError {
	return &RetryableError{
		BaseError: BaseError{
			StatusCode: 0,
			Message:    message,
		},
	}
}

// NewRetryableErrorWithCause creates a new RetryableError with message and cause
func NewRetryableErrorWithCause(message string, cause error) *RetryableError {
	return &RetryableError{
		BaseError: BaseError{
			StatusCode: 0,
			Message:    message,
			Cause:      cause,
		},
	}
}

// NewRetryableErrorWithStatus creates a new RetryableError with status code and message
func NewRetryableErrorWithStatus(statusCode int, message string) *RetryableError {
	return &RetryableError{
		BaseError: BaseError{
			StatusCode: statusCode,
			Message:    message,
		},
	}
}

// NewRetryableErrorWithStatusAndCause creates a new RetryableError with status code, message, and cause
func NewRetryableErrorWithStatusAndCause(statusCode int, message string, cause error) *RetryableError {
	return &RetryableError{
		BaseError: BaseError{
			StatusCode: statusCode,
			Message:    message,
			Cause:      cause,
		},
	}
}
