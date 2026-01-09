package client

import (
	"context"
	"encoding/json"
	"fmt"
	"github.com/grayskull/go-client/client-impl/hooks"
	"log/slog"
	"net/url"
	"reflect"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/grayskull/go-client/client-impl/auth"
	"github.com/grayskull/go-client/client-impl/constants"
	"github.com/grayskull/go-client/client-impl/models"
	"github.com/grayskull/go-client/client-impl/models/exceptions"
	"github.com/grayskull/go-client/client-impl/models/response"
	ClientHooks "github.com/grayskull/go-client/client/hooks"
	ClientModel "github.com/grayskull/go-client/client/models"
)

// GrayskullClientImpl implements the GrayskullClient interface
type GrayskullClientImpl struct {
	baseURL            string
	authHeaderProvider auth.GrayskullAuthHeaderProvider
	config             *models.GrayskullClientConfiguration
	httpClient         *GrayskullHttpClient
	logger             *slog.Logger
	secretValueType    *response.Response[ClientModel.SecretValue]
}

// NewGrayskullClient creates a new instance of GrayskullClient
func NewGrayskullClient(
	authProvider auth.GrayskullAuthHeaderProvider,
	config *models.GrayskullClientConfiguration,
	logger *slog.Logger,
) (*GrayskullClientImpl, error) {
	if authProvider == nil {
		return nil, exceptions.NewGrayskullError(400, "authProvider cannot be nil")
	}
	if config == nil {
		return nil, exceptions.NewGrayskullError(400, "config cannot be nil")
	}

	if logger == nil {
		logger = slog.Default()
	}

	httpClient := NewGrayskullHttpClient(authProvider, config, logger)

	return &GrayskullClientImpl{
		baseURL:            config.Host(),
		authHeaderProvider: authProvider,
		config:             config,
		httpClient:         httpClient,
		logger:             logger,
		secretValueType:    &response.Response[ClientModel.SecretValue]{},
	}, nil
}

// GetSecret retrieves a secret from the Grayskull server
func (c *GrayskullClientImpl) GetSecret(ctx context.Context, secretRef string) (secretValue ClientModel.SecretValue, err error) {
	startTime := time.Now()
	requestID := generateRequestID()
	ctx = context.WithValue(ctx, constants.GrayskullRequestID, requestID)

	var statusCode int
	defer func() {
		duration := time.Since(startTime)

		if c.httpClient.metricsRecorder != nil {
			// Record request duration in milliseconds and status
			durationMs := duration.Milliseconds()
			(*c.httpClient.metricsRecorder).RecordRequest("get_secret", statusCode, durationMs)

			// Record error if any
			// Errors are already captured via the status code in RecordRequest
			// and will be properly tracked by the metrics system
		}

		c.logger.DebugContext(ctx, "Request completed",
			"secretRef", secretRef,
			"statusCode", statusCode,
			"duration", duration,
		)
	}()

	if secretRef == "" {
		return ClientModel.SecretValue{}, exceptions.NewGrayskullError(400, "secretRef cannot be empty")
	}

	// Parse secretRef format: "projectId:secretName"
	projectID, secretName, err := parseSecretRef(secretRef)
	if err != nil {
		return ClientModel.SecretValue{}, err
	}

	// Add to context for logging
	ctx = context.WithValue(ctx, constants.ProjectID, projectID)
	ctx = context.WithValue(ctx, constants.SecretName, secretName)

	c.logger.DebugContext(ctx, "Fetching secret",
		"requestID", requestID,
		"secretRef", secretRef,
	)

	// URL encode the path parameters
	encodedProjectID := url.PathEscape(projectID)
	encodedSecretName := url.PathEscape(secretName)
	url := fmt.Sprintf("%s/v1/projects/%s/secrets/%s/data", c.baseURL, encodedProjectID, encodedSecretName)

	// Make the HTTP request
	httpResp, err := c.httpClient.DoGetWithRetry(ctx, url)
	if err != nil {
		return ClientModel.SecretValue{}, err
	}
	statusCode = httpResp.StatusCode()

	// Parse response
	var resp response.Response[ClientModel.SecretValue]
	if err := json.Unmarshal([]byte(httpResp.Body()), &resp); err != nil {
		return ClientModel.SecretValue{}, exceptions.NewGrayskullErrorWithCause(500, "failed to parse response", err)
	}

	// Check if data is empty by checking if all fields are zero values
	var zeroValue ClientModel.SecretValue
	if reflect.DeepEqual(resp.Data, zeroValue) {
		return ClientModel.SecretValue{}, exceptions.NewGrayskullError(404, "secret not found or empty")
	}

	return resp.Data, nil
}

// RegisterRefreshHook registers a refresh hook for a secret
func (c *GrayskullClientImpl) RegisterRefreshHook(secretRef string, hook ClientHooks.SecretRefreshHook) (ClientHooks.RefreshHandlerRef, error) {
	requestID := generateRequestID()
	ctx := context.WithValue(context.Background(), constants.GrayskullRequestID, requestID)

	if secretRef == "" {
		return nil, exceptions.NewGrayskullError(400, "secretRef cannot be empty")
	}
	if hook == nil {
		return nil, exceptions.NewGrayskullError(400, "hook cannot be nil")
	}

	c.logger.DebugContext(ctx, "Registering refresh hook (placeholder implementation)",
		"requestID", requestID,
		"secretRef", secretRef,
	)

	// TODO: Implement actual hook invocation when server-side support is added
	return hooks.Instance, nil
}

// Helper functions
func generateRequestID() string {
	return uuid.NewString()
}

func parseSecretRef(secretRef string) (string, string, error) {
	parts := strings.SplitN(secretRef, ":", 2)
	if len(parts) != 2 {
		return "", "", exceptions.NewGrayskullError(
			400,
			fmt.Sprintf("invalid secretRef format. Expected 'projectId:secretName', got: %s", secretRef),
		)
	}

	projectID := strings.TrimSpace(parts[0])
	secretName := strings.TrimSpace(parts[1])

	if projectID == "" || secretName == "" {
		return "", "", exceptions.NewGrayskullError(
			400,
			fmt.Sprintf("projectId and secretName cannot be empty in secretRef: %s", secretRef),
		)
	}

	return projectID, secretName, nil
}
