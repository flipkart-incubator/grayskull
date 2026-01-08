package grayskull

import (
	"context"
	"fmt"
	"net/http"
	"strconv"
)

type SecretService struct {
	client *Client
}

// NewSecretService creates a new SecretService
func NewSecretService(client *Client) *SecretService {
	return &SecretService{client: client}
}

// GetListSecretsURL returns the URL for listing secrets
func (s *SecretService) GetListSecretsURL(projectID string) string {
	return fmt.Sprintf("/%s/projects/%s/secrets", s.client.version, projectID)
}

// GetSecrets lists all secrets for a project with pagination
func (s *SecretService) GetSecrets(ctx context.Context, projectID string, opts *ListSecretsRequest) (*ListSecretsResponse, error) {
	path := s.GetListSecretsURL(projectID)
	req, err := s.client.newRequest(ctx, http.MethodGet, path, nil)
	if err != nil {
		return nil, fmt.Errorf("create request: %w", err)
	}

	// Add query parameters if provided
	if opts != nil {
		q := req.URL.Query()
		if opts.Offset > 0 {
			q.Add("offset", strconv.Itoa(opts.Offset))
		}
		if opts.Limit > 0 {
			q.Add("limit", strconv.Itoa(opts.Limit))
		}
		req.URL.RawQuery = q.Encode()
	}

	var response ListSecretsResponse
	if err := s.client.do(req, &response); err != nil {
		return nil, fmt.Errorf("API request failed: %w", err)
	}

	return &response, nil
}
