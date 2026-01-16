package client_impl

import (
	"context"
	"encoding/json"
	"fmt"
	"github.com/grayskull/client-impl/hooks"
	"log/slog"
	"net/url"
	"time"

	"github.com/google/uuid"
	Client_API_Hooks "github.com/grayskull/client-api/hooks"
	Client_API "github.com/grayskull/client-api/models"
	"github.com/grayskull/client-impl/auth"
	"github.com/grayskull/client-impl/constants"
	"github.com/grayskull/client-impl/metrics"
	"github.com/grayskull/client-impl/models"
	"github.com/grayskull/client-impl/models/response"
)

// GrayskullClientImpl implements the Grayskull client interface
type GrayskullClientImpl struct {
	baseURL               string
	authHeaderProvider    auth.GrayskullAuthHeaderProvider
	grayskullClientConfig *models.GrayskullClientConfiguration
	httpClient            *GrayskullHTTPClient
}

// NewGrayskullClient creates a new instance of GrayskullClientImpl
func NewGrayskullClient(authProvider auth.GrayskullAuthHeaderProvider, config *models.GrayskullClientConfiguration) (*GrayskullClientImpl, error) {
	if authProvider == nil {
		return nil, fmt.Errorf("authHeaderProvider cannot be nil")
	}
	if config == nil {
		return nil, fmt.Errorf("grayskullClientConfiguration cannot be nil")
	}

	// Use default logger and no-op metrics recorder
	logger := slog.Default()
	metricsRecorder := metrics.NewPrometheusRecorder()

	httpClient := NewGrayskullHTTPClient(authProvider, config, logger, metricsRecorder)

	return &GrayskullClientImpl{
		baseURL:               config.Host,
		authHeaderProvider:    authProvider,
		grayskullClientConfig: config,
		httpClient:            httpClient,
	}, nil
}

// GetSecret retrieves a secret from the Grayskull server
func (c *GrayskullClientImpl) GetSecret(secretRef string) (*Client_API.SecretValue, error) {
	requestID := uuid.New().String()
	ctx := context.WithValue(context.Background(), constants.GrayskullRequestID, requestID)

	startTime := time.Now()
	var statusCode int

	if secretRef == "" {
		return nil, fmt.Errorf("secretRef cannot be empty")
	}

	// Parse secretRef format: "projectId:secretName"
	parts := splitSecretRef(secretRef)
	if len(parts) != 2 {
		return nil, fmt.Errorf("invalid secretRef format. Expected 'projectId:secretName', got: %s", secretRef)
	}

	projectID, secretName := parts[0], parts[1]
	if projectID == "" || secretName == "" {
		return nil, fmt.Errorf("projectId and secretName cannot be empty in secretRef: %s", secretRef)
	}

	// Add context values
	ctx = context.WithValue(ctx, constants.ProjectID, projectID)
	ctx = context.WithValue(ctx, constants.SecretName, secretName)

	// URL encode the path parameters
	encodedProjectID := url.PathEscape(projectID)
	encodedSecretName := url.PathEscape(secretName)
	url := fmt.Sprintf("%s/v1/projects/%s/secrets/%s/data", c.baseURL, encodedProjectID, encodedSecretName)

	// Fetch the secret with automatic retry logic
	httpResponse, err := c.httpClient.DoGetWithRetry(ctx, url)
	if err != nil {
		statusCode = 500 // Default status code for errors
		if httpResponse != nil {
			statusCode = httpResponse.StatusCode
		}
		return nil, fmt.Errorf("failed to fetch secret: %w", err)
	}

	statusCode = httpResponse.StatusCode

	var secretResp response.Response[Client_API.SecretValue]
	if err := json.Unmarshal([]byte(httpResponse.Body), &secretResp); err != nil {
		return nil, fmt.Errorf("failed to parse response: %w", err)
	}

	// Record metrics
	duration := time.Since(startTime)
	c.httpClient.metricsRecorder.RecordRequest("get_secret", statusCode, duration)

	// Return a pointer to the Data field
	return &secretResp.Data, nil
}

// RegisterRefreshHook registers a refresh hook for a secret
func (c *GrayskullClientImpl) RegisterRefreshHook(secretRef string, hook Client_API_Hooks.SecretRefreshHook) (Client_API_Hooks.RefreshHandlerRef, error) {
	if secretRef == "" {
		return nil, fmt.Errorf("secretRef cannot be empty")
	}
	if hook == nil {
		return nil, fmt.Errorf("hook cannot be nil")
	}

	// TODO: Implement actual hook invocation when server-side events support is added
	// Return the singleton instance of the no-op implementation
	return hooks.Instance, nil
}

// Close releases resources used by the client
func (c *GrayskullClientImpl) Close() error {
	if c.httpClient != nil {
		return c.httpClient.Close()
	}
	return nil
}

// splitSecretRef splits the secret reference into project ID and secret name
func splitSecretRef(secretRef string) []string {
	// Split on first occurrence of ':'
	for i := 0; i < len(secretRef); i++ {
		if secretRef[i] == ':' {
			return []string{secretRef[:i], secretRef[i+1:]}
		}
	}
	return []string{secretRef} // Return original if no ':' found
}
