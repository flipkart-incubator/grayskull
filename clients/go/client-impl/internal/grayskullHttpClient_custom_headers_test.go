package internal_test

import (
	"context"
	"net/http"
	"testing"

	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/constants"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/internal"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/models"
	"github.com/stretchr/testify/assert"
)

func TestCustomHeaders_AppliedFromConfig(t *testing.T) {
	mockAuth := &MockAuthProviderHTTP{}
	mockAuth.On("GetAuthHeader").Return("Bearer test-token", nil)

	config := &models.GrayskullClientConfiguration{
		MaxRetries:    1,
		MinRetryDelay: 10,
		ReadTimeout:   1000,
	}
	config.AddDefaultHeader("X-Custom-A", "valueA")
	config.AddDefaultHeader("X-Custom-B", "valueB")

	client := internal.NewGrayskullHTTPClient(mockAuth, config, nil, NewNoopRecorder())

	captured := map[string]string{}
	testServer := setupTestServer(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		captured["X-Custom-A"] = r.Header.Get("X-Custom-A")
		captured["X-Custom-B"] = r.Header.Get("X-Custom-B")
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(`{"data":"test"}`))
	}))

	var result testResponse
	_, err := client.DoGetWithRetry(context.Background(), testServer.URL, &result)

	assert.NoError(t, err)
	assert.Equal(t, "valueA", captured["X-Custom-A"])
	assert.Equal(t, "valueB", captured["X-Custom-B"])
}

func TestCustomHeaders_InternalHeadersWin(t *testing.T) {
	mockAuth := &MockAuthProviderHTTP{}
	mockAuth.On("GetAuthHeader").Return("Bearer real-token", nil)

	config := &models.GrayskullClientConfiguration{
		MaxRetries:    1,
		MinRetryDelay: 10,
		ReadTimeout:   1000,
	}
	config.AddDefaultHeader("Authorization", "Bearer fake-token")
	config.AddDefaultHeader("X-Request-Id", "fake-request-id")

	client := internal.NewGrayskullHTTPClient(mockAuth, config, nil, NewNoopRecorder())

	var capturedAuth, capturedRequestID string
	testServer := setupTestServer(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		capturedAuth = r.Header.Get("Authorization")
		capturedRequestID = r.Header.Get("X-Request-Id")
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(`{"data":"test"}`))
	}))

	var result testResponse
	ctx := context.WithValue(context.Background(), constants.GrayskullRequestID, "real-request-id")
	_, err := client.DoGetWithRetry(ctx, testServer.URL, &result)

	assert.NoError(t, err)
	assert.Equal(t, "Bearer real-token", capturedAuth)
	assert.Equal(t, "real-request-id", capturedRequestID)
}

func TestCustomHeaders_AppliedToPost(t *testing.T) {
	mockAuth := &MockAuthProviderHTTP{}
	mockAuth.On("GetAuthHeader").Return("Bearer test-token", nil)

	config := &models.GrayskullClientConfiguration{
		MaxRetries:    1,
		MinRetryDelay: 10,
		ReadTimeout:   1000,
	}
	config.AddDefaultHeader("X-Custom-Post", "post-value")

	client := internal.NewGrayskullHTTPClient(mockAuth, config, nil, NewNoopRecorder())

	var capturedCustom string
	testServer := setupTestServer(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		capturedCustom = r.Header.Get("X-Custom-Post")
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(`{"data":"test"}`))
	}))

	var result testResponse
	_, err := client.DoPostWithRetry(context.Background(), testServer.URL, []byte(`{"key":"value"}`), &result)

	assert.NoError(t, err)
	assert.Equal(t, "post-value", capturedCustom)
}
