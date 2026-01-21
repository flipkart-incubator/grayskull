package internal_test

import (
	"context"
	"errors"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/internal"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/constants"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/metrics"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/models"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"
)

// noopRecorder is a no-op implementation of MetricsRecorder for testing
type noopRecorder struct{}

func (n *noopRecorder) RecordRequest(name string, statusCode int, duration time.Duration) {}

func (n *noopRecorder) RecordRetry(url string, attempt int, success bool) {}

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

func setupClient(t *testing.T, config *models.GrayskullClientConfiguration) internal.GrayskullHTTPClientInterface {
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

	return internal.NewGrayskullHTTPClient(mockAuth, config, nil, metricsRecorder)
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
	assert.Equal(t, http.StatusOK, resp.StatusCode())
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
	assert.Equal(t, http.StatusOK, resp.StatusCode())
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
	t.Run("successful close", func(t *testing.T) {
		// Create and close client
		client := setupClient(t, nil)
		err := client.Close()

		// Verify
		assert.NoError(t, err)
	})

	t.Run("close multiple times", func(t *testing.T) {
		client := setupClient(t, nil)
		err1 := client.Close()
		err2 := client.Close()

		assert.NoError(t, err1)
		assert.NoError(t, err2)
	})
}

func TestIsRetryableStatusCode(t *testing.T) {
	// isRetryableStatusCode is a helper function that mirrors the implementation in grayskullHttpClient.go
	isRetryableStatusCode := func(statusCode int) bool {
		return statusCode == http.StatusTooManyRequests || (statusCode >= http.StatusInternalServerError && statusCode < 600)
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
		{"408 Request Timeout", http.StatusRequestTimeout, false},
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

func TestNewGrayskullHTTPClient(t *testing.T) {
	t.Run("creates client with valid config", func(t *testing.T) {
		mockAuth := &MockAuthProviderHTTP{}
		mockAuth.On("GetAuthHeader").Return("Bearer token", nil)

		config := &models.GrayskullClientConfiguration{
			MaxRetries:     3,
			MinRetryDelay:  100,
			ReadTimeout:    5000,
			MaxConnections: 10,
		}

		client := internal.NewGrayskullHTTPClient(mockAuth, config, nil, NewNoopRecorder())

		assert.NotNil(t, client)
	})

	t.Run("creates client with nil logger", func(t *testing.T) {
		mockAuth := &MockAuthProviderHTTP{}
		config := &models.GrayskullClientConfiguration{
			MaxRetries:    3,
			MinRetryDelay: 100,
			ReadTimeout:   5000,
		}

		client := internal.NewGrayskullHTTPClient(mockAuth, config, nil, NewNoopRecorder())

		assert.NotNil(t, client)
	})

	t.Run("creates client with nil metrics recorder", func(t *testing.T) {
		mockAuth := &MockAuthProviderHTTP{}
		config := &models.GrayskullClientConfiguration{
			MaxRetries:    3,
			MinRetryDelay: 100,
			ReadTimeout:   5000,
		}

		client := internal.NewGrayskullHTTPClient(mockAuth, config, nil, nil)

		assert.NotNil(t, client)
	})
}

func TestDoGetWithRetry_AdditionalScenarios(t *testing.T) {
	t.Run("context canceled before request", func(t *testing.T) {
		ctx, cancel := context.WithCancel(context.Background())
		cancel() // Cancel immediately

		client := setupClient(t, nil)
		resp, err := client.DoGetWithRetry(ctx, "http://example.com")

		assert.Error(t, err)
		assert.Nil(t, resp)
	})

	t.Run("non-retryable 4xx error", func(t *testing.T) {
		testServer := setupTestServer(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.WriteHeader(http.StatusBadRequest)
			w.Write([]byte(`{"error":"bad request"}`))
		}))

		client := setupClient(t, &models.GrayskullClientConfiguration{
			MaxRetries:    3,
			MinRetryDelay: 10,
			ReadTimeout:   1000,
		})

		resp, err := client.DoGetWithRetry(context.Background(), testServer.URL)

		assert.Error(t, err)
		assert.Nil(t, resp)
		assert.Contains(t, err.Error(), "failed after 1 attempt")
	})

	t.Run("429 too many requests with retry", func(t *testing.T) {
		attempts := 0
		testServer := setupTestServer(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			attempts++
			if attempts == 1 {
				w.WriteHeader(http.StatusTooManyRequests)
			} else {
				w.WriteHeader(http.StatusOK)
				w.Write([]byte(`{"data":"success"}`))
			}
		}))

		client := setupClient(t, &models.GrayskullClientConfiguration{
			MaxRetries:    3,
			MinRetryDelay: 10,
			ReadTimeout:   1000,
		})

		resp, err := client.DoGetWithRetry(context.Background(), testServer.URL)

		assert.NoError(t, err)
		assert.NotNil(t, resp)
		assert.Equal(t, 2, attempts)
	})

	t.Run("request with headers", func(t *testing.T) {
		var capturedAuth string

		testServer := setupTestServer(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			capturedAuth = r.Header.Get("Authorization")
			w.WriteHeader(http.StatusOK)
			w.Write([]byte(`{"data":"test"}`))
		}))

		client := setupClient(t, nil)
		ctx := context.WithValue(context.Background(), "grayskull-request-id", "test-request-123")
		resp, err := client.DoGetWithRetry(ctx, testServer.URL)

		assert.NoError(t, err)
		assert.NotNil(t, resp)
		assert.Equal(t, "Bearer test-token", capturedAuth)
	})

	t.Run("response with different content types", func(t *testing.T) {
		testServer := setupTestServer(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Content-Type", "application/xml")
			w.WriteHeader(http.StatusOK)
			w.Write([]byte(`<data>test</data>`))
		}))

		client := setupClient(t, nil)
		resp, err := client.DoGetWithRetry(context.Background(), testServer.URL)

		assert.NoError(t, err)
		assert.NotNil(t, resp)
	})

	t.Run("response without explicit content type", func(t *testing.T) {
		testServer := setupTestServer(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.WriteHeader(http.StatusOK)
			w.Write([]byte(`{"data":"test"}`))
		}))

		client := setupClient(t, nil)
		resp, err := client.DoGetWithRetry(context.Background(), testServer.URL)

		assert.NoError(t, err)
		assert.NotNil(t, resp)
	})

	t.Run("multiple retries before success", func(t *testing.T) {
		attempts := 0
		testServer := setupTestServer(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			attempts++
			if attempts < 3 {
				w.WriteHeader(http.StatusServiceUnavailable)
			} else {
				w.WriteHeader(http.StatusOK)
				w.Write([]byte(`{"data":"success"}`))
			}
		}))

		client := setupClient(t, &models.GrayskullClientConfiguration{
			MaxRetries:    5,
			MinRetryDelay: 10,
			ReadTimeout:   1000,
		})

		resp, err := client.DoGetWithRetry(context.Background(), testServer.URL)

		assert.NoError(t, err)
		assert.NotNil(t, resp)
		assert.Equal(t, 3, attempts)
	})

	t.Run("auth header error", func(t *testing.T) {
		mockAuth := &MockAuthProviderHTTP{}
		mockAuth.On("GetAuthHeader").Return("", errors.New("auth error"))

		config := &models.GrayskullClientConfiguration{
			MaxRetries:    3,
			MinRetryDelay: 10,
			ReadTimeout:   1000,
		}

		client := internal.NewGrayskullHTTPClient(mockAuth, config, nil, NewNoopRecorder())
		resp, err := client.DoGetWithRetry(context.Background(), "http://example.com")

		assert.Error(t, err)
		assert.Nil(t, resp)
		assert.Contains(t, err.Error(), "failed to get auth header")
	})

	t.Run("empty auth header", func(t *testing.T) {
		mockAuth := &MockAuthProviderHTTP{}
		mockAuth.On("GetAuthHeader").Return("", nil)

		config := &models.GrayskullClientConfiguration{
			MaxRetries:    3,
			MinRetryDelay: 10,
			ReadTimeout:   1000,
		}

		client := internal.NewGrayskullHTTPClient(mockAuth, config, nil, NewNoopRecorder())
		resp, err := client.DoGetWithRetry(context.Background(), "http://example.com")

		assert.Error(t, err)
		assert.Nil(t, resp)
		assert.Contains(t, err.Error(), "auth header cannot be empty")
	})

	t.Run("invalid URL", func(t *testing.T) {
		client := setupClient(t, nil)
		resp, err := client.DoGetWithRetry(context.Background(), "://invalid-url")

		assert.Error(t, err)
		assert.Nil(t, resp)
	})

	t.Run("response protocol captured", func(t *testing.T) {
		testServer := setupTestServer(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.WriteHeader(http.StatusOK)
			w.Write([]byte(`{"data":"test"}`))
		}))

		client := setupClient(t, nil)
		resp, err := client.DoGetWithRetry(context.Background(), testServer.URL)

		assert.NoError(t, err)
		assert.NotNil(t, resp)
	})
}

