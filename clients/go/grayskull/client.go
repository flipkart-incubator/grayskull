package grayskull

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

// Client is the main client for interacting with the Grayskull API
type Client struct {
	baseURL    string
	httpClient *http.Client
	version    string
	authToken  string

	// Services
	Secrets *SecretService
}

// NewClient creates a new Grayskull API client
func NewClient(baseURL, version, authToken string) *Client {
	if !strings.HasSuffix(baseURL, "/") {
		baseURL += "/"
	}

	client := &Client{
		baseURL: baseURL,
		version: version,
		httpClient: &http.Client{
			Timeout: 30 * time.Second,
		},
		authToken: authToken,
	}

	// Initialize services
	client.Secrets = NewSecretService(client)

	return client
}

// SetAuthToken updates the authentication token for all subsequent requests
func (c *Client) SetAuthToken(token string) {
	c.authToken = token
}

// newRequest creates a new HTTP request with proper headers and authentication
func (c *Client) newRequest(ctx context.Context, method, path string, body interface{}) (*http.Request, error) {
	var buf io.Reader
	if body != nil {
		b, err := json.Marshal(body)
		if err != nil {
			return nil, fmt.Errorf("marshal request: %w", err)
		}
		buf = bytes.NewBuffer(b)
	}

	req, err := http.NewRequestWithContext(ctx, method, c.baseURL+strings.TrimPrefix(path, "/"), buf)
	if err != nil {
		return nil, fmt.Errorf("create request: %w", err)
	}

	req.Header.Set("Content-Type", "application/json")
	if c.authToken != "" {
		req.Header.Set("Authorization", "Bearer "+c.authToken)
	}

	return req, nil
}

// do executes an HTTP request and handles the response
func (c *Client) do(req *http.Request, v interface{}) error {
	resp, err := c.httpClient.Do(req)
	if err != nil {
		return fmt.Errorf("execute request: %w", err)
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return fmt.Errorf("read response: %w", err)
	}

	// For empty responses (like 204 No Content)
	if len(body) == 0 && resp.StatusCode == http.StatusNoContent {
		return nil
	}

	// If no target is provided, just return
	if v == nil {
		return nil
	}

	// Unmarshal directly into the target struct
	if err := json.Unmarshal(body, v); err != nil {
		return fmt.Errorf("unmarshal response: %w", err)
	}

	return nil
}
