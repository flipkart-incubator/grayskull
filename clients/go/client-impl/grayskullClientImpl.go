package client_impl

import (
	"context"
	"fmt"
	"log/slog"
	"net/url"
	"strings"
	"time"

	Client_API_Hooks "github.com/flipkart-incubator/grayskull/clients/go/client-api/hooks"
	Client_API "github.com/flipkart-incubator/grayskull/clients/go/client-api/models"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/auth"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/constants"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/internal"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/internal/hooks"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/metrics"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/models"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/models/errors"
	"github.com/go-playground/validator/v10"
	"github.com/google/uuid"
)

// GrayskullClientImpl implements the Grayskull client interface
type GrayskullClientImpl struct {
	baseURL            string
	authHeaderProvider auth.GrayskullAuthHeaderProvider
	clientConfig       *models.GrayskullClientConfiguration
	httpClient         internal.GrayskullHTTPClientInterface
	metricsRecorder    metrics.MetricsRecorder
}

var validate = validator.New()

// validateConfig validates the GrayskullClientConfiguration using go-playground/validator
func validateConfig(config *models.GrayskullClientConfiguration) error {
	if err := validate.Struct(config); err != nil {
		if validationErrors, ok := err.(validator.ValidationErrors); ok {
			// Format validation errors into user-friendly messages
			for _, fieldErr := range validationErrors {
				switch fieldErr.Tag() {
				case "required":
					return errors.NewGrayskullErrorWithMessage(fmt.Sprintf("%s is required", fieldErr.Field()))
				case "url":
					return errors.NewGrayskullErrorWithMessage(fmt.Sprintf("invalid host URL: %s", fieldErr.Value()))
				case "gte":
					return errors.NewGrayskullErrorWithMessage(fmt.Sprintf("%s cannot be negative", fieldErr.Field()))
				case "gt":
					return errors.NewGrayskullErrorWithMessage(fmt.Sprintf("%s must be greater than 0", fieldErr.Field()))
				}
			}
			return errors.NewGrayskullErrorWithMessage(validationErrors.Error())
		}
		return errors.NewGrayskullErrorWithCause(400, "invalid configuration", err)

	}
	return nil
}

// NewGrayskullClient creates a new instance of GrayskullClientImpl
func NewGrayskullClient(authProvider auth.GrayskullAuthHeaderProvider, config *models.GrayskullClientConfiguration, metricsRecorder metrics.MetricsRecorder) (*GrayskullClientImpl, error) {
	if authProvider == nil {
		return nil, errors.NewGrayskullErrorWithMessage("authHeaderProvider cannot be nil")
	}

	if config == nil {
		return nil, errors.NewGrayskullErrorWithMessage("grayskullClientConfiguration cannot be nil")
	}

	// Validate the config
	if err := validateConfig(config); err != nil {
		return nil, err
	}

	// Use default logger and Prometheus metrics recorder if not provided
	logger := slog.Default().With("component", "grayskull-client")
	if metricsRecorder == nil {
		metricsRecorder = metrics.NewPrometheusRecorder(nil)
	}

	httpClient := internal.NewGrayskullHTTPClient(authProvider, config, logger, metricsRecorder)

	return &GrayskullClientImpl{
		baseURL:            config.Host,
		authHeaderProvider: authProvider,
		clientConfig:       config,
		httpClient:         httpClient,
		metricsRecorder:    metricsRecorder,
	}, nil
}

// splitSecretRef splits the secret reference into project ID and secret name
// It splits only on the first colon to handle secret names that may contain colons
func (c *GrayskullClientImpl) splitSecretRef(secretRef string) []string {
	return strings.SplitN(secretRef, ":", 2)
}

// GetSecret retrieves a secret from the Grayskull server
func (g *GrayskullClientImpl) GetSecret(ctx context.Context, secretRef string) (*Client_API.SecretValue, error) {
	// If no context provided, create a new one with request ID
	if ctx == nil {
		ctx = context.Background()
	}

	// Add request ID to context if not already present
	if ctx.Value(constants.GrayskullRequestID) == nil {
		requestID := uuid.New().String()
		ctx = context.WithValue(ctx, constants.GrayskullRequestID, requestID)
	}

	startTime := time.Now()
	var statusCode int

	if secretRef == "" {
		return nil, errors.NewGrayskullError(400, "secretRef cannot be empty")
	}

	// Parse secretRef format: "projectId:secretName"
	parts := g.splitSecretRef(secretRef)
	if len(parts) != 2 {
		return nil, errors.NewGrayskullError(400, fmt.Sprintf("invalid secretRef format. Expected 'projectId:secretName', got: %s", secretRef))
	}

	projectID, secretName := parts[0], parts[1]
	if projectID == "" || secretName == "" {
		return nil, errors.NewGrayskullError(400, fmt.Sprintf("projectId and secretName cannot be empty in secretRef: %s", secretRef))
	}

	// Add context values
	ctx = context.WithValue(ctx, constants.ProjectID, projectID)
	ctx = context.WithValue(ctx, constants.SecretName, secretName)

	// URL encode the path parameters
	encodedProjectID := url.PathEscape(projectID)
	encodedSecretName := url.PathEscape(secretName)
	url := fmt.Sprintf("%s/v1/projects/%s/secrets/%s/data", g.baseURL, encodedProjectID, encodedSecretName)

	// Fetch the secret with automatic retry logic
	httpResponse, err := g.httpClient.DoGetWithRetry(ctx, url)
	if httpResponse != nil {
		statusCode = httpResponse.StatusCode()
	}
	if err != nil {
		duration := time.Since(startTime)
		g.metricsRecorder.RecordRequest("get_secret", statusCode, duration)
		return nil, errors.NewGrayskullErrorWithCause(statusCode, "failed to fetch secret", err)
	}

	// Unmarshal JSON response using the generic Response type
	secretResp, err := internal.UnmarshalResponse[Client_API.SecretValue](httpResponse.Body())
	if err != nil {
		duration := time.Since(startTime)
		g.metricsRecorder.RecordRequest("get_secret", statusCode, duration)
		return nil, errors.NewGrayskullErrorWithCause(500, "failed to parse response", err)
	}

	// After unmarshaling the response
	data := secretResp.Data
	if data == (Client_API.SecretValue{}) {
		duration := time.Since(startTime)
		g.metricsRecorder.RecordRequest("get_secret", statusCode, duration)
		return nil, errors.NewGrayskullError(500, "no data in response")
	}

	// Record metrics for success case
	duration := time.Since(startTime)
	g.metricsRecorder.RecordRequest("get_secret", statusCode, duration)

	// Return a pointer to the Data field
	return &data, nil
}

// RegisterRefreshHook registers a refresh hook for a secret
func (c *GrayskullClientImpl) RegisterRefreshHook(ctx context.Context, secretRef string, hook Client_API_Hooks.SecretRefreshHook) (Client_API_Hooks.RefreshHandlerRef, error) {
	if secretRef == "" {
		return nil, errors.NewGrayskullError(400, "secretRef cannot be empty")
	}
	if hook == nil {
		return nil, errors.NewGrayskullError(400, "hook cannot be nil")
	}

	// TODO: Implement actual hook invocation when server-side events support is added
	// Return the singleton instance of the no-op implementation
	return hooks.GetInstance(), nil
}
