// Package client_impl is the Grayskull Go SDK's reference client implementation.
//
// It connects to a Grayskull server, fetches secrets with retry on transient
// failures, and supports application-driven refresh hooks that are invoked
// asynchronously by a background poller (HookRefreshPoller). Both behaviors
// are designed to be byte-for-byte compatible with the Java SDK's
// com.flipkart.grayskull.GrayskullClientImpl.
package client_impl

import (
	"context"
	"fmt"
	"log/slog"
	"net/url"
	"strings"
	"sync"
	"time"

	"github.com/go-playground/validator/v10"
	"github.com/google/uuid"

	clientapi "github.com/flipkart-incubator/grayskull/clients/go/client-api"
	apiconstants "github.com/flipkart-incubator/grayskull/clients/go/client-api/constants"
	apihooks "github.com/flipkart-incubator/grayskull/clients/go/client-api/hooks"
	apimodels "github.com/flipkart-incubator/grayskull/clients/go/client-api/models"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/auth"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/constants"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/internal"
	internalhooks "github.com/flipkart-incubator/grayskull/clients/go/client-impl/internal/hooks"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/internal/models/response"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/metrics"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/models"
	grayskullErrors "github.com/flipkart-incubator/grayskull/clients/go/client-impl/models/errors"
	implworkload "github.com/flipkart-incubator/grayskull/clients/go/client-impl/workload"
)

// GrayskullClientImpl is the concrete Grayskull SDK client.
//
// It satisfies the public client_api.Client interface. It additionally
// exposes Close, which is not part of the interface (mirroring Go's
// preference for narrow interfaces): callers who want deterministic
// shutdown should retain the *GrayskullClientImpl and defer Close().
type GrayskullClientImpl struct {
	baseURL            string
	authHeaderProvider auth.GrayskullAuthHeaderProvider
	clientConfig       *models.GrayskullClientConfiguration
	httpClient         internal.GrayskullHTTPClientInterface
	metricsRecorder    metrics.MetricsRecorder

	refreshPoller *internalhooks.HookRefreshPoller

	closed    bool
	closeMu   sync.Mutex
	closeOnce sync.Once
}

// Compile-time check: GrayskullClientImpl satisfies the public Client interface.
var _ clientapi.Client = (*GrayskullClientImpl)(nil)

var validate = validator.New()

// validateConfig validates the GrayskullClientConfiguration using
// go-playground/validator. The user-facing messages mirror those returned by
// the Java SDK's IllegalArgumentExceptions in GrayskullClientConfiguration's
// setters.
func validateConfig(config *models.GrayskullClientConfiguration) error {
	if err := validate.Struct(config); err != nil {
		var validationErrors validator.ValidationErrors
		if asValidator(err, &validationErrors) {
			for _, fieldErr := range validationErrors {
				switch fieldErr.Tag() {
				case "required":
					return grayskullErrors.NewGrayskullErrorWithMessage(
						fmt.Sprintf("%s is required", fieldErr.Field()))
				case "url":
					return grayskullErrors.NewGrayskullErrorWithMessage(
						fmt.Sprintf("invalid host URL: %s", fieldErr.Value()))
				case "gte":
					return grayskullErrors.NewGrayskullErrorWithMessage(
						fmt.Sprintf("%s cannot be negative", fieldErr.Field()))
				case "gt":
					return grayskullErrors.NewGrayskullErrorWithMessage(
						fmt.Sprintf("%s must be greater than 0", fieldErr.Field()))
				}
			}
			return grayskullErrors.NewGrayskullErrorWithMessage(validationErrors.Error())
		}
		return grayskullErrors.NewGrayskullErrorWithCause(400, "invalid configuration", err)
	}
	return nil
}

// asValidator narrows the standard library "errors.As" indirection so the
// switch above stays focused on the validation logic itself.
func asValidator(src error, dst *validator.ValidationErrors) bool {
	if v, ok := src.(validator.ValidationErrors); ok {
		*dst = v
		return true
	}
	return false
}

