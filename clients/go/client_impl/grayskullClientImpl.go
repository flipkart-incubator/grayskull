package client_impl

import (
	"context"
	"errors"
	"fmt"
	"github.com/grayskull/client"
	"github.com/grayskull/client_impl/auth"
	"log/slog"
	"net/url"
	"os"
	"strings"
	"time"

	"github.com/google/uuid"

	CLientModels "github.com/grayskull/client/models"
	"github.com/grayskull/client_impl/constants"
	"github.com/grayskull/client_impl/models"
	"github.com/grayskull/client_impl/models/response"
)

// GrayskullClientImpl implements the Grayskull client interface
type GrayskullClientImpl struct {
	baseURL             string
	authHeaderProvider  auth.GrayskullAuthHeaderProvider
	clientConfiguration *models.GrayskullClientConfiguration
	httpClient          *GrayskullHTTPClient
	logger              *slog.Logger
}

// NewGrayskullClient creates a new instance of a Grayskull client
func NewGrayskullClient(authHeaderProvider auth.GrayskullAuthHeaderProvider, clientConfig *models.GrayskullClientConfiguration) (client.Client, error) {
	if authHeaderProvider == nil {
		return nil, errors.New("authHeaderProvider cannot be nil")
	}

	if clientConfig == nil {
		return nil, errors.New("clientConfig cannot be nil")
	}

	// Initialize logger with default JSON handler
	logger := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{
		Level: slog.LevelInfo,
	}))

	// Ensure base URL ends with a slash
	baseURL := clientConfig.Host
	if !strings.HasSuffix(baseURL, "/") {
		baseURL += "/"
	}

	client := &GrayskullClientImpl{
		baseURL:             baseURL,
		authHeaderProvider:  authHeaderProvider,
		clientConfiguration: clientConfig,
		logger:              logger,
	}

	// Initialize HTTP client
	httpClient, err := NewGrayskullHTTPClient(authHeaderProvider, clientConfig, nil)
	if err != nil {
		return nil, fmt.Errorf("failed to create HTTP client: %w", err)
	}

	client.httpClient = httpClient

	// Configure metrics
	if clientConfig.MetricsEnabled {
		logger.Info("metrics collection is enabled")
	}

	return client, nil
}

// GetSecret retrieves a secret from the Grayskull server.
// The secretRef should be in the format: "projectId:secretName"
// For example: "my-project:database-password"
func (c *GrayskullClientImpl) GetSecret(ctx context.Context, secretRef string) (*CLientModels.SecretValue, error) {
	requestID := uuid.New().String()
	// Use the provided context but add our request ID
	if ctx == nil {
		ctx = context.Background()
	}
	ctx = context.WithValue(ctx, constants.GrayskullRequestID, requestID)
	startTime := time.Now()

	// Set up logger with context
	logger := c.logger.With(
		constants.GrayskullRequestID, requestID,
		constants.SecretName, secretRef,
	)

	defer func() {
		duration := time.Since(startTime)
		logger.Info("getSecret completed",
			"duration_ms", duration.Milliseconds(),
		)
	}()

	if secretRef == "" {
		return nil, errors.New("secretRef cannot be empty")
	}

	// Parse secretRef format: "projectId:secretName"
	parts := strings.SplitN(secretRef, ":", 2)
	if len(parts) != 2 {
		return nil, fmt.Errorf("invalid secretRef format. Expected 'projectId:secretName', got: %s", secretRef)
	}

	projectID := parts[0]
	secretName := parts[1]

	if projectID == "" || secretName == "" {
		return nil, fmt.Errorf("projectId and secretName cannot be empty in secretRef: %s", secretRef)
	}

	// Add project and secret to logger context
	logger = logger.With(
		constants.ProjectID, projectID,
		constants.SecretName, secretName,
	)

	logger.Debug("fetching secret")

	// URL encode the path parameters
	encodedProjectID := url.PathEscape(projectID)
	encodedSecretName := url.PathEscape(secretName)
	requestURL := fmt.Sprintf("%sv1/projects/%s/secrets/%s/data", c.baseURL, encodedProjectID, encodedSecretName)

	// Fetch the secret with automatic retry logic
	httpResponse, err := c.httpClient.DoGetWithRetry(ctx, requestURL)
	if err != nil {
		logger.Error("failed to fetch secret", "error", err)
		return nil, fmt.Errorf("failed to fetch secret: %w", err)
	}

	secretResponse, err := response.UnmarshalResponse[CLientModels.SecretValue]([]byte(httpResponse.Body))
	if err != nil {
		logger.Error("failed to parse secret response", "error", err)
		return nil, fmt.Errorf("failed to parse secret response: %w", err)
	}

	if secretResponse == nil {
		return nil, errors.New("empty response from server")
	}

	// Get the actual secret data from the response
	secretData := secretResponse.Data

	// Check if the SecretValue is the zero value
	if secretData == (CLientModels.SecretValue{}) {
		return nil, errors.New("empty secret data in response")
	}

	return &secretData, nil
}

// Close releases resources used by the Grayskull client.
// This method should be called when the client is no longer needed to properly
// clean up HTTP connections and other resources.
func (c *GrayskullClientImpl) Close() error {
	c.logger.Info("closing Grayskull client")

	if c.httpClient != nil {
		if err := c.httpClient.Close(); err != nil {
			c.logger.Error("error closing HTTP client", "error", err)
			return fmt.Errorf("error closing HTTP client: %w", err)
		}
	}

	// No need to sync with slog as it handles its own buffering

	return nil
}
