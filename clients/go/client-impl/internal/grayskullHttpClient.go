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

	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/auth"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/constants"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/internal/utils"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/metrics"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/models"
	grayskullErrors "github.com/flipkart-incubator/grayskull/clients/go/client-impl/models/errors"
)

// GrayskullHTTPClientInterface is the HTTP client surface used by the SDK.
type GrayskullHTTPClientInterface interface {
	DoPostWithRetry(ctx context.Context, url string, jsonBody []byte, result any) (int, error)
	DoGetWithRetry(ctx context.Context, url string, result any) (int, error)
	Close() error
}

// GrayskullHTTPClient is the HTTP client with built-in retry and error handling.
type GrayskullHTTPClient struct {
	httpClient         *http.Client
	authHeaderProvider auth.GrayskullAuthHeaderProvider
	retryConfig        utils.RetryConfig
	logger             *slog.Logger
	metricsRecorder    metrics.MetricsRecorder
	customHeaders      map[string]string
}

// NewGrayskullHTTPClient constructs a GrayskullHTTPClient.
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
		// Total deadline = ConnectionTimeout + ReadTimeout.
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
		retryConfig:        retryConfig,
		logger:             logger,
		metricsRecorder:    metricsRecorder,
		customHeaders:      config.GetDefaultHeaders(),
	}
}

// httpResponse carries response data through the retry wrapper.
type httpResponse struct {
	Body       string
	StatusCode int
}

// DoGetWithRetry performs a GET with retries and unmarshals into result
// (must be a pointer).
func (c *GrayskullHTTPClient) DoGetWithRetry(ctx context.Context, url string, result any) (int, error) {
	return c.doWithRetry(ctx, http.MethodGet, url, nil, result)
}

// DoPostWithRetry performs a POST with retries. Unmarshals into result when
// result != nil and body != "" (callers ignoring the body can pass nil).
func (c *GrayskullHTTPClient) DoPostWithRetry(ctx context.Context, url string, jsonBody []byte, result any) (int, error) {
	return c.doWithRetry(ctx, http.MethodPost, url, jsonBody, result)
}

// doWithRetry is the shared retry+unmarshal wrapper. GET always unmarshals;
// POST only when result != nil and body != "".
func (c *GrayskullHTTPClient) doWithRetry(ctx context.Context, method, url string, body []byte, result any) (int, error) {
	var attemptCount int

	httpResp, err := utils.Retry(ctx, c.retryConfig, func() (httpResponse, error) {
		attemptCount++
		respBody, status, err := c.doRequest(ctx, method, url, body)
		if err != nil {
			return httpResponse{StatusCode: status}, err
		}
		return httpResponse{Body: respBody, StatusCode: status}, nil
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

	shouldUnmarshal := result != nil
	if method == http.MethodPost && httpResp.Body == "" {
		shouldUnmarshal = false
	}
	if shouldUnmarshal {
		if err := json.Unmarshal([]byte(httpResp.Body), result); err != nil {
			return httpResp.StatusCode, fmt.Errorf("failed to unmarshal response: %w", err)
		}
	}
	return httpResp.StatusCode, nil
}

// applyHeaders writes custom headers first, then SDK headers (Authorization,
// X-Request-Id) so the SDK always wins on conflict.
func (c *GrayskullHTTPClient) applyHeaders(ctx context.Context, req *http.Request) error {
	for k, v := range c.customHeaders {
		req.Header.Set(k, v)
	}

	authHeader, err := c.authHeaderProvider.GetAuthHeader()
	if err != nil {
		return fmt.Errorf("failed to get auth header: %w", err)
	}
	if authHeader == "" {
		return errors.New("auth header cannot be empty")
	}
	req.Header.Set("Authorization", authHeader)

	if requestID := ctx.Value(constants.GrayskullRequestID); requestID != nil {
		req.Header.Set("X-Request-Id", fmt.Sprintf("%v", requestID))
	}
	return nil
}

// doRequest performs a single HTTP request (no retries). For POST,
// jsonBody is the body and Content-Type is set to application/json. For
// GET, jsonBody is ignored.
func (c *GrayskullHTTPClient) doRequest(ctx context.Context, method, url string, jsonBody []byte) (string, int, error) {
	startTime := time.Now()

	var reqBody io.Reader
	if method == http.MethodPost {
		reqBody = bytes.NewReader(jsonBody)
	}

	req, err := http.NewRequestWithContext(ctx, method, url, reqBody)
	if err != nil {
		return "", 0, fmt.Errorf("failed to create request: %w", err)
	}
	if method == http.MethodPost {
		req.Header.Set("Content-Type", "application/json; charset=utf-8")
	}
	if err := c.applyHeaders(ctx, req); err != nil {
		return "", 0, err
	}

	logAttrs := []any{"url", url, "method", method}
	if method == http.MethodPost {
		logAttrs = append(logAttrs, "body_length", len(jsonBody))
	}
	c.logger.DebugContext(ctx, "Executing HTTP request", logAttrs...)

	resp, err := c.httpClient.Do(req)
	if err != nil {
		// Network-level failures (dial/transport/EOF) are retryable.
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

	if resp.StatusCode >= http.StatusBadRequest {
		errMsg := fmt.Sprintf("request failed with status %d: %s", resp.StatusCode, string(respBody))
		if c.metricsRecorder != nil {
			c.metricsRecorder.RecordRequest(url, resp.StatusCode, time.Since(startTime))
		}
		if isRetryableStatusCode(resp.StatusCode) {
			return "", resp.StatusCode, grayskullErrors.NewRetryableErrorWithStatus(resp.StatusCode, errMsg)
		}
		return "", resp.StatusCode, grayskullErrors.NewGrayskullError(resp.StatusCode, errMsg)
	}

	c.metricsRecorder.RecordRequest(url, resp.StatusCode, time.Since(startTime))
	return string(respBody), resp.StatusCode, nil
}

// isRetryableStatusCode returns true for 429 and 5xx.
func isRetryableStatusCode(statusCode int) bool {
	return statusCode == http.StatusTooManyRequests || (statusCode >= http.StatusInternalServerError && statusCode < 600)
}

// Close closes idle HTTP connections.
func (c *GrayskullHTTPClient) Close() error {
	if transport, ok := c.httpClient.Transport.(*http.Transport); ok {
		transport.CloseIdleConnections()
	}
	return nil
}