// NewGrayskullClient creates a new instance of the Grayskull client.
//
// authProvider must produce a non-empty Authorization header on each call;
// config must validate successfully (see validateConfig). If metricsRecorder
// is nil, a default Prometheus recorder backed by the global registry is
// created.
func NewGrayskullClient(authProvider auth.GrayskullAuthHeaderProvider, config *models.GrayskullClientConfiguration, metricsRecorder metrics.MetricsRecorder) (clientapi.Client, error) {
	if authProvider == nil {
		return nil, grayskullErrors.NewGrayskullErrorWithMessage("authHeaderProvider cannot be nil")
	}
	if config == nil {
		return nil, grayskullErrors.NewGrayskullErrorWithMessage("grayskullClientConfiguration cannot be nil")
	}
	if err := validateConfig(config); err != nil {
		return nil, err
	}

	logger := slog.Default().With("component", "grayskull-client")
	if metricsRecorder == nil {
		metricsRecorder = metrics.NewPrometheusRecorder(nil)
	}

	// Resolve workload identity exactly once and pin it on the configuration's
	// default headers, mirroring the Java client's "resolve at construction"
	// semantics.
	if config.WorkloadIdentityResolver == nil {
		config.WorkloadIdentityResolver = implworkload.NewDefaultWorkloadIdentityResolver()
	}
	identity := config.WorkloadIdentityResolver.Resolve()
	config.AddDefaultHeader(apiconstants.WorkloadHeader, identity)
	config.AddDefaultHeader(apiconstants.UserAgentHeader, "grayskull-go/"+SDKVersion)

	httpClient := internal.NewGrayskullHTTPClient(authProvider, config, logger, metricsRecorder)

	pollingInterval := config.PollingIntervalSeconds
	if pollingInterval <= 0 {
		// Backwards-compat for callers that built configs by hand and never
		// set PollingIntervalSeconds: fall back to the Java SDK's default
		// rather than failing.
		pollingInterval = 60
	}

	refreshPoller := internalhooks.NewHookRefreshPoller(internalhooks.PollerConfig{
		HTTPClient:      httpClient,
		BaseURL:         config.Host,
		IntervalSeconds: pollingInterval,
		Logger:          logger,
		Metrics:         metricsRecorder,
	})

	return &GrayskullClientImpl{
		baseURL:            config.Host,
		authHeaderProvider: authProvider,
		clientConfig:       config,
		httpClient:         httpClient,
		metricsRecorder:    metricsRecorder,
		refreshPoller:      refreshPoller,
	}, nil
}

// splitSecretRef splits "projectId:secretName" on the first colon to handle
// secret names that may themselves contain colons.
func (g *GrayskullClientImpl) splitSecretRef(secretRef string) []string {
	return strings.SplitN(secretRef, ":", 2)
}

