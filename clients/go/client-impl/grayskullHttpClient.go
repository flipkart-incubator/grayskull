package client

import (
	"context"
	"crypto/tls"
	"errors"
	"io"
	"log/slog"
	"net"
	"net/http"
	"os"
	"time"

	"github.com/grayskull/go-client/client-impl/auth"
	"github.com/grayskull/go-client/client-impl/constants"
	"github.com/grayskull/go-client/client-impl/metrics"
	"github.com/grayskull/go-client/client-impl/models"
	"github.com/grayskull/go-client/client-impl/models/exceptions"
	"github.com/grayskull/go-client/client-impl/models/response"
	"github.com/grayskull/go-client/client-impl/utils"
	"golang.org/x/net/http2"
)

// GrayskullHttpClient handles HTTP communication with the Grayskull server
type GrayskullHttpClient struct {
	httpClient         *http.Client
	authHeaderProvider auth.GrayskullAuthHeaderProvider
	retryUtil          *utils.RetryUtil
	logger             *slog.Logger
	metricsRecorder    *metrics.Recorder
}

// NewGrayskullHttpClient creates a new instance of GrayskullHttpClient
func NewGrayskullHttpClient(authProvider auth.GrayskullAuthHeaderProvider, config *models.GrayskullClientConfiguration, logger *slog.Logger) *GrayskullHttpClient {
	if logger == nil {
		logger = slog.Default()
	}

	var metricsRecorder *metrics.Recorder
	if config != nil && config.MetricsEnabled() {
		recorder := metrics.NewPrometheusRecorder(metrics.PrometheusRecorderConfig{
			Namespace: "grayskull",
			Subsystem: "client",
		})
		// Convert to interface type and take its address
		var rec metrics.Recorder = recorder
		metricsRecorder = &rec
	}

	tlsConfig := &tls.Config{
		MinVersion:         tls.VersionTLS12, // Enforce minimum TLS 1.2
		InsecureSkipVerify: config.InsecureSkipVerify(),
	}
	// Configure base HTTP transport
	baseTransport := &http.Transport{
		MaxIdleConns:          int(config.MaxConnections()),
		IdleConnTimeout:       5 * time.Minute,
		TLSHandshakeTimeout:   10 * time.Second,
		ResponseHeaderTimeout: time.Duration(config.ReadTimeout()) * time.Millisecond,
		ForceAttemptHTTP2:     true,
		TLSClientConfig:       tlsConfig,
	}

	// Enable HTTP/2
	if err := http2.ConfigureTransport(baseTransport); err != nil {
		logger.Error("failed to configure HTTP/2 transport", "error", err)
		// Continue without HTTP/2 rather than failing completely
	}

	// Create transport with the appropriate metrics recorder
	transportRecorder := metrics.DefaultRecorder
	if metricsRecorder != nil {
		transportRecorder = *metricsRecorder
	}

	// Wrap with metrics transport
	metricsTransport := metrics.NewTransport(baseTransport, transportRecorder)

	client := &http.Client{
		Transport: metricsTransport,
		Timeout:   time.Duration(config.ConnectionTimeout()) * time.Millisecond,
	}

	retryUtil := utils.NewRetryUtil(
		int(config.MaxRetries()),
		time.Duration(config.MinRetryDelay())*time.Millisecond,
	)

	return &GrayskullHttpClient{
		httpClient:         client,
		authHeaderProvider: authProvider,
		retryUtil:          retryUtil,
		logger:             logger,
		metricsRecorder:    metricsRecorder,
	}
}

