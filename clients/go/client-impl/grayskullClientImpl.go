package client_impl

import (
	"context"
	"fmt"
	"log/slog"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	Client_API "github.com/flipkart-incubator/grayskull/clients/go/client-api/models"

	clientapi "github.com/flipkart-incubator/grayskull/clients/go/client-api"
	apiconstants "github.com/flipkart-incubator/grayskull/clients/go/client-api/constants"
	Client_API_Hooks "github.com/flipkart-incubator/grayskull/clients/go/client-api/hooks"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/auth"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/constants"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/internal"
	internalHooks "github.com/flipkart-incubator/grayskull/clients/go/client-impl/internal/hooks"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/internal/models/response"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/metrics"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/models"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/models/errors"
	"github.com/go-playground/validator/v10"
	"github.com/google/uuid"
)

// GrayskullClientImpl implements the Grayskull client interface.
type GrayskullClientImpl struct {
	baseURL            string
	authHeaderProvider auth.GrayskullAuthHeaderProvider
	clientConfig       *models.GrayskullClientConfiguration
	httpClient         internal.GrayskullHTTPClientInterface
	metricsRecorder    metrics.MetricsRecorder

	registry         *internalHooks.Registry
	poller           *internal.Poller
	lastSeenVersions sync.Map
	closed           atomic.Bool
}

var validate = validator.New()

// validateConfig validates the GrayskullClientConfiguration.
func validateConfig(config *models.GrayskullClientConfiguration) error {
	if err := validate.Struct(config); err != nil {
		if validationErrors, ok := err.(validator.ValidationErrors); ok {
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
				default:
					return errors.NewGrayskullErrorWithMessage(validationErrors.Error())
				}
			}
		}
		return errors.NewGrayskullErrorWithCause(http.StatusBadRequest, "invalid configuration", err)

	}
	return nil
}

// NewGrayskullClient creates a new Grayskull client.
func NewGrayskullClient(authProvider auth.GrayskullAuthHeaderProvider, config *models.GrayskullClientConfiguration, metricsRecorder metrics.MetricsRecorder) (clientapi.Client, error) {
	if authProvider == nil {
		return nil, errors.NewGrayskullErrorWithMessage("authHeaderProvider cannot be nil")
	}

	if config == nil {
		return nil, errors.NewGrayskullErrorWithMessage("grayskullClientConfiguration cannot be nil")
	}

	if err := validateConfig(config); err != nil {
		return nil, err
	}

	// Workload identity is resolved once and pinned as a default header.
	identity := config.GetWorkloadIdentityResolver().Resolve()
	config.AddDefaultHeader(apiconstants.HeaderWorkload, identity)

	// User-Agent: grayskull-go/<version>.
	userAgent := "grayskull-go/" + GetVersion()
	config.AddDefaultHeader(apiconstants.HeaderUserAgent, userAgent)

	logger := slog.Default().With("component", "grayskull-client")
	if metricsRecorder == nil {
		metricsRecorder = metrics.NewPrometheusRecorder(nil)
	}

	httpClient := internal.NewGrayskullHTTPClient(authProvider, config, logger, metricsRecorder)

	client := &GrayskullClientImpl{
		baseURL:            config.Host,
		authHeaderProvider: authProvider,
		clientConfig:       config,
		httpClient:         httpClient,
		metricsRecorder:    metricsRecorder,
		registry:           internalHooks.NewRegistry(),
	}

	interval := time.Duration(config.PollingIntervalSeconds) * time.Second
	if interval <= 0 {
		interval = time.Duration(constants.DefaultPollingIntervalSeconds) * time.Second
	}
	client.poller = internal.NewPoller(internal.PollerConfig{
		BaseURL:         config.Host,
		HTTPClient:      httpClient,
		Registry:        client.registry,
		Interval:        interval,
		MetricsRecorder: metricsRecorder,
		Logger:          logger.With("subcomponent", "poller"),
	})
	client.poller.Start()

	return client, nil

}

// splitSecretRef splits "projectId:secretName" on the FIRST colon (secret
// names may contain colons).
func (c *GrayskullClientImpl) splitSecretRef(secretRef string) []string {
	return strings.SplitN(secretRef, ":", 2)
}

