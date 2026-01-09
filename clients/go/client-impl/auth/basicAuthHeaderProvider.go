package auth

import (
	"encoding/base64"
	"fmt"
	"strings"
)

// BasicAuthHeaderProvider implements GrayskullAuthHeaderProvider for HTTP Basic Authentication.
//
// This implementation provides HTTP Basic Authentication by encoding the username
// and password in Base64 format according to RFC 7617.
type BasicAuthHeaderProvider struct {
	username string
	password string
}

// NewBasicAuthHeaderProvider creates a new instance of BasicAuthHeaderProvider.
// Returns an error if username or password is empty.
func NewBasicAuthHeaderProvider(username, password string) (*BasicAuthHeaderProvider, error) {
	username = strings.TrimSpace(username)
	if username == "" || password == "" {
		return nil, fmt.Errorf("username and password cannot be empty")
	}
	return &BasicAuthHeaderProvider{
		username: username,
		password: password,
	}, nil
}

// GetAuthHeader returns the Basic Authentication header value.
func (b *BasicAuthHeaderProvider) GetAuthHeader() (string, error) {
	credentials := fmt.Sprintf("%s:%s", b.username, b.password)
	encoded := base64.StdEncoding.EncodeToString([]byte(credentials))
	return "Basic " + encoded, nil
}
