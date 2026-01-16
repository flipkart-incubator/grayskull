package client_impl

import (
	"context"
	"encoding/json"
	"fmt"
	"github.com/flipkart-incubator/grayskull/client-impl/hooks"
	"log/slog"
	"net/url"
	"strings"
	"time"

	Client_API_Hooks "github.com/flipkart-incubator/grayskull/client-api/hooks"
	Client_API "github.com/flipkart-incubator/grayskull/client-api/models"
	"github.com/flipkart-incubator/grayskull/client-impl/auth"
	"github.com/flipkart-incubator/grayskull/client-impl/constants"
	"github.com/flipkart-incubator/grayskull/client-impl/metrics"
	"github.com/flipkart-incubator/grayskull/client-impl/models"
	"github.com/flipkart-incubator/grayskull/client-impl/models/exceptions"
	"github.com/flipkart-incubator/grayskull/client-impl/models/response"
	"github.com/google/uuid"
)

// GrayskullClientImpl implements the Grayskull client interface
type GrayskullClientImpl struct {
	BaseURL               string
	AuthHeaderProvider    auth.GrayskullAuthHeaderProvider
	GrayskullClientConfig *models.GrayskullClientConfiguration
	HttpClient            GrayskullHTTPClientInterface
	MetricsRecorder       metrics.MetricsRecorder
}

// NewGrayskullClient creates a new instance of GrayskullClientImpl
func NewGrayskullClient(authProvider auth.GrayskullAuthHeaderProvider, config *models.GrayskullClientConfiguration) (*GrayskullClientImpl, error) {
	if authProvider == nil {
		return nil, exceptions.NewGrayskullErrorWithMessage("authHeaderProvider cannot be nil")
	}
	if config == nil {
		return nil, exceptions.NewGrayskullErrorWithMessage("grayskullClientConfiguration cannot be nil")
	}

	// Use default logger and no-op metrics recorder
	logger := slog.Default()
	metricsRecorder := metrics.NewPrometheusRecorder()

	httpClient := NewGrayskullHTTPClient(authProvider, config, logger, metricsRecorder)

	return &GrayskullClientImpl{
		BaseURL:               config.Host,
		AuthHeaderProvider:    authProvider,
		GrayskullClientConfig: config,
		HttpClient:            httpClient,
		MetricsRecorder:       metricsRecorder,
	}, nil
}

// SplitSecretRef splits the secret reference into project ID and secret name
// It splits only on the first colon to handle secret names that may contain colons
func (c *GrayskullClientImpl) SplitSecretRef(secretRef string) []string {
	return strings.SplitN(secretRef, ":", 2)
}

// GetSecret retrieves a secret from the Grayskull server
func (g *GrayskullClientImpl) GetSecret(secretRef string) (*Client_API.SecretValue, error) {
	requestID := uuid.New().String()
	ctx := context.WithValue(context.Background(), constants.GrayskullRequestID, requestID)

	startTime := time.Now()
	var statusCode int

	if secretRef == "" {
		return nil, fmt.Errorf("secretRef cannot be empty")
	}

	// Parse secretRef format: "projectId:secretName"
	parts := g.SplitSecretRef(secretRef)
	if len(parts) != 2 {
		return nil, exceptions.NewGrayskullError(400, fmt.Sprintf("invalid secretRef format. Expected 'projectId:secretName', got: %s", secretRef))
	}

	projectID, secretName := parts[0], parts[1]
	if projectID == "" || secretName == "" {
		return nil, exceptions.NewGrayskullError(400, fmt.Sprintf("projectId and secretName cannot be empty in secretRef: %s", secretRef))
	}

	// Add context values
	ctx = context.WithValue(ctx, constants.ProjectID, projectID)
	ctx = context.WithValue(ctx, constants.SecretName, secretName)

	// URL encode the path parameters
	encodedProjectID := url.PathEscape(projectID)
	encodedSecretName := url.PathEscape(secretName)
	url := fmt.Sprintf("%s/v1/projects/%s/secrets/%s/data", g.BaseURL, encodedProjectID, encodedSecretName)

	// Fetch the secret with automatic retry logic
	httpResponse, err := g.HttpClient.DoGetWithRetry(ctx, url)
	if err != nil {
		if httpResponse != nil {
			statusCode = httpResponse.StatusCode
		}
		return nil, exceptions.NewGrayskullErrorWithCause(statusCode, "failed to fetch secret", err)
	}

	statusCode = httpResponse.StatusCode

	var secretResp response.Response[Client_API.SecretValue]
	if err := json.Unmarshal([]byte(httpResponse.Body), &secretResp); err != nil {
		return nil, exceptions.NewGrayskullErrorWithCause(500, "failed to parse response", err)
	}

	// After unmarshaling the response
	if secretResp.Data == (Client_API.SecretValue{}) {
		return nil, exceptions.NewGrayskullError(500, "no data in response")
	}

	// Record metrics
	duration := time.Since(startTime)
	g.MetricsRecorder.RecordRequest("get_secret", statusCode, duration)

	// Return a pointer to the Data field
	return &secretResp.Data, nil
}

// RegisterRefreshHook registers a refresh hook for a secret
func (c *GrayskullClientImpl) RegisterRefreshHook(secretRef string, hook Client_API_Hooks.SecretRefreshHook) (Client_API_Hooks.RefreshHandlerRef, error) {
	if secretRef == "" {
		return nil, fmt.Errorf("secretRef cannot be empty")
	}
	if hook == nil {
		return nil, exceptions.NewGrayskullError(400, "hook cannot be nil")
	}

	// TODO: Implement actual hook invocation when server-side events support is added
	// Return the singleton instance of the no-op implementation
	return hooks.Instance, nil
}

// Close releases resources used by the client
func (c *GrayskullClientImpl) Close() error {
	if c.HttpClient != nil {
		return c.HttpClient.Close()
	}
	return nil
}
