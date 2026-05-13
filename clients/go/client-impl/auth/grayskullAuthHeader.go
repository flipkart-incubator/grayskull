package auth

// GrayskullAuthHeaderProvider handles authentication for Grayskull client requests.
// Implementations manage auth logic, header generation, and method selection.
type GrayskullAuthHeaderProvider interface {
	// GetAuthHeader returns the authentication header value to be included in requests.
	GetAuthHeader() (string, error)
}
