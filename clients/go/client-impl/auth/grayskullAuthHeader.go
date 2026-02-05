package auth

// GrayskullAuthHeaderProvider handles authentication for Grayskull client requests.
// Implementations manage auth logic, header generation, and method selection.
type GrayskullAuthHeaderProvider interface {
	// GetAuthHeader returns the authentication header value to be included in requests.
	//
	// Note: Implementations should be thread-safe as this method may be called
	// concurrently by multiple goroutines.
	//
	// Returns:
	//   - The authentication header value (e.g., "Basic dXNlcm5hbWU6cGFzc3dvcmQ=")
	//   - An error if authentication token generation fails
	GetAuthHeader() (string, error)
}
