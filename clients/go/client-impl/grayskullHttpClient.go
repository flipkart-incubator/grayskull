package client_impl

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"sync/atomic"
	"time"

	"github.com/flipkart-incubator/grayskull/client-impl/auth"
	"github.com/flipkart-incubator/grayskull/client-impl/constants"
	"github.com/flipkart-incubator/grayskull/client-impl/metrics"
	"github.com/flipkart-incubator/grayskull/client-impl/models"
	"github.com/flipkart-incubator/grayskull/client-impl/models/exceptions"
	"github.com/flipkart-incubator/grayskull/client-impl/models/response"
	"github.com/flipkart-incubator/grayskull/client-impl/utils"
)

// GrayskullHTTPClientInterface defines the interface for the HTTP client
type GrayskullHTTPClientInterface interface {
	DoGetWithRetry(ctx context.Context, url string) (*response.HttpResponse, error)
	Close() error
}

// GrayskullHTTPClient is a client for making HTTP requests to the Grayskull service
// with built-in retry and error handling.
type GrayskullHTTPClient struct {
	httpClient         *http.Client
	authHeaderProvider auth.GrayskullAuthHeaderProvider
	retryUtil          *utils.RetryUtil
	logger             *slog.Logger
	metricsRecorder    metrics.MetricsRecorder
}

// NewGrayskullHTTPClient creates a new instance of GrayskullHTTPClient
func NewGrayskullHTTPClient(authProvider auth.GrayskullAuthHeaderProvider, config *models.GrayskullClientConfiguration, logger *slog.Logger, metricsRecorder metrics.MetricsRecorder) GrayskullHTTPClientInterface {
	if logger == nil {
		logger = slog.Default()
	}
	transport := &http.Transport{
		MaxIdleConns:        config.MaxConnections,
		MaxIdleConnsPerHost: config.MaxConnections,
		IdleConnTimeout:     5 * time.Minute,
	}

	client := &http.Client{
		Timeout:   time.Duration(config.ReadTimeout) * time.Millisecond,
		Transport: transport,
	}

	retryConfig := utils.RetryConfig{
		MaxAttempts:   config.MaxRetries,
		InitialDelay:  time.Duration(config.MinRetryDelay) * time.Millisecond,
		MaxRetryDelay: 1 * time.Minute, // Default max retry delay
	}

	if metricsRecorder == nil {
		metricsRecorder = metrics.NewPrometheusRecorder()
	}

	return &GrayskullHTTPClient{
		httpClient:         client,
		authHeaderProvider: authProvider,
		retryUtil:          utils.NewRetryUtil(retryConfig),
		logger:             logger,
		metricsRecorder:    metricsRecorder,
	}
}

// DoGetWithRetry performs a GET request with retry logic
func (c *GrayskullHTTPClient) DoGetWithRetry(ctx context.Context, url string) (*response.HttpResponse, error) {
	var attemptCount int32 // Use atomic for concurrent safety

	result, err := c.retryUtil.Retry(ctx, func() (interface{}, error) {
		atomic.AddInt32(&attemptCount, 1)
		return c.doGet(ctx, url)
	})

	if err != nil {
		// Check if it's a retryable error that exhausted all attempts
		var retryableErr *exceptions.RetryableError
		if errors.As(err, &retryableErr) && atomic.LoadInt32(&attemptCount) > 1 {
			c.metricsRecorder.RecordRetry(url, int(atomic.LoadInt32(&attemptCount)), false)
		}
		return nil, fmt.Errorf("failed after %d attempts: %w", attemptCount, err)
	}

	// Safe to assert type since doGet always returns *response.HttpResponse
	httpResp := result.(*response.HttpResponse)

	if attemptCount > 1 {
		c.metricsRecorder.RecordRetry(url, int(attemptCount), true)
	}

	return httpResp, nil
}

// doGet performs a single GET request without retries
func (c *GrayskullHTTPClient) doGet(ctx context.Context, url string) (*response.HttpResponse, error) {
	startTime := time.Now()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return nil, fmt.Errorf("failed to create request: %w", err)
	}

	// Add auth header
	authHeader, err := c.authHeaderProvider.GetAuthHeader()
	if err != nil {
		return nil, fmt.Errorf("failed to get auth header: %w", err)
	}
	if authHeader == "" {
		return nil, errors.New("auth header cannot be empty")
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
		return nil, exceptions.NewRetryableErrorWithCause("HTTP request failed", err)
	}
	defer resp.Body.Close()

	// Read response body
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, fmt.Errorf("failed to read response body: %w", err)
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
			return nil, exceptions.NewRetryableErrorWithStatus(resp.StatusCode, errMsg)
		}
		return nil, exceptions.NewGrayskullError(resp.StatusCode, errMsg)
	}

	// Record the request metrics
	c.metricsRecorder.RecordRequest(url, resp.StatusCode, time.Since(startTime))

	headers := make(map[string]string)
	contentType := resp.Header.Get("Content-Type")
	if contentType == "" {
		contentType = "unknown"
	}
	headers["Content-Type"] = contentType

	return &response.HttpResponse{
		StatusCode:  resp.StatusCode,
		Body:        string(body),
		ContentType: contentType,
		Protocol:    resp.Proto,
	}, nil
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
