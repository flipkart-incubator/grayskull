package response

// Response is a generic struct for deserializing the server's ResponseTemplate.
// This represents the standard response format from the Grayskull API.
type Response[T any] struct {
	// Data contains the actual response data.
	Data T `json:"data"`

	// Message is a human-readable message describing the response.
	Message string `json:"message"`
}
