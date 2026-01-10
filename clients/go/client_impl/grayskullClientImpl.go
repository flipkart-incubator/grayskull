package client_impl

import (
	"context"
	"errors"
	"fmt"
	"github.com/grayskull/client"
	"github.com/grayskull/client/hooks"
	"github.com/grayskull/client_impl/auth"
	"github.com/grayskull/client_impl/metrics"
	"log/slog"
	"net/url"
	"os"
	"strings"
	"time"

	"github.com/google/uuid"

	CLientModels "github.com/grayskull/client/models"
	"github.com/grayskull/client_impl/constants"
	ClientHooks "github.com/grayskull/client_impl/hooks"
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
	metrics             *metrics.Metrics
	metricsServer       *metrics.Server
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

	// Initialize metrics
	metricsInstance := metrics.NewMetrics("grayskull_client")

	// Start metrics server if enabled
	var metricsServer *metrics.Server
	if clientConfig.Metrics.Enabled && clientConfig.Metrics.Server != nil {
		metricsServer = metrics.NewServer(clientConfig.Metrics.Server.Address)
		if err := metricsServer.Start(); err != nil {
			logger.Error("Failed to start metrics server", "error", err)
		} else {
			logger.Info("Metrics server started", "address", metricsServer.Addr())
		}
	}

	// Ensure base URL ends with a slash
	baseURL := clientConfig.Host
	if !strings.HasSuffix(baseURL, "/") {
		baseURL += "/"
	}

	// Initialize HTTP client
	httpClient, err := NewGrayskullHTTPClient(authHeaderProvider, clientConfig, nil)
	if err != nil {
		return nil, fmt.Errorf("failed to create HTTP client: %w", err)
	}

	client := &GrayskullClientImpl{
		baseURL:             baseURL,
		authHeaderProvider:  authHeaderProvider,
		clientConfiguration: clientConfig,
		httpClient:          httpClient,
		logger:              logger,
		metrics:             metricsInstance,
		metricsServer:       metricsServer,
	}

	return client, nil
}

// GetSecret retrieves a secret from the Grayskull server.
// The secretRef should be in the format: "projectId:secretName"
// For example: "my-project:database-password"
func (c *GrayskullClientImpl) GetSecret(ctx context.Context, secretRef string) (secretValue *CLientModels.SecretValue, operationErr error) {
	startTime := time.Now()
	requestID := uuid.NewString()

	// Add request ID to context before starting the operation
	ctx = context.WithValue(ctx, constants.GrayskullRequestID, requestID)

	// Defer function to record metrics
	defer func() {
		duration := time.Since(startTime).Seconds()
		status := "success"
		if operationErr != nil {
			status = "error"
		} else if ctx.Err() != nil {
			status = "canceled"
		}
		c.metrics.ObserveSecretOperationDuration("get_secret", status, duration)
		c.metrics.IncSecretOperationTotal("get_secret", status)
	}()

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

// RegisterRefreshHook registers a refresh hook for a secret.
// Note: This is a placeholder implementation. The hook will be registered but
// will not be invoked until server-side long-polling support is implemented.
func (c *GrayskullClientImpl) RegisterRefreshHook(ctx context.Context, secretRef string, hook hooks.SecretRefreshHook) (hooks.RefreshHandlerRef, error) {
	startTime := time.Now()
	status := "success"

	// Defer function to record metrics
	defer func() {
		duration := time.Since(startTime).Seconds()
		c.metrics.ObserveSecretOperationDuration("register_refresh_hook", status, duration)
		c.metrics.IncSecretOperationTotal("register_refresh_hook", status)
	}()

	// Validate inputs
	if secretRef == "" {
		status = "error"
		return nil, fmt.Errorf("secretRef cannot be empty")
	}
	if hook == nil {
		status = "error"
		return nil, fmt.Errorf("hook cannot be nil")
	}

	// Create a new no-op refresh handler ref
	handlerRef := &ClientHooks.NoOpRefreshHandlerRef{}

	// Log that a refresh hook was registered (for debugging)
	c.logger.DebugContext(ctx, "refresh hook registered",
		"secretRef", secretRef,
	)

	return handlerRef, nil
}

// Close releases resources used by the Grayskull client.
// This method should be called when the client is no longer needed to properly
// clean up HTTP connections and other resources.
func (c *GrayskullClientImpl) Close() error {
	var errs []error

	// Close the metrics server if it was started
	if c.metricsServer != nil {
		c.logger.Info("shutting down metrics server")
		if err := c.metricsServer.Stop(context.Background()); err != nil {
			errMsg := fmt.Sprintf("error stopping metrics server: %v", err)
			errs = append(errs, errors.New(errMsg))
			c.logger.Error("error stopping metrics server", "error", err)
		} else {
			c.logger.Info("metrics server stopped successfully")
		}
	}

	// Close the HTTP client
	if c.httpClient != nil {
		c.logger.Debug("closing HTTP client")
		if err := c.httpClient.Close(); err != nil {
			errMsg := fmt.Sprintf("error closing HTTP client: %v", err)
			errs = append(errs, errors.New(errMsg))
			c.logger.Error("error closing HTTP client", "error", err)
		} else {
			c.logger.Debug("HTTP client closed successfully")
		}
	}

	// Log final metrics summary if metrics were enabled
	if c.metrics != nil && c.clientConfiguration.Metrics.Enabled && c.clientConfiguration.Metrics.Server != nil {
		c.logger.Info("metrics collection completed",
			"metrics_endpoint", fmt.Sprintf("http://%s/metrics", c.clientConfiguration.Metrics.Server.Address),
		)
	}

	// Return combined errors if any occurred
	if len(errs) > 0 {
		var errMsg string
		for _, e := range errs {
			errMsg += e.Error() + "; "
		}
		return fmt.Errorf("errors during client shutdown: %s", errMsg)
	}

	return nil
}
