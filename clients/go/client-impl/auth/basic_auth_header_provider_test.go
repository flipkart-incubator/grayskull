package auth_test

import (
	"encoding/base64"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/auth"
	"testing"
)

func TestNewBasicAuthHeaderProvider(t *testing.T) {
	tests := []struct {
		name        string
		username    string
		password    string
		expectError bool
	}{
		{
			name:        "valid credentials",
			username:    "user",
			password:    "pass",
			expectError: false,
		},
		{
			name:        "empty username",
			username:    "",
			password:    "pass",
			expectError: true,
		},
		{
			name:        "empty password",
			username:    "user",
			password:    "",
			expectError: true,
		},
		{
			name:        "empty username and password",
			username:    "",
			password:    "",
			expectError: true,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			_, err := auth.NewBasicAuthHeaderProvider(tt.username, tt.password)
			if tt.expectError && err == nil {
				t.Error("expected error but got none")
			}
			if !tt.expectError && err != nil {
				t.Errorf("unexpected error: %v", err)
			}
		})
	}
}

func TestGetAuthHeader(t *testing.T) {
	tests := []struct {
		name     string
		username string
		password string
		expected string
	}{
		{
			name:     "basic credentials",
			username: "user",
			password: "pass",
			expected: "Basic " + base64.StdEncoding.EncodeToString([]byte("user:pass")),
		},
		{
			name:     "special characters in credentials",
			username: "user@example.com",
			password: "p@ssw0rd!123",
			expected: "Basic " + base64.StdEncoding.EncodeToString([]byte("user@example.com:p@ssw0rd!123")),
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			provider, err := auth.NewBasicAuthHeaderProvider(tt.username, tt.password)
			if err != nil {
				t.Fatalf("failed to create auth provider: %v", err)
			}

			header, err := provider.GetAuthHeader()
			if err != nil {
				t.Fatalf("GetAuthHeader() error = %v", err)
			}

			if header != tt.expected {
				t.Errorf("GetAuthHeader() = %v, want %v", header, tt.expected)
			}
		})
	}
}

func TestGetAuthHeader_Error(t *testing.T) {
	// This test ensures that GetAuthHeader never returns an error for BasicAuthHeaderProvider
	provider, err := auth.NewBasicAuthHeaderProvider("user", "pass")
	if err != nil {
		t.Fatalf("failed to create auth provider: %v", err)
	}

	header, err := provider.GetAuthHeader()
	if err != nil {
		t.Errorf("GetAuthHeader() returned unexpected error: %v", err)
	}
	if header == "" {
		t.Error("GetAuthHeader() returned empty header")
	}
}
