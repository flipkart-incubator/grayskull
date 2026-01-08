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

// GetSecretURL returns the URL path for getting a specific secret.
// It validates that neither projectID nor secretName is empty to prevent malformed URLs.
// Returns an error if either parameter is empty.
func (s *SecretService) GetSecretURL(projectID, secretName string) (string, error) {
	if projectID == "" {
		return "", fmt.Errorf("projectID cannot be empty")
	}
	if secretName == "" {
		return "", fmt.Errorf("secretName cannot be empty")
	}
	return fmt.Sprintf("projects/%s/secrets/%s", projectID, secretName), nil
}

// GetSecret retrieves the latest version of a specific secret
func (s *SecretService) GetSecret(ctx context.Context, projectID, secretName string) (*ListSecretsResponse, error) {
	path, err := s.GetSecretURL(projectID, secretName)
	if err != nil {
		return nil, fmt.Errorf("invalid secret reference: %w", err)
	}

	req, err := s.client.newRequest(ctx, http.MethodGet, path, nil)
	if err != nil {
		return nil, fmt.Errorf("create request: %w", err)
	}

	var secret ListSecretsResponse
	if err := s.client.do(req, &secret); err != nil {
		return nil, fmt.Errorf("get secret: %w", err)
	}

	return &secret, nil
}
