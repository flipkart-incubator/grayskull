package grayskull

import (
	"context"
	"fmt"
	"net/http"
)

type SecretService struct {
	client *Client
}

// NewSecretService creates a new SecretService
func NewSecretService(client *Client) *SecretService {
	return &SecretService{client: client}
}

// GetSecretURL returns the URL path for getting a specific secret
func (s *SecretService) GetSecretURL(projectID, secretName string) string {
	return fmt.Sprintf("projects/%s/secrets/%s", projectID, secretName)
}

// GetSecret retrieves the latest version of a specific secret
func (s *SecretService) GetSecret(ctx context.Context, projectID, secretName string) (*Secret, error) {
	if projectID == "" {
		return nil, fmt.Errorf("projectID is required")
	}
	if secretName == "" {
		return nil, fmt.Errorf("secretName is required")
	}

	path := s.GetSecretURL(projectID, secretName)
	req, err := s.client.newRequest(ctx, http.MethodGet, path, nil)
	if err != nil {
		return nil, fmt.Errorf("create request: %w", err)
	}

	var secret Secret
	if err := s.client.do(req, &secret); err != nil {
		return nil, fmt.Errorf("Get Secret: %w", err)
	}

	return &secret, nil
}
