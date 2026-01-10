package client_impl

import (
	"context"
	"errors"
	"fmt"
	"github.com/grayskull/client_impl/auth"
	"github.com/grayskull/client_impl/metrics"
	"io"
	"log/slog"
	"net"
	"net/http"
	"time"

	"github.com/grayskull/client_impl/constants"
	"github.com/grayskull/client_impl/models"
	"github.com/grayskull/client_impl/models/exceptions"
	"github.com/grayskull/client_impl/models/response"
	"github.com/grayskull/client_impl/utils"
)

// GrayskullHTTPClient is the HTTP client for making requests to the Grayskull service
type GrayskullHTTPClient struct {
	client       *http.Client
	authProvider auth.GrayskullAuthHeaderProvider
	retryUtil    *utils.RetryUtil
	logger       *slog.Logger
	metrics      *metrics.Metrics
}

// Do makes an HTTP request
func (c *GrayskullHTTPClient) Do(req *http.Request) (*http.Response, error) {
	return c.client.Do(req)
}

// NewGrayskullHTTPClient creates a new instance of GrayskullHTTPClient
func NewGrayskullHTTPClient(authProvider auth.GrayskullAuthHeaderProvider, config *models.GrayskullClientConfiguration, logger *slog.Logger) (*GrayskullHTTPClient, error) {
	if authProvider == nil {
		return nil, fmt.Errorf("authProvider cannot be nil")
	}
	if config == nil {
		return nil, fmt.Errorf("config cannot be nil")
	}
	if logger == nil {
		logger = slog.Default()
	}

	transport := &http.Transport{
		MaxIdleConns:        config.MaxConnections,
		MaxIdleConnsPerHost: config.MaxConnections,
		IdleConnTimeout:     5 * time.Minute,
		DialContext: (&net.Dialer{
			Timeout: config.ConnectionTimeout,
		}).DialContext,
	}

	client := &http.Client{
		Transport: transport,
		Timeout:   config.ReadTimeout,
	}

	return &GrayskullHTTPClient{
		client:       client,
		authProvider: authProvider,
		retryUtil:    utils.NewRetryUtil(config.MaxRetries, config.MinRetryDelay, logger),
		logger:       logger,
		metrics:      metrics.GetMetrics(),
	}, nil
}

// DoGetWithRetry performs an HTTP GET request with retry logic
func (c *GrayskullHTTPClient) DoGetWithRetry(ctx context.Context, url string) (*response.HttpResponse, error) {
	startTime := time.Now()
	var attemptCount int

	var lastResp *response.HttpResponse

	result, err := c.retryUtil.Retry(ctx, func() (interface{}, error) {
		attemptCount++
		resp, err := c.doGet(ctx, url)
		if err == nil {
			lastResp = resp
		} else {
			return nil, err
		}
		return resp, err
	})

	// Record metrics for the request
	duration := time.Since(startTime).Seconds()
	statusCode := 0
	if lastResp != nil {
		statusCode = lastResp.StatusCode
	}

	// Record request metrics
	c.recordHTTPMetrics(ctx, "GET", url, statusCode, duration, attemptCount, err)

	if err != nil {
		return nil, err
	}

	// Safe type assertion for the result
	httpResp, ok := result.(*response.HttpResponse)
	if !ok {
		errMsg := fmt.Sprintf("unexpected response type from retry: %T", result)
		c.recordHTTPMetrics(ctx, "GET", url, 500, duration, attemptCount, errors.New(errMsg))
		return nil, exceptions.NewGrayskullError(500, errMsg)
	}

	return httpResp, nil
}