// GetSecret fetches a secret from the Grayskull server.
func (g *GrayskullClientImpl) GetSecret(ctx context.Context, secretRef string) (*Client_API.SecretValue, error) {
	if ctx == nil {
		ctx = context.Background()
	}
	if ctx.Value(constants.GrayskullRequestID) == nil {
		requestID := uuid.New().String()
		ctx = context.WithValue(ctx, constants.GrayskullRequestID, requestID)
	}

	startTime := time.Now()
	var statusCode int

	if secretRef == "" {
		g.metricsRecorder.RecordRequest("get_secret", http.StatusBadRequest, time.Since(startTime))
		return nil, errors.NewGrayskullError(http.StatusBadRequest, "secretRef cannot be empty")
	}

	parts := g.splitSecretRef(secretRef)
	if len(parts) != 2 {
		g.metricsRecorder.RecordRequest("get_secret", http.StatusBadRequest, time.Since(startTime))
		return nil, errors.NewGrayskullError(http.StatusBadRequest, fmt.Sprintf("invalid secretRef format. Expected 'projectId:secretName', got: %s", secretRef))
	}

	projectID, secretName := parts[0], parts[1]
	if projectID == "" || secretName == "" {
		g.metricsRecorder.RecordRequest("get_secret", http.StatusBadRequest, time.Since(startTime))
		return nil, errors.NewGrayskullError(http.StatusBadRequest, fmt.Sprintf("projectId and secretName cannot be empty in secretRef: %s", secretRef))
	}

	encodedProjectID := url.PathEscape(projectID)
	encodedSecretName := url.PathEscape(secretName)
	getSecretUrl := fmt.Sprintf("%s/v1/projects/%s/secrets/%s/data", g.baseURL, encodedProjectID, encodedSecretName)

	var secretResp response.Response[Client_API.SecretValue]
	statusCode, err := g.httpClient.DoGetWithRetry(ctx, getSecretUrl, &secretResp)
	if err != nil {
		duration := time.Since(startTime)
		g.metricsRecorder.RecordRequest("get_secret", statusCode, duration)
		return nil, errors.NewGrayskullErrorWithCause(statusCode, "failed to fetch secret", err)
	}

	data := secretResp.Data
	if data == (Client_API.SecretValue{}) {
		g.metricsRecorder.RecordRequest("get_secret", statusCode, time.Since(startTime))
		return nil, errors.NewGrayskullError(http.StatusInternalServerError, "no data in response")
	}

	g.metricsRecorder.RecordRequest("get_secret", statusCode, time.Since(startTime))

	// Track latest observed version so a later RegisterRefreshHook can seed
	// the poller and skip versions the app has already seen.
	g.lastSeenVersions.Store(secretRef, data.DataVersion)

	return &data, nil
}

// RegisterRefreshHook registers a refresh hook for a secret.
func (c *GrayskullClientImpl) RegisterRefreshHook(ctx context.Context, secretRef string, hook Client_API_Hooks.SecretRefreshHook) (Client_API_Hooks.RefreshHandlerRef, error) {
	if c.closed.Load() {
		return nil, errors.NewGrayskullError(http.StatusBadRequest, "client has been closed; cannot register new refresh hooks")
	}
	if secretRef == "" {
		return nil, errors.NewGrayskullError(http.StatusBadRequest, "secretRef cannot be empty")
	}
	if hook == nil {
		return nil, errors.NewGrayskullError(http.StatusBadRequest, "hook cannot be nil")
	}

	parts := c.splitSecretRef(secretRef)
	if len(parts) != 2 || parts[0] == "" || parts[1] == "" {
		return nil, errors.NewGrayskullError(http.StatusBadRequest,
			fmt.Sprintf("invalid secretRef format. Expected 'projectId:secretName', got: %s", secretRef))
	}

	seed := 0
	if v, ok := c.lastSeenVersions.Load(secretRef); ok {
		if iv, ok := v.(int); ok {
			seed = iv
		}
	}
	return c.registry.Register(parts[0], parts[1], hook, seed), nil
}

// Close stops the poller and releases HTTP transport resources. Returns the
// first error encountered so callers can see partial-shutdown failures
// (poller timeout, transport close).
func (c *GrayskullClientImpl) Close() error {
	if !c.closed.CompareAndSwap(false, true) {
		return nil
	}
	var firstErr error
	if c.poller != nil {
		if err := c.poller.Close(); err != nil {
			firstErr = err
		}
	}
	if c.httpClient != nil {
		if err := c.httpClient.Close(); err != nil && firstErr == nil {
			firstErr = err
		}
	}
	return firstErr
}
