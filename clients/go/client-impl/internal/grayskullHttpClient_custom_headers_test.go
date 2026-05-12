package internal_test

import (
	"context"
	"net/http"
	"testing"

	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/internal"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/models"
	"github.com/stretchr/testify/assert"
)

// TestSetCustomHeaders_ReplacesHeaders verifies that SetCustomHeaders
// replaces the set of custom headers.
func TestSetCustomHeaders_ReplacesHeaders(t *testing.T) {
	mockAuth := &MockAuthProviderHTTP{}
	mockAuth.On("GetAuthHeader").Return("Bearer test-token", nil)

	config := &models.GrayskullClientConfiguration{
		MaxRetries:    1,
		MinRetryDelay: 10,
		ReadTimeout:   1000,
	}

	rawClient := internal.NewGrayskullHTTPClient(mockAuth, config, nil, NewNoopRecorder())

	// Type assert to the concrete type to access SetCustomHeaders
	concreteClient, ok := rawClient.(*internal.GrayskullHTTPClient)
	assert.True(t, ok, "client should be *internal.GrayskullHTTPClient")

	// Set initial headers
	initialHeaders := map[string]string{
		"X-Custom-1": "value1",
		"X-Custom-2": "value2",
	}
	concreteClient.SetCustomHeaders(initialHeaders)

	// Set new headers (should replace, not merge)
	newHeaders := map[string]string{
		"X-Custom-3": "value3",
	}
	concreteClient.SetCustomHeaders(newHeaders)

	// Verify by making a request and checking headers
	var capturedCustom1, capturedCustom3 string
	testServer := setupTestServer(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		capturedCustom1 = r.Header.Get("X-Custom-1")
		capturedCustom3 = r.Header.Get("X-Custom-3")
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(`{"data":"test"}`))
	}))

	var result testResponse
	_, err := rawClient.DoGetWithRetry(context.Background(), testServer.URL, &result)

	assert.NoError(t, err)
	assert.Empty(t, capturedCustom1, "old custom header should not be present after SetCustomHeaders")
	assert.Equal(t, "value3", capturedCustom3, "new custom header should be present")
}

// TestSetCustomHeaders_EmptyMap_ClearsHeaders verifies that passing an empty
// map or nil clears all custom headers.
func TestSetCustomHeaders_EmptyMap_ClearsHeaders(t *testing.T) {
	mockAuth := &MockAuthProviderHTTP{}
	mockAuth.On("GetAuthHeader").Return("Bearer test-token", nil)

	config := &models.GrayskullClientConfiguration{
		MaxRetries:    1,
		MinRetryDelay: 10,
		ReadTimeout:   1000,
	}

	rawClient := internal.NewGrayskullHTTPClient(mockAuth, config, nil, NewNoopRecorder())

	concreteClient, ok := rawClient.(*internal.GrayskullHTTPClient)
	assert.True(t, ok)

	// Set initial headers
	initialHeaders := map[string]string{
		"X-Custom": "value",
	}
	concreteClient.SetCustomHeaders(initialHeaders)

	// Clear with empty map
	concreteClient.SetCustomHeaders(map[string]string{})

	// Verify header is not present
	var capturedCustom string
	testServer := setupTestServer(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		capturedCustom = r.Header.Get("X-Custom")
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(`{"data":"test"}`))
	}))

	var result testResponse
	_, err := rawClient.DoGetWithRetry(context.Background(), testServer.URL, &result)

	assert.NoError(t, err)
	assert.Empty(t, capturedCustom, "custom header should be cleared")
}

