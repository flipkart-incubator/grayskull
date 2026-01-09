package exceptions

// GrayskullError represents an error that occurs in the Grayskull client.
// It includes a status code and implements the error interface.
type GrayskullError struct {
	BaseError
}

// NewGrayskullError creates a new GrayskullError with status code and message
func NewGrayskullError(statusCode int, message string) *GrayskullError {
	return &GrayskullError{
		BaseError: BaseError{
			StatusCode: statusCode,
			Message:    message,
		},
	}
}

// NewGrayskullErrorWithCause creates a new GrayskullError with status code, message, and cause
func NewGrayskullErrorWithCause(statusCode int, message string, cause error) *GrayskullError {
	return &GrayskullError{
		BaseError: BaseError{
			StatusCode: statusCode,
			Message:    message,
			Cause:      cause,
		},
	}
}

// NewGrayskullErrorFromException creates a new GrayskullError from an exception with status code
func NewGrayskullErrorFromException(statusCode int, cause error) *GrayskullError {
	return &GrayskullError{
		BaseError: BaseError{
			StatusCode: statusCode,
			Message:    cause.Error(),
			Cause:      cause,
		},
	}
}

// NewGrayskullErrorWithMessage creates a new GrayskullError with just a message (status code 0)
func NewGrayskullErrorWithMessage(message string) *GrayskullError {
	return &GrayskullError{
		BaseError: BaseError{
			StatusCode: 0,
			Message:    message,
		},
	}
}

// NewGrayskullErrorWithMessageAndCause creates a new GrayskullError with message and cause (status code 0)
func NewGrayskullErrorWithMessageAndCause(message string, cause error) *GrayskullError {
	return &GrayskullError{
		BaseError: BaseError{
			StatusCode: 0,
			Message:    message,
			Cause:      cause,
		},
	}
}
