package internal_test

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/constants"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/internal"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/metrics"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/models"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"
)

type noopRecorder struct{}

func (n *noopRecorder) RecordRequest(name string, statusCode int, duration time.Duration) {}
func (n *noopRecorder) RecordRetry(url string, attempt int, success bool)                 {}

func NewNoopRecorder() metrics.MetricsRecorder {
	return &noopRecorder{}
}

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
			MinRetryDelay: 100,
			ReadTimeout:   1000,
		}
	}

	return internal.NewGrayskullHTTPClient(mockAuth, config, nil, NewNoopRecorder())
}

type testResponse struct {
	Data string `json:"data"`
}

func TestGrayskullHTTPClient_E2E_BasicSuccess(t *testing.T) {
	testServer := setupTestServer(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(`{"data":"test"}`))
	}))

	client := setupClient(t, nil)
	var result testResponse
	statusCode, err := client.DoGetWithRetry(context.Background(), testServer.URL, &result)

	assert.NoError(t, err)
	assert.Equal(t, http.StatusOK, statusCode)
	assert.Equal(t, "test", result.Data)
}

func TestGrayskullHTTPClient_E2E_RetryOn5xxSuccess(t *testing.T) {
	attempts := 0
	testServer := setupTestServer(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		attempts++
		if attempts == 1 {
			w.WriteHeader(http.StatusServiceUnavailable)
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

	var result testResponse
	statusCode, err := client.DoGetWithRetry(context.Background(), testServer.URL, &result)

	assert.NoError(t, err)
	assert.Equal(t, http.StatusOK, statusCode)
	assert.Equal(t, "success", result.Data)
	assert.Equal(t, 2, attempts)
}

func TestGrayskullHTTPClient_E2E_MaxRetriesExceeded(t *testing.T) {
	testServer := setupTestServer(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
		w.Write([]byte(`{"error":"server error"}`))
	}))

	client := setupClient(t, &models.GrayskullClientConfiguration{
		MaxRetries:    1,
		MinRetryDelay: 10,
		ReadTimeout:   1000,
	})

	var result testResponse
	statusCode, err := client.DoGetWithRetry(context.Background(), testServer.URL, &result)

	assert.Error(t, err)
	// Status code will be 0 when all retries are exhausted and converted to GrayskullError
	assert.True(t, statusCode == 0 || statusCode == http.StatusInternalServerError)
	assert.Contains(t, err.Error(), "failed after 1 attempt")
}

func TestGrayskullHTTPClient_E2E_NetworkError(t *testing.T) {
	// Create a server that immediately closes connections to simulate network error
	testServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Hijack and close connection to simulate network failure
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
		MaxRetries:    2,
		MinRetryDelay: 10,
		ReadTimeout:   1000,
	})

	var result testResponse
	statusCode, err := client.DoGetWithRetry(context.Background(), testServer.URL, &result)

	assert.Error(t, err)
	assert.Equal(t, 0, statusCode)
}

func TestGrayskullHTTPClient_E2E_ContextCancellation(t *testing.T) {
	// Create a test server that would normally respond
	testServer := setupTestServer(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(`{"data":"test"}`))
	}))

	ctx, cancel := context.WithCancel(context.Background())
	cancel() // Cancel immediately before request

	client := setupClient(t, nil)
	var result testResponse
	statusCode, err := client.DoGetWithRetry(ctx, testServer.URL, &result)

	assert.Error(t, err)
	assert.Equal(t, 0, statusCode)
}

func TestGrayskullHTTPClient_E2E_NonRetryable4xxError(t *testing.T) {
	testServer := setupTestServer(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusBadRequest)
		w.Write([]byte(`{"error":"bad request"}`))
	}))

	client := setupClient(t, &models.GrayskullClientConfiguration{
		MaxRetries:    3,
		MinRetryDelay: 10,
		ReadTimeout:   1000,
	})

	var result testResponse
	statusCode, err := client.DoGetWithRetry(context.Background(), testServer.URL, &result)

	assert.Error(t, err)
	// Status code will be 0 when error is converted to GrayskullError after retries
	assert.True(t, statusCode == 0 || statusCode == http.StatusBadRequest)
	assert.Contains(t, err.Error(), "failed after 1 attempt")
}

func TestGrayskullHTTPClient_E2E_429TooManyRequestsRetry(t *testing.T) {
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

	var result testResponse
	statusCode, err := client.DoGetWithRetry(context.Background(), testServer.URL, &result)

	assert.NoError(t, err)
	assert.Equal(t, http.StatusOK, statusCode)
	assert.Equal(t, 2, attempts)
}

func TestGrayskullHTTPClient_E2E_MultipleRetriesBeforeSuccess(t *testing.T) {
	attempts := 0
	testServer := setupTestServer(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		attempts++
		if attempts < 3 {
			w.WriteHeader(http.StatusServiceUnavailable)
		} else {
			w.WriteHeader(http.StatusOK)
			w.Write([]byte(`{"data":"finally"}`))
		}
	}))

	client := setupClient(t, &models.GrayskullClientConfiguration{
		MaxRetries:    5,
		MinRetryDelay: 10,
		ReadTimeout:   1000,
	})

	var result testResponse
	statusCode, err := client.DoGetWithRetry(context.Background(), testServer.URL, &result)

	assert.NoError(t, err)
	assert.Equal(t, http.StatusOK, statusCode)
	assert.Equal(t, "finally", result.Data)
	assert.Equal(t, 3, attempts)
}