// DoGetWithRetry executes a GET request with retry logic
func (c *GrayskullHttpClient) DoGetWithRetry(ctx context.Context, url string) (*response.HttpResponse, error) {
	var (
		attemptCount int
		lastResponse *response.HttpResponse
		lastError    error
	)

	// Execute with retry logic
	_, err := c.retryUtil.Retry(ctx, func() (interface{}, error) {
		attemptCount++
		if attemptCount > 1 {
			c.logger.DebugContext(ctx, "Retrying request",
				"url", url,
				"attempt", attemptCount,
			)
		}

		resp, err := c.doGet(ctx, url)
		if err == nil {
			lastResponse = resp
			return resp, nil
		}

		// Check if error is retryable
		if retryable, ok := err.(*exceptions.RetryableError); ok {
			lastError = retryable
			return nil, retryable
		}

		// Handle GrayskullError as non-retryable
		if grayskullErr, ok := err.(*exceptions.GrayskullError); ok {
			lastError = grayskullErr
			return nil, grayskullErr
		}

		// For all other errors, wrap as retryable
		lastError = exceptions.NewRetryableErrorWithStatusAndCause(500, "request failed", err)
		return nil, lastError
	})

	if err != nil {
		return nil, err // Return the error from RetryUtil.Retry() to preserve retry context
	}

	return lastResponse, nil
}

// doGet executes a single GET request
func (c *GrayskullHttpClient) doGet(ctx context.Context, url string) (*response.HttpResponse, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return nil, exceptions.NewRetryableErrorWithStatusAndCause(0, "failed to create request", err)
	}

	// Add headers
	if err := c.addHeaders(ctx, req); err != nil {
		return nil, err
	}

	c.logger.DebugContext(ctx, "Executing GET request",
		"url", url,
	)

	// Execute request
	resp, err := c.httpClient.Do(req)
	if err != nil {
		// Check for timeout/deadline errors
		if errors.Is(err, context.DeadlineExceeded) || os.IsTimeout(err) {
			return nil, exceptions.NewRetryableErrorWithStatusAndCause(500, "timeout while communicating with Grayskull server", err)
		}
		// Check for temporary network errors (often retryable)
		var netErr net.Error
		if errors.As(err, &netErr) && netErr.Timeout() {
			return nil, exceptions.NewRetryableErrorWithStatusAndCause(500, "timeout while communicating with Grayskull server", err)
		}
		return nil, exceptions.NewRetryableErrorWithStatusAndCause(500, "error communicating with Grayskull server", err)
	}
	defer resp.Body.Close()

	// Read response body
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, exceptions.NewRetryableErrorWithStatusAndCause(500, "failed to read response body", err)
	}

	// Check for error status codes
	if !isSuccessStatus(resp.StatusCode) {
		errMsg := string(body)
		if isRetryableStatus(resp.StatusCode) {
			return nil, exceptions.NewRetryableErrorWithStatus(resp.StatusCode, "request failed: "+errMsg)
		}
		return nil, exceptions.NewGrayskullError(resp.StatusCode, "request failed: "+errMsg)
	}

	c.logger.DebugContext(ctx, "Received response",
		"url", url,
		"statusCode", resp.StatusCode,
		"contentType", resp.Header.Get("Content-Type"),
		"protocol", resp.Proto,
		"bodyLength", len(body),
	)

	return response.NewHttpResponse(
		resp.StatusCode,
		string(body),
		resp.Header.Get("Content-Type"),
		resp.Proto,
	), nil
}

// addHeaders adds required headers to the request
func (c *GrayskullHttpClient) addHeaders(ctx context.Context, req *http.Request) error {
	// Add auth header
	authHeader, err := c.authHeaderProvider.GetAuthHeader()
	if err != nil {
		return exceptions.NewGrayskullErrorWithCause(401, "failed to get auth header", err)
	}
	req.Header.Add("Authorization", authHeader)

	// Add request ID if available
	if requestID, ok := ctx.Value(constants.GrayskullRequestID).(string); ok && requestID != "" {
		req.Header.Add("X-Request-Id", requestID)
	}

	return nil
}

// isSuccessStatus checks if the status code indicates a successful response
func isSuccessStatus(statusCode int) bool {
	return statusCode >= 200 && statusCode < 300
}

// isRetryableStatus checks if the status code indicates a retryable error
func isRetryableStatus(statusCode int) bool {
	return statusCode == 429 || (statusCode >= 500 && statusCode < 600)
}
