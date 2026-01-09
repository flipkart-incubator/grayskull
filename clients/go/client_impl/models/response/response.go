package response

import "encoding/json"

// Response is a generic struct for deserializing the server's ResponseTemplate.
// This represents the standard response format from the Grayskull API.
//
// This struct is designed to be immutable and thread-safe.
//
// T is the type of the data field
type Response[T any] struct {
	// Data contains the actual response data.
	data T `json:"data"`

	// Message is a human-readable message describing the response.
	message string `json:"message"`
}

// GetData returns the response data.
func (r *Response[T]) GetData() T {
	return r.data
}

// GetMessage returns the response message.
func (r *Response[T]) GetMessage() string {
	return r.message
}

// NewResponse creates a new Response with the given data and message
func NewResponse[T any](data T, message string) *Response[T] {
	return &Response[T]{
		data:    data,
		message: message,
	}
}

// UnmarshalResponse unmarshals JSON data into a Response of the specified type
func UnmarshalResponse[T any](data []byte) (*Response[T], error) {
	var resp Response[T]
	if err := json.Unmarshal(data, &resp); err != nil {
		return nil, err
	}
	return &resp, nil
}
