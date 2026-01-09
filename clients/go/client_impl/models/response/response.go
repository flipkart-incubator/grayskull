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
	Data T `json:"data"`

	// Message is a human-readable message describing the response.
	Message string `json:"message"`
}

// Data returns the response data.
func (r *Response[T]) GetData() T {
	return r.Data
}

// Message returns the response message.
func (r *Response[T]) GetMessage() string {
	return r.Message
}

// NewResponse creates a new Response with the given data and message
func NewResponse[T any](data T, message string) *Response[T] {
	return &Response[T]{
		Data:    data,
		Message: message,
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