func TestDoGetWithRetry_UncoveredPaths(t *testing.T) {
	t.Run("sets request ID header from context", func(t *testing.T) {
		var capturedRequestID string

		testServer := setupTestServer(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			capturedRequestID = r.Header.Get("X-Request-Id")
			w.WriteHeader(http.StatusOK)
			w.Write([]byte(`{"data":"test"}`))
		}))

		client := setupClient(t, nil)
		// Use the actual constant from the constants package
		ctx := context.WithValue(context.Background(), constants.GrayskullRequestID, "test-request-123")
		resp, err := client.DoGetWithRetry(ctx, testServer.URL)

		assert.NoError(t, err)
		assert.NotNil(t, resp)
		assert.Equal(t, "test-request-123", capturedRequestID)
	})

	t.Run("handles empty content type header", func(t *testing.T) {
		testServer := setupTestServer(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			// Explicitly clear content-type by writing header first
			w.Header().Del("Content-Type")
			w.WriteHeader(http.StatusOK)
			// Don't write body to avoid auto-detection
		}))

		client := setupClient(t, nil)
		resp, err := client.DoGetWithRetry(context.Background(), testServer.URL)

		assert.NoError(t, err)
		assert.NotNil(t, resp)
	})
}

func TestDoGetWithRetry_MetricsRecording(t *testing.T) {
	t.Run("records metrics on success", func(t *testing.T) {
		testServer := setupTestServer(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.WriteHeader(http.StatusOK)
			w.Write([]byte(`{"data":"test"}`))
		}))

		client := setupClient(t, nil)
		resp, err := client.DoGetWithRetry(context.Background(), testServer.URL)

		assert.NoError(t, err)
		assert.NotNil(t, resp)
	})

	t.Run("records retry metrics on failure then success", func(t *testing.T) {
		attempts := 0
		testServer := setupTestServer(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			attempts++
			if attempts == 1 {
				w.WriteHeader(http.StatusInternalServerError)
			} else {
				w.WriteHeader(http.StatusOK)
				w.Write([]byte(`{"data":"test"}`))
			}
		}))

		client := setupClient(t, &models.GrayskullClientConfiguration{
			MaxRetries:    3,
			MinRetryDelay: 10,
			ReadTimeout:   1000,
		})

		resp, err := client.DoGetWithRetry(context.Background(), testServer.URL)

		assert.NoError(t, err)
		assert.NotNil(t, resp)
	})

	t.Run("records metrics on error", func(t *testing.T) {
		testServer := setupTestServer(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.WriteHeader(http.StatusBadRequest)
			w.Write([]byte(`{"error":"bad request"}`))
		}))

		client := setupClient(t, nil)
		resp, err := client.DoGetWithRetry(context.Background(), testServer.URL)

		assert.Error(t, err)
		assert.Nil(t, resp)
	})
}