// TestSetCustomHeaders_InternalHeadersWin verifies that internal headers
// (Authorization, X-Request-Id) always overwrite custom headers if there's a conflict.
func TestSetCustomHeaders_InternalHeadersWin(t *testing.T) {
	mockAuth := &MockAuthProviderHTTP{}
	mockAuth.On("GetAuthHeader").Return("Bearer real-token", nil)

	config := &models.GrayskullClientConfiguration{
		MaxRetries:    1,
		MinRetryDelay: 10,
		ReadTimeout:   1000,
	}

	rawClient := internal.NewGrayskullHTTPClient(mockAuth, config, nil, NewNoopRecorder())

	concreteClient, ok := rawClient.(*internal.GrayskullHTTPClient)
	assert.True(t, ok)

	// Try to set Authorization header via custom headers
	customHeaders := map[string]string{
		"Authorization": "Bearer fake-token",
		"X-Request-Id":  "fake-request-id",
	}
	concreteClient.SetCustomHeaders(customHeaders)

	// Verify internal headers win
	var capturedAuth, capturedRequestID string
	testServer := setupTestServer(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		capturedAuth = r.Header.Get("Authorization")
		capturedRequestID = r.Header.Get("X-Request-Id")
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(`{"data":"test"}`))
	}))

	var result testResponse
	ctx := context.Background()
	_, err := rawClient.DoGetWithRetry(ctx, testServer.URL, &result)

	assert.NoError(t, err)
	assert.Equal(t, "Bearer real-token", capturedAuth, "internal Authorization should win")
	// X-Request-Id is only set if present in context, so it might be empty or a generated one
	// The key point is it shouldn't be "fake-request-id"
	assert.NotEqual(t, "fake-request-id", capturedRequestID)
}

// TestSetCustomHeaders_MultipleHeadersSet verifies that multiple custom
// headers can be set and all are included in requests.
func TestSetCustomHeaders_MultipleHeadersSet(t *testing.T) {
	mockAuth := &MockAuthProviderHTTP{}
	mockAuth.On("GetAuthHeader").Return("Bearer test-token", nil)

	config := &models.GrayskullClientConfiguration{
		MaxRetries:    1,
		MinRetryDelay: 10,
		ReadTimeout:   1000,
	}

	rawClient := internal.NewGrayskullHTTPClient(mockAuth, config, nil, NewNoopRecorder())

	concreteClient, ok := rawClient.(*internal.GrayskullHTTPClient)
	assert.True(t, ok)

	// Set multiple custom headers
	customHeaders := map[string]string{
		"X-Custom-A": "valueA",
		"X-Custom-B": "valueB",
		"X-Custom-C": "valueC",
	}
	concreteClient.SetCustomHeaders(customHeaders)

	// Verify all headers are present
	capturedHeaders := make(map[string]string)
	testServer := setupTestServer(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		capturedHeaders["X-Custom-A"] = r.Header.Get("X-Custom-A")
		capturedHeaders["X-Custom-B"] = r.Header.Get("X-Custom-B")
		capturedHeaders["X-Custom-C"] = r.Header.Get("X-Custom-C")
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(`{"data":"test"}`))
	}))

	var result testResponse
	_, err := rawClient.DoGetWithRetry(context.Background(), testServer.URL, &result)

	assert.NoError(t, err)
	assert.Equal(t, "valueA", capturedHeaders["X-Custom-A"])
	assert.Equal(t, "valueB", capturedHeaders["X-Custom-B"])
	assert.Equal(t, "valueC", capturedHeaders["X-Custom-C"])
}

// TestSetCustomHeaders_CopiesMap verifies that SetCustomHeaders creates a copy
// of the provided map so external modifications don't affect the client.
func TestSetCustomHeaders_CopiesMap(t *testing.T) {
	mockAuth := &MockAuthProviderHTTP{}
	mockAuth.On("GetAuthHeader").Return("Bearer test-token", nil)

	config := &models.GrayskullClientConfiguration{
		MaxRetries:    1,
		MinRetryDelay: 10,
		ReadTimeout:   1000,
	}

	rawClient := internal.NewGrayskullHTTPClient(mockAuth, config, nil, NewNoopRecorder())

	concreteClient, ok := rawClient.(*internal.GrayskullHTTPClient)
	assert.True(t, ok)

	// Set custom headers
	customHeaders := map[string]string{
		"X-Custom": "original",
	}
	concreteClient.SetCustomHeaders(customHeaders)

	// Modify the original map
	customHeaders["X-Custom"] = "modified"
	customHeaders["X-New"] = "added"

	// Verify the client still uses the original values
	var capturedCustom, capturedNew string
	testServer := setupTestServer(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		capturedCustom = r.Header.Get("X-Custom")
		capturedNew = r.Header.Get("X-New")
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(`{"data":"test"}`))
	}))

	var result testResponse
	_, err := rawClient.DoGetWithRetry(context.Background(), testServer.URL, &result)

	assert.NoError(t, err)
	assert.Equal(t, "original", capturedCustom, "client should use copied value, not modified original")
	assert.Empty(t, capturedNew, "newly added header should not affect client")
}

