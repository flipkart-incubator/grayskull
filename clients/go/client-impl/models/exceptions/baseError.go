// file: client-impl/models/exceptions/base_error.go
package exceptions

import "fmt"

// BaseError provides common error functionality
type BaseError struct {
	StatusCode int
	Message    string
	Cause      error
}

// Error implements the error interface
func (e *BaseError) Error() string {
	if e.Cause != nil {
		return fmt.Sprintf("%s: %v", e.Message, e.Cause)
	}
	return e.Message
}

// Unwrap returns the underlying error, if any
func (e *BaseError) Unwrap() error {
	return e.Cause
}
