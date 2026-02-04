package internal

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"time"

	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/auth"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/constants"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/internal/utils"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/metrics"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/models"
	grayskullErrors "github.com/flipkart-incubator/grayskull/clients/go/client-impl/models/errors"
)

// GrayskullHTTPClientInterface defines the interface for the HTTP client
type GrayskullHTTPClientInterface interface {
	DoGetWithRetry(ctx context.Context, url string, result any) (int, error)
	Close() error
}

// GrayskullHTTPClient is a client for making HTTP requests to the Grayskull service
// with built-in retry and error handling.
type GrayskullHTTPClient struct {
	httpClient         *http.Client
	authHeaderProvider auth.GrayskullAuthHeaderProvider
	retryConfig        utils.RetryConfig
	logger             *slog.Logger
	metricsRecorder    metrics.MetricsRecorder
}

// NewGrayskullHTTPClient creates a new instance of GrayskullHTTPClient
func NewGrayskullHTTPClient(authProvider auth.GrayskullAuthHeaderProvider, config *models.GrayskullClientConfiguration, logger *slog.Logger, metricsRecorder metrics.MetricsRecorder) GrayskullHTTPClientInterface {
	if logger == nil {
		logger = slog.Default().WithGroup("grayskull-http-client")
	}

	transport := &http.Transport{
		DialContext: (&net.Dialer{
			// Connection timeout - how long to wait for the initial connection
			Timeout:   time.Duration(config.ConnectionTimeout) * time.Millisecond,
			KeepAlive: 30 * time.Second,
		}).DialContext,
		MaxIdleConns:        config.MaxIdleConns,
		MaxIdleConnsPerHost: config.MaxIdleConnsPerHost,
		IdleConnTimeout:     time.Duration(config.IdleConnTimeout) * time.Millisecond,
	}

	client := &http.Client{
		// Total request timeout = ConnectionTimeout + ReadTimeout
		// This ensures we don't wait forever for a response after connection is established
		Timeout:   time.Duration(config.ConnectionTimeout+config.ReadTimeout) * time.Millisecond,
		Transport: transport,
	}

	retryConfig := utils.RetryConfig{
		MaxAttempts:   config.MaxRetries,
		InitialDelay:  time.Duration(config.MinRetryDelay) * time.Millisecond,
		MaxRetryDelay: 1 * time.Minute, // Default max retry delay
	}

	if metricsRecorder == nil {
		metricsRecorder = metrics.NewPrometheusRecorder(nil)
	}

	return &GrayskullHTTPClient{
		httpClient:         client,
		authHeaderProvider: authProvider,
		retryConfig:        retryConfig,
		logger:             logger,
		metricsRecorder:    metricsRecorder,
	}
}

// httpResponse is an internal struct to hold response data during retry
type httpResponse struct {
	Body       string
	StatusCode int
}

// DoGetWithRetry performs a GET request with retry logic and unmarshals the response into the provided type
// The result parameter should be a pointer to the target type for unmarshalling
// Returns the HTTP status code and any error encountered
func (c *GrayskullHTTPClient) DoGetWithRetry(ctx context.Context, url string, result any) (int, error) {
	var attemptCount int

	httpResp, err := utils.Retry(ctx, c.retryConfig, func() (httpResponse, error) {
		attemptCount++
		body, status, err := c.doGet(ctx, url)
		if err != nil {
			return httpResponse{StatusCode: status}, err
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

	// Unmarshal the response body into the provided result type
	if err := json.Unmarshal([]byte(httpResp.Body), result); err != nil {
		return httpResp.StatusCode, fmt.Errorf("failed to unmarshal response: %w", err)
	}

	return httpResp.StatusCode, nil
}

// doGet performs a single GET request without retries
// Returns the response body, status code, and any error
func (c *GrayskullHTTPClient) doGet(ctx context.Context, url string) (string, int, error) {
	startTime := time.Now()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return "", 0, fmt.Errorf("failed to create request: %w", err)
	}

	// Add auth header
	authHeader, err := c.authHeaderProvider.GetAuthHeader()
	if err != nil {
		return "", 0, fmt.Errorf("failed to get auth header: %w", err)
	}
	if authHeader == "" {
		return "", 0, errors.New("auth header cannot be empty")
	}
	req.Header.Set("Authorization", authHeader)

	// Add request ID from context if available
	if requestID := ctx.Value(constants.GrayskullRequestID); requestID != nil {
		req.Header.Set("X-Request-Id", fmt.Sprintf("%v", requestID))
	}

	c.logger.DebugContext(ctx, "Executing HTTP request",
		"url", url,
		"method", http.MethodGet,
	)

	resp, err := c.httpClient.Do(req)
	if err != nil {
		// Convert network errors to retryable errors
		return "", 0, grayskullErrors.NewRetryableErrorWithCause("HTTP request failed", err)
	}
	defer resp.Body.Close()

	// Read response body
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return "", resp.StatusCode, grayskullErrors.NewGrayskullErrorWithCause(resp.StatusCode, "failed to read response body", err)
	}

	// Log response details
	c.logger.DebugContext(ctx, "Received HTTP response",
		"url", url,
		"status_code", resp.StatusCode,
		"status", resp.Status,
		"body_length", len(body),
	)

	// Handle error status codes
	if resp.StatusCode >= 400 {
		errMsg := fmt.Sprintf("request failed with status %d: %s", resp.StatusCode, string(body))
		// Record metrics before returning error
		if c.metricsRecorder != nil {
			c.metricsRecorder.RecordRequest(url, resp.StatusCode, time.Since(startTime))
		}
		if isRetryableStatusCode(resp.StatusCode) {
			return "", resp.StatusCode, grayskullErrors.NewRetryableErrorWithStatus(resp.StatusCode, errMsg)
		}
		return "", resp.StatusCode, grayskullErrors.NewGrayskullError(resp.StatusCode, errMsg)
	}

	// Record the request metrics
	c.metricsRecorder.RecordRequest(url, resp.StatusCode, time.Since(startTime))

	return string(body), resp.StatusCode, nil
}

// isRetryableStatusCode checks if an HTTP status code indicates a retryable error
func isRetryableStatusCode(statusCode int) bool {
	return statusCode == http.StatusTooManyRequests || (statusCode >= http.StatusInternalServerError && statusCode < 600)
}

// Close releases any resources used by the HTTP client
func (c *GrayskullHTTPClient) Close() error {
	// Close idle connections
	if transport, ok := c.httpClient.Transport.(*http.Transport); ok {
		transport.CloseIdleConnections()
	}
	return nil
}