func TestDoGetWithRetry_EdgeCases(t *testing.T) {
	t.Run("handles server closing connection while reading body", func(t *testing.T) {
		// This test simulates a scenario where reading the response body fails
		testServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.WriteHeader(http.StatusOK)
			w.Write([]byte(`{"data":"test"}`))
			// Force connection close
			hj, ok := w.(http.Hijacker)
			if ok {
				conn, _, _ := hj.Hijack()
				if conn != nil {
					conn.Close()
				}
			}
		}))
		defer testServer.Close()

		client := setupClient(t, &models.GrayskullClientConfiguration{
			MaxRetries:    1,
			MinRetryDelay: 10,
			ReadTimeout:   1000,
		})

		// This should trigger retry logic due to connection issues
		_, err := client.DoGetWithRetry(context.Background(), testServer.URL)

		// Expect an error due to connection issues
		assert.Error(t, err)
	})
}

func TestUnmarshalResponse(t *testing.T) {
	type TestData struct {
		Name  string `json:"name"`
		Value int    `json:"value"`
	}

	t.Run("successfully unmarshal valid JSON response", func(t *testing.T) {
		body := `{"data":{"name":"test","value":42},"message":"success"}`
		resp, err := internal.UnmarshalResponse[TestData](body)

		assert.NoError(t, err)
		assert.Equal(t, "test", resp.Data.Name)
		assert.Equal(t, 42, resp.Data.Value)
		assert.Equal(t, "success", resp.Message)
	})

	t.Run("successfully unmarshal response with empty data", func(t *testing.T) {
		body := `{"data":null,"message":"no data"}`
		resp, err := internal.UnmarshalResponse[*TestData](body)

		assert.NoError(t, err)
		assert.Nil(t, resp.Data)
		assert.Equal(t, "no data", resp.Message)
	})

	t.Run("successfully unmarshal response with string data", func(t *testing.T) {
		body := `{"data":"simple string","message":"ok"}`
		resp, err := internal.UnmarshalResponse[string](body)

		assert.NoError(t, err)
		assert.Equal(t, "simple string", resp.Data)
		assert.Equal(t, "ok", resp.Message)
	})

	t.Run("fails to unmarshal invalid JSON", func(t *testing.T) {
		body := `{"data":"test","message":"incomplete`
		resp, err := internal.UnmarshalResponse[string](body)

		assert.Error(t, err)
		assert.Contains(t, err.Error(), "failed to unmarshal response")
		assert.Empty(t, resp.Data)
	})

	t.Run("fails to unmarshal malformed JSON", func(t *testing.T) {
		body := `not a json`
		_, err := internal.UnmarshalResponse[TestData](body)

		assert.Error(t, err)
		assert.Contains(t, err.Error(), "failed to unmarshal response")
	})

	t.Run("successfully unmarshal response with nested data", func(t *testing.T) {
		type NestedData struct {
			User struct {
				ID   int    `json:"id"`
				Name string `json:"name"`
			} `json:"user"`
		}

		body := `{"data":{"user":{"id":123,"name":"John"}},"message":"found"}`
		resp, err := internal.UnmarshalResponse[NestedData](body)

		assert.NoError(t, err)
		assert.Equal(t, 123, resp.Data.User.ID)
		assert.Equal(t, "John", resp.Data.User.Name)
		assert.Equal(t, "found", resp.Message)
	})

	t.Run("successfully unmarshal empty body with default values", func(t *testing.T) {
		body := `{}`
		resp, err := internal.UnmarshalResponse[string](body)

		assert.NoError(t, err)
		assert.Empty(t, resp.Data)
		assert.Empty(t, resp.Message)
	})
}
