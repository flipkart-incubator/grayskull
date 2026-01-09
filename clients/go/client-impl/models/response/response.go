package response

import "encoding/json"

// Response represents the standard response format from the Grayskull API.
// It is a generic type that can hold any type of data in its Data field.
//
// This struct is used for deserializing the server's ResponseTemplate.
// It is immutable and thread-safe.
type Response[T any] struct {
	// Data contains the actual response data.
	Data T `json:"data"`

	// Message is a human-readable message describing the response.
	Message string `json:"message,omitempty"`
}

// NewResponse creates a new Response with the given data and message.
func NewResponse[T any](data T, message string) *Response[T] {
	return &Response[T]{
		Data:    data,
		Message: message,
	}
}

// UnmarshalResponse parses the JSON-encoded data and stores the result in the value
// pointed to by v, which must be a pointer to a Response.
func UnmarshalResponse[T any](data []byte, v *Response[T]) error {
	return json.Unmarshal(data, v)
}

// Marshal returns the JSON encoding of the Response.
func (r *Response[T]) Marshal() ([]byte, error) {
	return json.Marshal(r)
}
