package exceptions

import "fmt"

// GrayskullError represents an error that occurred in the Grayskull client.
// It implements the error interface and includes an optional status code.
type GrayskullError struct {
	// StatusCode represents the HTTP status code associated with the error.
	// A value of 0 indicates no status code was provided.
	StatusCode int
	// Message contains the error message.
	Message string
	// Cause contains the underlying error that triggered this one, if any.
	Cause error
}

// Error implements the error interface.
func (e *GrayskullError) Error() string {
	if e.Cause != nil {
		return fmt.Sprintf("%s: %v", e.Message, e.Cause)
	}
	return e.Message
}

// Unwrap returns the underlying error, making it compatible with errors.Is and errors.As.
func (e *GrayskullError) Unwrap() error {
	return e.Cause
}

// NewGrayskullError creates a new GrayskullError with the given status code and message.
func NewGrayskullError(statusCode int, message string) *GrayskullError {
	return &GrayskullError{
		StatusCode: statusCode,
		Message:    message,
	}
}

// NewGrayskullErrorWithCause creates a new GrayskullError with status code, message, and cause.
func NewGrayskullErrorWithCause(statusCode int, message string, cause error) *GrayskullError {
	return &GrayskullError{
		StatusCode: statusCode,
		Message:    message,
		Cause:      cause,
	}
}

// NewGrayskullErrorFromException creates a new GrayskullError from a status code and an existing error.
// The error message will be the error's message without duplicating it in the cause.
func NewGrayskullErrorFromException(statusCode int, err error) *GrayskullError {
	return &GrayskullError{
		StatusCode: statusCode,
		Message:    err.Error(),
	}
}

// NewGrayskullErrorWithMessage creates a new GrayskullError with just a message (status code 0).
func NewGrayskullErrorWithMessage(message string) *GrayskullError {
	return &GrayskullError{
		Message: message,
	}
}

// NewGrayskullErrorWithMessageAndCause creates a new GrayskullError with a message and cause (status code 0).
func NewGrayskullErrorWithMessageAndCause(message string, cause error) *GrayskullError {
	return &GrayskullError{
		Message: message,
		Cause:   cause,
	}
}
