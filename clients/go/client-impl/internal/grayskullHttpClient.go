package internal

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"time"

	apiconstants "github.com/flipkart-incubator/grayskull/clients/go/client-api/constants"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/auth"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/constants"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/internal/utils"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/metrics"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/models"
	grayskullErrors "github.com/flipkart-incubator/grayskull/clients/go/client-impl/models/errors"
)

// GrayskullHTTPClientInterface defines the interface for the HTTP client used
// by the Grayskull SDK. It is intentionally minimal: a GET that JSON-decodes
// the response in place, a POST that does the same with a JSON-encoded body,
// and a Close that releases pooled connections.
type GrayskullHTTPClientInterface interface {
	// DoGetWithRetry executes an HTTP GET against url and JSON-decodes the
	// response body into result. It returns the final HTTP status code and
	// any error encountered.
	DoGetWithRetry(ctx context.Context, url string, result any) (int, error)

	// DoPostWithRetry executes an HTTP POST against url with body as the
	// JSON-encoded request payload, and JSON-decodes the response body into
	// result. body may be nil for an empty request body. It returns the final
	// HTTP status code and any error encountered.
	DoPostWithRetry(ctx context.Context, url string, body []byte, result any) (int, error)

	// Close releases connection-pool resources owned by the client.
	Close() error
}

// GrayskullHTTPClient is a client for making HTTP requests to the Grayskull service
// with built-in retry, default-header injection, and error handling.
//
// All exported methods are safe for concurrent use.
type GrayskullHTTPClient struct {
	httpClient         *http.Client
	authHeaderProvider auth.GrayskullAuthHeaderProvider
	clientConfig       *models.GrayskullClientConfiguration
	retryConfig        utils.RetryConfig
	logger             *slog.Logger
	metricsRecorder    metrics.MetricsRecorder
}

// NewGrayskullHTTPClient creates a new instance of GrayskullHTTPClient.
//
// The returned client respects the connection / read timeouts and connection-pool
// knobs in config and applies retry behavior bounded by config.MaxRetries.
func NewGrayskullHTTPClient(authProvider auth.GrayskullAuthHeaderProvider, config *models.GrayskullClientConfiguration, logger *slog.Logger, metricsRecorder metrics.MetricsRecorder) GrayskullHTTPClientInterface {
	if logger == nil {
		logger = slog.Default().WithGroup("grayskull-http-client")
	}

	transport := &http.Transport{
		DialContext: (&net.Dialer{
			Timeout:   time.Duration(config.ConnectionTimeout) * time.Millisecond,
			KeepAlive: 30 * time.Second,
		}).DialContext,
		MaxIdleConns:        config.MaxIdleConns,
		MaxIdleConnsPerHost: config.MaxIdleConnsPerHost,
		IdleConnTimeout:     time.Duration(config.IdleConnTimeout) * time.Millisecond,
	}

	client := &http.Client{
		// Total request timeout = ConnectionTimeout + ReadTimeout. This
		// matches the Java SDK's separate connect / read budgets without
		// surprising the user with an unbounded read once connected.
		Timeout:   time.Duration(config.ConnectionTimeout+config.ReadTimeout) * time.Millisecond,
		Transport: transport,
	}

	retryConfig := utils.RetryConfig{
		MaxAttempts:   config.MaxRetries,
		InitialDelay:  time.Duration(config.MinRetryDelay) * time.Millisecond,
		MaxRetryDelay: 1 * time.Minute,
	}

	if metricsRecorder == nil {
		metricsRecorder = metrics.NewPrometheusRecorder(nil)
	}

	return &GrayskullHTTPClient{
		httpClient:         client,
		authHeaderProvider: authProvider,
		clientConfig:       config,
		retryConfig:        retryConfig,
		logger:             logger,
		metricsRecorder:    metricsRecorder,
	}
}

// httpResponse is an internal struct to hold response data during retry.
type httpResponse struct {
	Body       string
	StatusCode int
}

// DoGetWithRetry performs a GET request with retry logic and unmarshals the
// response into the provided result pointer. result may be nil to discard the
// response body. Returns the HTTP status code and any error encountered.
func (c *GrayskullHTTPClient) DoGetWithRetry(ctx context.Context, url string, result any) (int, error) {
	return c.executeWithRetry(ctx, url, result, func(ctx context.Context) (string, int, error) {
		return c.doRequest(ctx, http.MethodGet, url, nil)
	})
}

// DoPostWithRetry performs a POST request with retry logic, sending body as the
// JSON request payload, and unmarshals the response into the provided result
// pointer. body may be nil; result may be nil to discard the response body.
// Returns the HTTP status code and any error encountered.
func (c *GrayskullHTTPClient) DoPostWithRetry(ctx context.Context, url string, body []byte, result any) (int, error) {
	return c.executeWithRetry(ctx, url, result, func(ctx context.Context) (string, int, error) {
		return c.doRequest(ctx, http.MethodPost, url, body)
	})
}

