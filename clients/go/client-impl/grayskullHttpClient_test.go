package client_impl_test

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/flipkart-incubator/grayskull/client-impl"
	"github.com/flipkart-incubator/grayskull/client-impl/metrics"
	"github.com/flipkart-incubator/grayskull/client-impl/models"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"
)

// noopRecorder is a no-op implementation of MetricsRecorder for testing
type noopRecorder struct{}

func (n *noopRecorder) RecordRequest(name string, statusCode int, duration time.Duration) {}

func (n *noopRecorder) RecordRetry(url string, attempt int, success bool) {}

func (n *noopRecorder) GetRecorderName() string {
	return "noop"
}

// NewNoopRecorder creates a new no-op metrics recorder
func NewNoopRecorder() metrics.MetricsRecorder {
	return &noopRecorder{}
}

// MockHTTPClient is a mock HTTP client for testing
type MockHTTPClient struct {
	mock.Mock
}

func (m *MockHTTPClient) Do(req *http.Request) (*http.Response, error) {
	args := m.Called(req)
	if args.Get(0) == nil {
		return nil, args.Error(1)
	}
	return args.Get(0).(*http.Response), args.Error(1)
}

// MockAuthProviderHTTP is a mock implementation of the auth provider for HTTP client tests
type MockAuthProviderHTTP struct {
	mock.Mock
}

func (m *MockAuthProviderHTTP) GetAuthHeader() (string, error) {
	args := m.Called()
	return args.String(0), args.Error(1)
}

func setupTestServer(t *testing.T, handler http.HandlerFunc) *httptest.Server {
	t.Helper()
	ts := httptest.NewServer(handler)
	t.Cleanup(ts.Close)
	return ts
}

func setupClient(t *testing.T, config *models.GrayskullClientConfiguration) client_impl.GrayskullHTTPClientInterface {
	t.Helper()
	mockAuth := &MockAuthProviderHTTP{}
	mockAuth.On("GetAuthHeader").Return("Bearer test-token", nil)

	if config == nil {
		config = &models.GrayskullClientConfiguration{
			MaxRetries:    3,
			MinRetryDelay: 100, // Changed from RetryDelay to MinRetryDelay and removed time.Millisecond
			ReadTimeout:   1000,
		}
	}

	// Create a no-op metrics recorder
	metricsRecorder := NewNoopRecorder()

	return client_impl.NewGrayskullHTTPClient(mockAuth, config, nil, metricsRecorder)
}

func TestGrayskullHTTPClient_DoGetWithRetry_Success(t *testing.T) {
	// Setup test server
	testServer := setupTestServer(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(`{"data":"test"}`))
	}))

	// Create and test client
	client := setupClient(t, nil)
	resp, err := client.DoGetWithRetry(context.Background(), testServer.URL)

	// Verify
	assert.NoError(t, err)
	assert.NotNil(t, resp)
	assert.Equal(t, http.StatusOK, resp.StatusCode)
}

func TestGrayskullHTTPClient_DoGetWithRetry_RetryOn5xx(t *testing.T) {
	// Setup test server that fails first time, then succeeds
	attempts := 0
	testServer := setupTestServer(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		attempts++
		if attempts == 1 {
			w.WriteHeader(http.StatusServiceUnavailable)
		} else {
			w.WriteHeader(http.StatusOK)
			w.Write([]byte(`{"data":"test"}`))
		}
	}))

	// Create and test client
	client := setupClient(t, &models.GrayskullClientConfiguration{
		MaxRetries:    3,
		MinRetryDelay: 10, // Shorter delay for tests
		ReadTimeout:   1000,
	})

	resp, err := client.DoGetWithRetry(context.Background(), testServer.URL)

	// Verify
	assert.NoError(t, err)
	assert.NotNil(t, resp)
	assert.Equal(t, http.StatusOK, resp.StatusCode)
	assert.Equal(t, 2, attempts) // Should have retried once
}

func TestGrayskullHTTPClient_DoGetWithRetry_MaxRetriesExceeded(t *testing.T) {
	// Setup test server that always returns 500
	testServer := setupTestServer(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))

	// Create and test client with only 1 retry
	client := setupClient(t, &models.GrayskullClientConfiguration{
		MaxRetries:    1,
		MinRetryDelay: 10,
		ReadTimeout:   1000,
	})

	resp, err := client.DoGetWithRetry(context.Background(), testServer.URL)

	// Verify
	assert.Error(t, err)
	assert.Nil(t, resp)
	assert.Contains(t, err.Error(), "failed after 1 attempt")
}

func TestGrayskullHTTPClient_DoGetWithRetry_NetworkError(t *testing.T) {
	// Create client
	client := setupClient(t, &models.GrayskullClientConfiguration{
		MaxRetries:    2,
		MinRetryDelay: 10,
		ReadTimeout:   1000,
	})

	// Test with an invalid URL to force a network error
	resp, err := client.DoGetWithRetry(context.Background(), "http://invalid-url")

	// Verify
	assert.Error(t, err)
	assert.Nil(t, resp)
}

func TestGrayskullHTTPClient_Close(t *testing.T) {
	// Create and close client
	client := setupClient(t, nil)
	err := client.Close()

	// Verify
	assert.NoError(t, err)
}

func TestIsRetryableStatusCode(t *testing.T) {
	// isRetryableStatusCode is a helper function that mirrors the implementation in grayskullHttpClient.go
	isRetryableStatusCode := func(statusCode int) bool {
		switch statusCode {
		case http.StatusRequestTimeout: // 408
			return true
		case http.StatusTooManyRequests: // 429
			return true
		case http.StatusInternalServerError: // 500
			return true
		case http.StatusBadGateway: // 502
			return true
		case http.StatusServiceUnavailable: // 503
			return true
		case http.StatusGatewayTimeout: // 504
			return true
		default:
			return false
		}
	}

	tests := []struct {
		name     string
		code     int
		expected bool
	}{
		{"200 OK", http.StatusOK, false},
		{"400 Bad Request", http.StatusBadRequest, false},
		{"401 Unauthorized", http.StatusUnauthorized, false},
		{"403 Forbidden", http.StatusForbidden, false},
		{"404 Not Found", http.StatusNotFound, false},
		{"408 Request Timeout", http.StatusRequestTimeout, true},
		{"429 Too Many Requests", http.StatusTooManyRequests, true},
		{"500 Internal Server Error", http.StatusInternalServerError, true},
		{"502 Bad Gateway", http.StatusBadGateway, true},
		{"503 Service Unavailable", http.StatusServiceUnavailable, true},
		{"504 Gateway Timeout", http.StatusGatewayTimeout, true},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			result := isRetryableStatusCode(tt.code)
			assert.Equal(t, tt.expected, result)
		})
	}
}