// recordHTTPMetrics records metrics for HTTP requests
func (c *GrayskullHTTPClient) recordHTTPMetrics(ctx context.Context, method, path string, statusCode int, duration float64, attempts int, err error) {
	// Record request duration
	status := "success"
	if err != nil {
		status = "error"
	}

	// Record HTTP request metrics
	c.metrics.ObserveHTTPRequestDuration(method, path, fmt.Sprintf("%d", statusCode), duration)
	c.metrics.IncHTTPRequestTotal(method, path, fmt.Sprintf("%d", statusCode))

	// Record retry metrics if there were retries
	if attempts > 1 {
		c.metrics.IncHTTPRequestTotal("retry", fmt.Sprintf("attempts_%d", attempts), status)
	}

	// Log the request
	c.logger.DebugContext(ctx, "HTTP request completed",
		"method", method,
		"path", path,
		"status", statusCode,
		"duration_seconds", duration,
		"attempts", attempts,
	)
}

// doGet performs a single HTTP GET request
func (c *GrayskullHTTPClient) doGet(ctx context.Context, url string) (*response.HttpResponse, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return nil, exceptions.NewGrayskullErrorWithCause(0, "failed to create request", err)
	}

	// Set request headers
	if err := c.setRequestHeaders(req, ctx); err != nil {
		return nil, exceptions.NewGrayskullErrorWithCause(500, "failed to set request headers", err)
	}

	c.logger.DebugContext(ctx, "executing GET request",
		"url", url,
	)

	resp, err := c.client.Do(req)
	if err != nil {
		if isTimeoutError(err) {
			return nil, exceptions.NewRetryableErrorWithStatusAndCause(500, "request timeout", err)
		}
		return nil, exceptions.NewRetryableErrorWithStatusAndCause(500, "failed to execute request", err)
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, exceptions.NewRetryableErrorWithStatusAndCause(500, "failed to read response body", err)
	}

	c.logger.DebugContext(ctx, "received response",
		"status", resp.Status,
		"url", url,
		"content_length", len(body),
	)

	if resp.StatusCode >= 400 {
		errMsg := fmt.Sprintf("request failed with status %d: %s", resp.StatusCode, string(body))
		if isRetryableStatusCode(resp.StatusCode) {
			return nil, exceptions.NewRetryableErrorWithStatus(resp.StatusCode, errMsg)
		}
		return nil, exceptions.NewGrayskullErrorWithCause(resp.StatusCode, errMsg, nil)
	}

	return &response.HttpResponse{
		StatusCode:  resp.StatusCode,
		Body:        string(body),
		ContentType: resp.Header.Get("Content-Type"),
		Protocol:    resp.Proto,
	}, nil
}

// setRequestHeaders sets the required headers for Grayskull API requests
func (c *GrayskullHTTPClient) setRequestHeaders(req *http.Request, ctx context.Context) error {
	authHeader, err := c.authProvider.GetAuthHeader()
	if err != nil {
		return fmt.Errorf("failed to get auth header: %w", err)
	}
	req.Header.Set("Authorization", authHeader)

	// Add request ID from context if available
	if requestID, ok := ctx.Value(constants.GrayskullRequestID).(string); ok && requestID != "" {
		req.Header.Set("X-Request-Id", requestID)
	}
	return nil
}

// isRetryableStatusCode checks if the HTTP status code indicates a retryable error
func isRetryableStatusCode(statusCode int) bool {
	return statusCode == http.StatusTooManyRequests ||
		(statusCode >= http.StatusInternalServerError && statusCode < 600)
}

// isTimeoutError checks if the error is a timeout error
func isTimeoutError(err error) bool {
	timeoutErr, ok := err.(interface{ Timeout() bool })
	return ok && timeoutErr.Timeout()
}

// Close releases all resources used by the HTTP client.
// It should be called when the client is no longer needed to prevent resource leaks.
// This is safe to call multiple times.
func (c *GrayskullHTTPClient) Close() error {
	if c == nil || c.client == nil {
		return nil
	}

	// Close idle connections for any transport that supports it
	if transport, ok := c.client.Transport.(interface{ CloseIdleConnections() }); ok {
		transport.CloseIdleConnections()
	}

	// If the transport also implements io.Closer, call Close()
	if closer, ok := c.client.Transport.(interface{ Close() error }); ok {
		return closer.Close()
	}

	return nil
}