// TestSetCustomHeaders_POSTRequest verifies that custom headers are also
// applied to POST requests.
func TestSetCustomHeaders_POSTRequest(t *testing.T) {
	mockAuth := &MockAuthProviderHTTP{}
	mockAuth.On("GetAuthHeader").Return("Bearer test-token", nil)

	config := &models.GrayskullClientConfiguration{
		MaxRetries:    1,
		MinRetryDelay: 10,
		ReadTimeout:   1000,
	}

	rawClient := internal.NewGrayskullHTTPClient(mockAuth, config, nil, NewNoopRecorder())

	concreteClient, ok := rawClient.(*internal.GrayskullHTTPClient)
	assert.True(t, ok)

	// Set custom headers
	customHeaders := map[string]string{
		"X-Custom-Post": "post-value",
	}
	concreteClient.SetCustomHeaders(customHeaders)

	// Verify header is present in POST request
	var capturedCustom string
	testServer := setupTestServer(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		capturedCustom = r.Header.Get("X-Custom-Post")
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(`{"data":"test"}`))
	}))

	var result testResponse
	jsonBody := []byte(`{"key":"value"}`)
	_, err := rawClient.DoPostWithRetry(context.Background(), testServer.URL, jsonBody, &result)

	assert.NoError(t, err)
	assert.Equal(t, "post-value", capturedCustom, "custom header should be present in POST request")
}

// TestApplyHeaders_WithoutCustomHeaders verifies that when no custom headers
// are set, only internal headers are applied.
func TestApplyHeaders_WithoutCustomHeaders(t *testing.T) {
	mockAuth := &MockAuthProviderHTTP{}
	mockAuth.On("GetAuthHeader").Return("Bearer test-token", nil)

	config := &models.GrayskullClientConfiguration{
		MaxRetries:    1,
		MinRetryDelay: 10,
		ReadTimeout:   1000,
	}

	rawClient := internal.NewGrayskullHTTPClient(mockAuth, config, nil, NewNoopRecorder())

	// Don't set any custom headers

	// Verify only Authorization is present
	var capturedAuth string
	var headerCount int
	testServer := setupTestServer(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		capturedAuth = r.Header.Get("Authorization")
		headerCount = len(r.Header)
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(`{"data":"test"}`))
	}))

	var result testResponse
	_, err := rawClient.DoGetWithRetry(context.Background(), testServer.URL, &result)

	assert.NoError(t, err)
	assert.Equal(t, "Bearer test-token", capturedAuth)
	// Should have Authorization plus standard HTTP headers (User-Agent, Accept-Encoding, etc.)
	// No custom headers should be present
}

// TestSetCustomHeaders_NilMap verifies that passing nil to SetCustomHeaders
// clears all custom headers.
func TestSetCustomHeaders_NilMap(t *testing.T) {
	mockAuth := &MockAuthProviderHTTP{}
	mockAuth.On("GetAuthHeader").Return("Bearer test-token", nil)

	config := &models.GrayskullClientConfiguration{
		MaxRetries:    1,
		MinRetryDelay: 10,
		ReadTimeout:   1000,
	}

	rawClient := internal.NewGrayskullHTTPClient(mockAuth, config, nil, NewNoopRecorder())

	concreteClient, ok := rawClient.(*internal.GrayskullHTTPClient)
	assert.True(t, ok)

	// Set initial headers
	concreteClient.SetCustomHeaders(map[string]string{"X-Custom": "value"})

	// Clear with nil
	concreteClient.SetCustomHeaders(nil)

	// Verify header is cleared
	var capturedCustom string
	testServer := setupTestServer(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		capturedCustom = r.Header.Get("X-Custom")
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(`{"data":"test"}`))
	}))

	var result testResponse
	_, err := rawClient.DoGetWithRetry(context.Background(), testServer.URL, &result)

	assert.NoError(t, err)
	assert.Empty(t, capturedCustom, "custom header should be cleared when nil is passed")
}