// executeWithRetry centralizes the retry envelope used by both GET and POST.
// It records retry metrics on retried attempts (success or failure) and
// JSON-decodes a non-empty response body when a non-nil result pointer is
// provided.
func (c *GrayskullHTTPClient) executeWithRetry(ctx context.Context, url string, result any, attempt func(ctx context.Context) (string, int, error)) (int, error) {
	var attemptCount int

	httpResp, err := utils.Retry(ctx, c.retryConfig, func() (httpResponse, error) {
		attemptCount++
		body, status, attemptErr := attempt(ctx)
		if attemptErr != nil {
			return httpResponse{StatusCode: status}, attemptErr
		}
		return httpResponse{Body: body, StatusCode: status}, nil
	})

	if err != nil {
		var retryableErr *grayskullErrors.RetryableError
		if errors.As(err, &retryableErr) && attemptCount > 1 {
			c.metricsRecorder.RecordRetry(url, attemptCount, false)
		}
		return httpResp.StatusCode, fmt.Errorf("failed after %d attempts: %w", attemptCount, err)
	}

	if attemptCount > 1 {
		c.metricsRecorder.RecordRetry(url, attemptCount, true)
	}

	if result == nil || httpResp.Body == "" {
		return httpResp.StatusCode, nil
	}
	if err := json.Unmarshal([]byte(httpResp.Body), result); err != nil {
		return httpResp.StatusCode, fmt.Errorf("failed to unmarshal response: %w", err)
	}
	return httpResp.StatusCode, nil
}

// doRequest performs a single HTTP request without retries and returns the
// response body, status code, and any error.
//
// For POST requests the body argument is sent as the request payload with
// Content-Type application/json; charset=utf-8. For GET requests body is
// expected to be nil and is ignored.
func (c *GrayskullHTTPClient) doRequest(ctx context.Context, method, url string, body []byte) (string, int, error) {
	startTime := time.Now()

	var bodyReader io.Reader
	if body != nil {
		bodyReader = bytes.NewReader(body)
	}

	req, err := http.NewRequestWithContext(ctx, method, url, bodyReader)
	if err != nil {
		return "", 0, fmt.Errorf("failed to create request: %w", err)
	}

	if err := c.applyHeaders(ctx, req, body != nil); err != nil {
		return "", 0, err
	}

	c.logger.DebugContext(ctx, "Executing HTTP request",
		"url", url,
		"method", method,
		"body_length", len(body),
	)

	resp, err := c.httpClient.Do(req)
	if err != nil {
		// Network-level errors are transient and worth retrying.
		return "", 0, grayskullErrors.NewRetryableErrorWithCause("HTTP request failed", err)
	}
	defer resp.Body.Close()

	respBody, err := io.ReadAll(resp.Body)
	if err != nil {
		return "", resp.StatusCode, grayskullErrors.NewGrayskullErrorWithCause(resp.StatusCode, "failed to read response body", err)
	}

	c.logger.DebugContext(ctx, "Received HTTP response",
		"url", url,
		"status_code", resp.StatusCode,
		"status", resp.Status,
		"body_length", len(respBody),
	)

	if resp.StatusCode >= 400 {
		errMsg := fmt.Sprintf("request failed with status %d: %s", resp.StatusCode, string(respBody))
		c.metricsRecorder.RecordRequest(url, resp.StatusCode, time.Since(startTime))
		if isRetryableStatusCode(resp.StatusCode) {
			return "", resp.StatusCode, grayskullErrors.NewRetryableErrorWithStatus(resp.StatusCode, errMsg)
		}
		return "", resp.StatusCode, grayskullErrors.NewGrayskullError(resp.StatusCode, errMsg)
	}

	c.metricsRecorder.RecordRequest(url, resp.StatusCode, time.Since(startTime))
	return string(respBody), resp.StatusCode, nil
}

// applyHeaders attaches the auth header, the per-request correlation header,
// the JSON content-type for POSTs, and any default headers configured on the
// client (e.g., Grayskull-Workload, User-Agent).
func (c *GrayskullHTTPClient) applyHeaders(ctx context.Context, req *http.Request, hasBody bool) error {
	for name, value := range c.clientConfig.DefaultHeaders() {
		req.Header.Set(name, value)
	}

	authHeader, err := c.authHeaderProvider.GetAuthHeader()
	if err != nil {
		return fmt.Errorf("failed to get auth header: %w", err)
	}
	if authHeader == "" {
		return errors.New("auth header cannot be empty")
	}
	req.Header.Set(apiconstants.AuthorizationHeader, authHeader)

	if requestID := ctx.Value(constants.GrayskullRequestID); requestID != nil {
		req.Header.Set(apiconstants.RequestIDHeader, fmt.Sprintf("%v", requestID))
	}

	if hasBody && req.Header.Get("Content-Type") == "" {
		req.Header.Set("Content-Type", "application/json; charset=utf-8")
	}

	return nil
}

// isRetryableStatusCode checks if an HTTP status code indicates a retryable error.
// Mirrors GrayskullHttpClient.isRetryableStatusCode in the Java SDK.
func isRetryableStatusCode(statusCode int) bool {
	return statusCode == http.StatusTooManyRequests || (statusCode >= http.StatusInternalServerError && statusCode < 600)
}

// Close releases any resources used by the HTTP client (currently, idle
// keep-alive connections held by the underlying transport).
func (c *GrayskullHTTPClient) Close() error {
	if transport, ok := c.httpClient.Transport.(*http.Transport); ok {
		transport.CloseIdleConnections()
	}
	return nil
}
