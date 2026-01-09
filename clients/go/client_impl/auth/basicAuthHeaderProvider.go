package auth

import (
	"encoding/base64"
	"errors"
	"fmt"
)

// BasicAuthHeaderProvider implements GrayskullAuthHeaderProvider for HTTP Basic Authentication
// as defined in RFC 7617.
type BasicAuthHeaderProvider struct {
	username string
	password string
}

// NewBasicAuthHeaderProvider creates a new BasicAuthHeaderProvider with the given credentials.
// Returns an error if either username or password is empty.
func NewBasicAuthHeaderProvider(username, password string) (*BasicAuthHeaderProvider, error) {
	if username == "" || password == "" {
		return nil, errors.New("username and password cannot be empty")
	}
	return &BasicAuthHeaderProvider{
		username: username,
		password: password,
	}, nil
}

// GetAuthHeader returns the Basic Authentication header value.
// Implements the GrayskullAuthHeaderProvider interface.
func (b *BasicAuthHeaderProvider) GetAuthHeader() (string, error) {
	credentials := fmt.Sprintf("%s:%s", b.username, b.password)
	encoded := base64.StdEncoding.EncodeToString([]byte(credentials))
	return "Basic " + encoded, nil
}