// GetSecret retrieves a secret from the Grayskull server. The secretRef must
// be in the format "projectId:secretName" (for example, "my-project:db-pwd").
func (g *GrayskullClientImpl) GetSecret(ctx context.Context, secretRef string) (*apimodels.SecretValue, error) {
	if ctx == nil {
		ctx = context.Background()
	}
	if ctx.Value(constants.GrayskullRequestID) == nil {
		ctx = context.WithValue(ctx, constants.GrayskullRequestID, uuid.New().String())
	}

	startTime := time.Now()
	statusCode := 0

	if secretRef == "" {
		g.metricsRecorder.RecordRequest("get_secret", 400, time.Since(startTime))
		return nil, grayskullErrors.NewGrayskullError(400, "secretRef cannot be empty")
	}

	parts := g.splitSecretRef(secretRef)
	if len(parts) != 2 {
		g.metricsRecorder.RecordRequest("get_secret", 400, time.Since(startTime))
		return nil, grayskullErrors.NewGrayskullError(400,
			fmt.Sprintf("invalid secretRef format. Expected 'projectId:secretName', got: %s", secretRef))
	}

	projectID, secretName := parts[0], parts[1]
	if projectID == "" || secretName == "" {
		g.metricsRecorder.RecordRequest("get_secret", 400, time.Since(startTime))
		return nil, grayskullErrors.NewGrayskullError(400,
			fmt.Sprintf("projectId and secretName cannot be empty in secretRef: %s", secretRef))
	}

	encodedProjectID := url.PathEscape(projectID)
	encodedSecretName := url.PathEscape(secretName)
	getSecretURL := fmt.Sprintf("%s/v1/projects/%s/secrets/%s/data",
		g.baseURL, encodedProjectID, encodedSecretName)

	var secretResp response.Response[apimodels.SecretValue]
	statusCode, err := g.httpClient.DoGetWithRetry(ctx, getSecretURL, &secretResp)
	if err != nil {
		g.metricsRecorder.RecordRequest("get_secret", statusCode, time.Since(startTime))
		return nil, grayskullErrors.NewGrayskullErrorWithCause(statusCode, "failed to fetch secret", err)
	}

	data := secretResp.Data
	if data == (apimodels.SecretValue{}) {
		g.metricsRecorder.RecordRequest("get_secret", statusCode, time.Since(startTime))
		return nil, grayskullErrors.NewGrayskullError(500, "no data in response")
	}

	g.metricsRecorder.RecordRequest("get_secret", statusCode, time.Since(startTime))
	return &data, nil
}

// RegisterRefreshHook registers a callback to be invoked when the server
// reports a newer version of secretRef during the periodic batch poll.
//
// Multiple hooks may be registered for the same secret; each is delivered
// sequentially with the latest known value (older values are coalesced when
// they overlap with a slow hook). The returned RefreshHandlerRef is the
// caller's handle for unregistering.
func (g *GrayskullClientImpl) RegisterRefreshHook(ctx context.Context, secretRef string, hook apihooks.SecretRefreshHook) (apihooks.RefreshHandlerRef, error) {
	_ = ctx
	if secretRef == "" {
		return nil, grayskullErrors.NewGrayskullError(400, "secretRef cannot be empty")
	}
	if hook == nil {
		return nil, grayskullErrors.NewGrayskullError(400, "hook cannot be nil")
	}

	parts := g.splitSecretRef(secretRef)
	if len(parts) != 2 || parts[0] == "" || parts[1] == "" {
		return nil, grayskullErrors.NewGrayskullError(400,
			fmt.Sprintf("invalid secretRef format. Expected 'projectId:secretName', got: %s", secretRef))
	}

	if g.refreshPoller == nil {
		// Test seam: clients constructed by hand (e.g., NewGrayskullClientForTesting)
		// without a refresh poller fall back to a no-op handle so unit tests that
		// only exercise GetSecret do not need to spin up a poller.
		return newNoopHandlerRef(secretRef), nil
	}
	return g.refreshPoller.Register(parts[0], parts[1], hook), nil
}

// Close releases resources held by the client: it stops the background hook
// poller, drains the dispatcher worker pool, and closes idle HTTP connections.
// Safe to call multiple times; only the first call performs work.
func (g *GrayskullClientImpl) Close() error {
	var err error
	g.closeOnce.Do(func() {
		g.closeMu.Lock()
		g.closed = true
		g.closeMu.Unlock()

		if g.refreshPoller != nil {
			g.refreshPoller.Close()
		}
		if g.httpClient != nil {
			err = g.httpClient.Close()
		}
	})
	return err
}

// noopHandlerRef is returned only by the testing constructor
// NewGrayskullClientForTesting when no refresh poller has been wired up. In
// production, RegisterRefreshHook always returns a DefaultRefreshHandlerRef.
type noopHandlerRef struct {
	secretRef string
}

var _ apihooks.RefreshHandlerRef = (*noopHandlerRef)(nil)

func newNoopHandlerRef(secretRef string) *noopHandlerRef {
	return &noopHandlerRef{secretRef: secretRef}
}

func (n *noopHandlerRef) GetSecretRef() string { return n.secretRef }
func (n *noopHandlerRef) IsActive() bool       { return false }
func (n *noopHandlerRef) Unregister()          {}
