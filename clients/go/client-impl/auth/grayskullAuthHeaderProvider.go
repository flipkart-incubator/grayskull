package auth

// GrayskullAuthHeaderProvider is an interface for providing authentication headers to the Grayskull client.
// If there are multiple authentication providers, Grayskull will pick the primary one.
type GrayskullAuthHeaderProvider interface {
	// GetAuthHeader returns the authentication header value to be included in requests.
	//
	// Important: Implementations should be thread-safe as this method
	// may be called concurrently by multiple threads.
	//
	// Returns:
	// - The authentication header value (e.g., "Basic dXNlcm5hbWU6cGFzc3dvcmQ=")
	// - An error if authentication token generation fails
	GetAuthHeader() (string, error)
}