func TestGrayskullHTTPClient_E2E_AuthHeaderValidation(t *testing.T) {
	t.Run("auth header error", func(t *testing.T) {
		mockAuth := &MockAuthProviderHTTP{}
		mockAuth.On("GetAuthHeader").Return("", errors.New("auth error"))

		config := &models.GrayskullClientConfiguration{
			MaxRetries:    3,
			MinRetryDelay: 10,
			ReadTimeout:   1000,
		}

		client := internal.NewGrayskullHTTPClient(mockAuth, config, nil, NewNoopRecorder())
		var result testResponse
		statusCode, err := client.DoGetWithRetry(context.Background(), "http://example.com", &result)

		assert.Error(t, err)
		assert.Equal(t, 0, statusCode)
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
		var result testResponse
		statusCode, err := client.DoGetWithRetry(context.Background(), "http://example.com", &result)

		assert.Error(t, err)
		assert.Equal(t, 0, statusCode)
		assert.Contains(t, err.Error(), "auth header cannot be empty")
	})
}

func TestGrayskullHTTPClient_E2E_RequestHeaders(t *testing.T) {
	var capturedAuth, capturedRequestID string

	testServer := setupTestServer(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		capturedAuth = r.Header.Get("Authorization")
		capturedRequestID = r.Header.Get("X-Request-Id")
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(`{"data":"test"}`))
	}))

	client := setupClient(t, nil)
	ctx := context.WithValue(context.Background(), constants.GrayskullRequestID, "test-123")
	var result testResponse
	statusCode, err := client.DoGetWithRetry(ctx, testServer.URL, &result)

	assert.NoError(t, err)
	assert.Equal(t, http.StatusOK, statusCode)
	assert.Equal(t, "Bearer test-token", capturedAuth)
	assert.Equal(t, "test-123", capturedRequestID)
}

func TestGrayskullHTTPClient_E2E_InvalidURL(t *testing.T) {
	client := setupClient(t, nil)
	var result testResponse
	statusCode, err := client.DoGetWithRetry(context.Background(), "://invalid-url", &result)

	assert.Error(t, err)
	assert.Equal(t, 0, statusCode)
}

func TestGrayskullHTTPClient_E2E_UnmarshalError(t *testing.T) {
	testServer := setupTestServer(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(`invalid json`))
	}))

	client := setupClient(t, nil)
	var result testResponse
	statusCode, err := client.DoGetWithRetry(context.Background(), testServer.URL, &result)

	assert.Error(t, err)
	assert.Equal(t, http.StatusOK, statusCode)
	assert.Contains(t, err.Error(), "failed to unmarshal response")
}

func TestGrayskullHTTPClient_E2E_DifferentContentTypes(t *testing.T) {
	testServer := setupTestServer(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/xml")
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(`{"data":"test"}`))
	}))

	client := setupClient(t, nil)
	var result testResponse
	statusCode, err := client.DoGetWithRetry(context.Background(), testServer.URL, &result)

	assert.NoError(t, err)
	assert.Equal(t, http.StatusOK, statusCode)
	assert.Equal(t, "test", result.Data)
}

func TestGrayskullHTTPClient_E2E_Close(t *testing.T) {
	t.Run("successful close", func(t *testing.T) {
		client := setupClient(t, nil)
		err := client.Close()
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

func TestGrayskullHTTPClient_E2E_ClientCreation(t *testing.T) {
	t.Run("creates with valid config", func(t *testing.T) {
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

	t.Run("creates with nil logger", func(t *testing.T) {
		mockAuth := &MockAuthProviderHTTP{}
		config := &models.GrayskullClientConfiguration{
			MaxRetries:    3,
			MinRetryDelay: 100,
			ReadTimeout:   5000,
		}

		client := internal.NewGrayskullHTTPClient(mockAuth, config, nil, NewNoopRecorder())
		assert.NotNil(t, client)
	})

	t.Run("creates with nil metrics recorder", func(t *testing.T) {
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

func TestGrayskullHTTPClient_E2E_CompleteWorkflow(t *testing.T) {
	attempts := 0
	testServer := setupTestServer(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		attempts++

		assert.Equal(t, "Bearer test-token", r.Header.Get("Authorization"))
		assert.NotEmpty(t, r.Header.Get("X-Request-Id"))

		if attempts == 1 {
			w.WriteHeader(http.StatusServiceUnavailable)
			w.Write([]byte(`{"error":"service unavailable"}`))
		} else {
			w.WriteHeader(http.StatusOK)
			w.Write([]byte(`{"data":"complete"}`))
		}
	}))

	client := setupClient(t, &models.GrayskullClientConfiguration{
		MaxRetries:    3,
		MinRetryDelay: 10,
		ReadTimeout:   1000,
	})

	ctx := context.WithValue(context.Background(), constants.GrayskullRequestID, "workflow-test")
	var result testResponse
	statusCode, err := client.DoGetWithRetry(ctx, testServer.URL, &result)

	assert.NoError(t, err)
	assert.Equal(t, http.StatusOK, statusCode)
	assert.Equal(t, "complete", result.Data)
	assert.Equal(t, 2, attempts)

	err = client.Close()
	assert.NoError(t, err)
}

func TestGrayskullHTTPClient_E2E_ReadBodyError(t *testing.T) {
	// This test simulates a scenario where the server closes the connection while the body is being read
	testServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(`{"data":"test"}`))
		// Hijack the connection and close it to simulate body read failure
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
		MaxRetries:    2,
		MinRetryDelay: 10,
		ReadTimeout:   1000,
	})

	var result testResponse
	statusCode, err := client.DoGetWithRetry(context.Background(), testServer.URL, &result)

	// Should get an error because reading the body fails
	assert.Error(t, err)
	// Status code might be 0 or 200 depending on when the connection closes
	assert.True(t, statusCode == 0 || statusCode == http.StatusOK)
	assert.Contains(t, err.Error(), "failed after")
}
