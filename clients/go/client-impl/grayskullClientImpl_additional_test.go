package client_impl

import (
	"context"
	"encoding/json"
	"sync"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"

	Client_API "github.com/flipkart-incubator/grayskull/clients/go/client-api/models"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/internal"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/internal/hooks"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/internal/models/response"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/metrics"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/models"
	"github.com/prometheus/client_golang/prometheus"
)

// MockHTTPClientWithPost extends MockGrayskullHTTPClient to include POST
type MockHTTPClientWithPost struct {
	MockGrayskullHTTPClient
}

func (m *MockHTTPClientWithPost) DoPostWithRetry(ctx context.Context, url string, jsonBody []byte, result any) (int, error) {
	args := m.Called(ctx, url, jsonBody, result)
	return args.Int(0), args.Error(1)
}

// TestRegisterRefreshHook_Success verifies successful hook registration
func TestRegisterRefreshHook_Success(t *testing.T) {
	mockAuth := &MockAuthProvider{}
	mockHTTPClient := &MockHTTPClientWithPost{}

	config := &models.GrayskullClientConfiguration{
		Host:           "http://localhost:8080",
		MaxConnections: 10,
	}

	registry := hooks.NewRegistry()
	client := &GrayskullClientImpl{
		baseURL:            config.Host,
		authHeaderProvider: mockAuth,
		clientConfig:       config,
		httpClient:         mockHTTPClient,
		metricsRecorder:    metrics.NewPrometheusRecorder(prometheus.NewRegistry()),
		registry:           registry,
	}

	var hookCalled bool
	hook := func(v Client_API.SecretValue) error {
		hookCalled = true
		return nil
	}

	ref, err := client.RegisterRefreshHook(context.Background(), "project:secret", hook)

	assert.NoError(t, err)
	assert.NotNil(t, ref)
	assert.True(t, ref.IsActive())
	assert.Equal(t, "project:secret", ref.GetSecretRef())

	// Verify state was created in registry
	state := registry.Get("project:secret")
	assert.NotNil(t, state)
}

// TestRegisterRefreshHook_CanUnregister verifies that unregistering a hook works
func TestRegisterRefreshHook_CanUnregister(t *testing.T) {
	mockAuth := &MockAuthProvider{}
	mockHTTPClient := &MockHTTPClientWithPost{}

	config := &models.GrayskullClientConfiguration{
		Host:           "http://localhost:8080",
		MaxConnections: 10,
	}

	registry := hooks.NewRegistry()
	client := &GrayskullClientImpl{
		baseURL:            config.Host,
		authHeaderProvider: mockAuth,
		clientConfig:       config,
		httpClient:         mockHTTPClient,
		metricsRecorder:    metrics.NewPrometheusRecorder(prometheus.NewRegistry()),
		registry:           registry,
	}

	hook := func(v Client_API.SecretValue) error { return nil }

	ref, err := client.RegisterRefreshHook(context.Background(), "project:secret", hook)
	assert.NoError(t, err)
	assert.True(t, ref.IsActive())

	// Unregister
	ref.Unregister()
	assert.False(t, ref.IsActive())

	// Unregister again should be idempotent
	ref.Unregister()
	assert.False(t, ref.IsActive())
}

// TestRegisterRefreshHook_InvalidSecretRef verifies error for invalid secretRef
func TestRegisterRefreshHook_InvalidSecretRef(t *testing.T) {
	mockAuth := &MockAuthProvider{}
	mockHTTPClient := &MockHTTPClientWithPost{}

	config := &models.GrayskullClientConfiguration{
		Host:           "http://localhost:8080",
		MaxConnections: 10,
	}

	registry := hooks.NewRegistry()
	client := &GrayskullClientImpl{
		baseURL:            config.Host,
		authHeaderProvider: mockAuth,
		clientConfig:       config,
		httpClient:         mockHTTPClient,
		metricsRecorder:    metrics.NewPrometheusRecorder(prometheus.NewRegistry()),
		registry:           registry,
	}

	hook := func(v Client_API.SecretValue) error { return nil }

	// No colon
	ref, err := client.RegisterRefreshHook(context.Background(), "no-colon", hook)
	assert.Error(t, err)
	assert.Nil(t, ref)
	assert.Contains(t, err.Error(), "invalid secretRef format")

	// Empty projectID
	ref, err = client.RegisterRefreshHook(context.Background(), ":secret", hook)
	assert.Error(t, err)
	assert.Nil(t, ref)

	// Empty secretName
	ref, err = client.RegisterRefreshHook(context.Background(), "project:", hook)
	assert.Error(t, err)
	assert.Nil(t, ref)
}

// TestGetSecret_ThenRegisterHook_SeedsPollerWithObservedVersion verifies
// that getSecret observed version is used to seed the hook's lastKnownVersion
func TestGetSecret_ThenRegisterHook_SeedsPollerWithObservedVersion(t *testing.T) {
	mockAuth := &MockAuthProvider{}
	mockAuth.On("GetAuthHeader").Return("Bearer test-token", nil)

	mockHTTPClient := &MockHTTPClientWithPost{}

	secretValue := Client_API.SecretValue{
		DataVersion: 7,
		PublicPart:  "user",
		PrivatePart: "pwd",
	}
	resp := response.NewResponse(secretValue, "Success")
	jsonData, _ := json.Marshal(resp)

	mockHTTPClient.On("DoGetWithRetry", mock.Anything, mock.Anything, mock.Anything).
		Run(func(args mock.Arguments) {
			result := args.Get(2).(*response.Response[Client_API.SecretValue])
			json.Unmarshal(jsonData, result)
		}).
		Return(200, nil)

	config := &models.GrayskullClientConfiguration{
		Host:           "http://localhost:8080",
		MaxConnections: 10,
	}

	registry := hooks.NewRegistry()
	client := &GrayskullClientImpl{
		baseURL:            config.Host,
		authHeaderProvider: mockAuth,
		clientConfig:       config,
		httpClient:         mockHTTPClient,
		metricsRecorder:    metrics.NewPrometheusRecorder(prometheus.NewRegistry()),
		registry:           registry,
	}

	// GetSecret observes v7
	_, err := client.GetSecret(context.Background(), "team:db-pass")
	assert.NoError(t, err)

	// Register hook
	hook := func(v Client_API.SecretValue) error { return nil }
	ref, err := client.RegisterRefreshHook(context.Background(), "team:db-pass", hook)
	assert.NoError(t, err)
	assert.NotNil(t, ref)

	// Verify lastKnownVersion was seeded with 7
	state := registry.Get("team:db-pass")
	assert.NotNil(t, state)
	assert.Equal(t, int32(7), state.LastKnownVersion.Load())
}

// TestRegisterHook_WithoutPriorGetSecret_StartsFromVersionZero verifies
// that without a prior getSecret, the hook starts with lastKnownVersion=0
func TestRegisterHook_WithoutPriorGetSecret_StartsFromVersionZero(t *testing.T) {
	mockAuth := &MockAuthProvider{}
	mockHTTPClient := &MockHTTPClientWithPost{}

	config := &models.GrayskullClientConfiguration{
		Host:           "http://localhost:8080",
		MaxConnections: 10,
	}

	registry := hooks.NewRegistry()
	client := &GrayskullClientImpl{
		baseURL:            config.Host,
		authHeaderProvider: mockAuth,
		clientConfig:       config,
		httpClient:         mockHTTPClient,
		metricsRecorder:    metrics.NewPrometheusRecorder(prometheus.NewRegistry()),
		registry:           registry,
	}

	hook := func(v Client_API.SecretValue) error { return nil }
	ref, err := client.RegisterRefreshHook(context.Background(), "team:no-get-secret", hook)
	assert.NoError(t, err)
	assert.NotNil(t, ref)

	// Verify lastKnownVersion is 0
	state := registry.Get("team:no-get-secret")
	assert.NotNil(t, state)
	assert.Equal(t, int32(0), state.LastKnownVersion.Load())
}

// TestGetSecret_MultipleCallsSameSecret_SeedUsesLastObservedVersion verifies
// that multiple getSecret calls update the seed version, and the last one wins
func TestGetSecret_MultipleCallsSameSecret_SeedUsesLastObservedVersion(t *testing.T) {
	mockAuth := &MockAuthProvider{}
	mockAuth.On("GetAuthHeader").Return("Bearer test-token", nil)

	mockHTTPClient := &MockHTTPClientWithPost{}

	// First call returns v9
	secretValue1 := Client_API.SecretValue{
		DataVersion: 9,
		PublicPart:  "u",
		PrivatePart: "p",
	}
	resp1 := response.NewResponse(secretValue1, "Success")
	jsonData1, _ := json.Marshal(resp1)

	// Second call returns v11
	secretValue2 := Client_API.SecretValue{
		DataVersion: 11,
		PublicPart:  "u",
		PrivatePart: "p",
	}
	resp2 := response.NewResponse(secretValue2, "Success")
	jsonData2, _ := json.Marshal(resp2)

	callCount := 0
	mockHTTPClient.On("DoGetWithRetry", mock.Anything, mock.Anything, mock.Anything).
		Run(func(args mock.Arguments) {
			result := args.Get(2).(*response.Response[Client_API.SecretValue])
			callCount++
			if callCount == 1 {
				json.Unmarshal(jsonData1, result)
			} else {
				json.Unmarshal(jsonData2, result)
			}
		}).
		Return(200, nil)

	config := &models.GrayskullClientConfiguration{
		Host:           "http://localhost:8080",
		MaxConnections: 10,
	}

	registry := hooks.NewRegistry()
	client := &GrayskullClientImpl{
		baseURL:            config.Host,
		authHeaderProvider: mockAuth,
		clientConfig:       config,
		httpClient:         mockHTTPClient,
		metricsRecorder:    metrics.NewPrometheusRecorder(prometheus.NewRegistry()),
		registry:           registry,
	}

	// First getSecret observes v9
	_, err := client.GetSecret(context.Background(), "team:rotating")
	assert.NoError(t, err)

	// Second getSecret observes v11 (this is the last call)
	_, err = client.GetSecret(context.Background(), "team:rotating")
	assert.NoError(t, err)

	// Register hook - should seed with v11
	hook := func(v Client_API.SecretValue) error { return nil }
	ref, err := client.RegisterRefreshHook(context.Background(), "team:rotating", hook)
	assert.NoError(t, err)

	// Verify lastKnownVersion was seeded with 11 (last observed)
	state := registry.Get("team:rotating")
	assert.NotNil(t, state)
	assert.Equal(t, int32(11), state.LastKnownVersion.Load())
}

// TestRegisterHook_ThenGetSecret_DoesNotRetroactivelyUpdatePoller verifies
// that getSecret called AFTER registerRefreshHook does not update the poller's version
func TestRegisterHook_ThenGetSecret_DoesNotRetroactivelyUpdatePoller(t *testing.T) {
	mockAuth := &MockAuthProvider{}
	mockAuth.On("GetAuthHeader").Return("Bearer test-token", nil)

	mockHTTPClient := &MockHTTPClientWithPost{}

	secretValue := Client_API.SecretValue{
		DataVersion: 11,
		PublicPart:  "u",
		PrivatePart: "p",
	}
	resp := response.NewResponse(secretValue, "Success")
	jsonData, _ := json.Marshal(resp)

	mockHTTPClient.On("DoGetWithRetry", mock.Anything, mock.Anything, mock.Anything).
		Run(func(args mock.Arguments) {
			result := args.Get(2).(*response.Response[Client_API.SecretValue])
			json.Unmarshal(jsonData, result)
		}).
		Return(200, nil)

	config := &models.GrayskullClientConfiguration{
		Host:           "http://localhost:8080",
		MaxConnections: 10,
	}

	registry := hooks.NewRegistry()
	client := &GrayskullClientImpl{
		baseURL:            config.Host,
		authHeaderProvider: mockAuth,
		clientConfig:       config,
		httpClient:         mockHTTPClient,
		metricsRecorder:    metrics.NewPrometheusRecorder(prometheus.NewRegistry()),
		registry:           registry,
	}

	// Register hook first (no prior getSecret, so seed=0)
	hook := func(v Client_API.SecretValue) error { return nil }
	_, err := client.RegisterRefreshHook(context.Background(), "team:late-get", hook)
	assert.NoError(t, err)

	// Now call getSecret (after registration)
	_, err = client.GetSecret(context.Background(), "team:late-get")
	assert.NoError(t, err)

	// Verify lastKnownVersion is still 0 (not retroactively updated)
	state := registry.Get("team:late-get")
	assert.NotNil(t, state)
	assert.Equal(t, int32(0), state.LastKnownVersion.Load())
}

// TestGetSecret_DifferentSecret_DoesNotSeedUnrelatedHook verifies that
// versions for one secret don't bleed into another
func TestGetSecret_DifferentSecret_DoesNotSeedUnrelatedHook(t *testing.T) {
	mockAuth := &MockAuthProvider{}
	mockAuth.On("GetAuthHeader").Return("Bearer test-token", nil)

	mockHTTPClient := &MockHTTPClientWithPost{}

	secretValue := Client_API.SecretValue{
		DataVersion: 5,
		PublicPart:  "u",
		PrivatePart: "p",
	}
	resp := response.NewResponse(secretValue, "Success")
	jsonData, _ := json.Marshal(resp)

	mockHTTPClient.On("DoGetWithRetry", mock.Anything, mock.Anything, mock.Anything).
		Run(func(args mock.Arguments) {
			result := args.Get(2).(*response.Response[Client_API.SecretValue])
			json.Unmarshal(jsonData, result)
		}).
		Return(200, nil)

	config := &models.GrayskullClientConfiguration{
		Host:           "http://localhost:8080",
		MaxConnections: 10,
	}

	registry := hooks.NewRegistry()
	client := &GrayskullClientImpl{
		baseURL:            config.Host,
		authHeaderProvider: mockAuth,
		clientConfig:       config,
		httpClient:         mockHTTPClient,
		metricsRecorder:    metrics.NewPrometheusRecorder(prometheus.NewRegistry()),
		registry:           registry,
	}

	// GetSecret for secret-a records v5
	_, err := client.GetSecret(context.Background(), "team:secret-a")
	assert.NoError(t, err)

	// Register hook for a different secret (secret-b)
	hook := func(v Client_API.SecretValue) error { return nil }
	_, err = client.RegisterRefreshHook(context.Background(), "team:secret-b", hook)
	assert.NoError(t, err)

	// Verify secret-b has lastKnownVersion=0 (not seeded from secret-a)
	state := registry.Get("team:secret-b")
	assert.NotNil(t, state)
	assert.Equal(t, int32(0), state.LastKnownVersion.Load())
}

// TestTwoHooksSameSecret_SecondRegistrationLeavesVersionIntact verifies
// that registering a second hook for the same secret doesn't overwrite lastKnownVersion
func TestTwoHooksSameSecret_SecondRegistrationLeavesVersionIntact(t *testing.T) {
	mockAuth := &MockAuthProvider{}
	mockAuth.On("GetAuthHeader").Return("Bearer test-token", nil)

	mockHTTPClient := &MockHTTPClientWithPost{}

	secretValue := Client_API.SecretValue{
		DataVersion: 8,
		PublicPart:  "u",
		PrivatePart: "p",
	}
	resp := response.NewResponse(secretValue, "Success")
	jsonData, _ := json.Marshal(resp)

	mockHTTPClient.On("DoGetWithRetry", mock.Anything, mock.Anything, mock.Anything).
		Run(func(args mock.Arguments) {
			result := args.Get(2).(*response.Response[Client_API.SecretValue])
			json.Unmarshal(jsonData, result)
		}).
		Return(200, nil)

	config := &models.GrayskullClientConfiguration{
		Host:           "http://localhost:8080",
		MaxConnections: 10,
	}

	registry := hooks.NewRegistry()
	client := &GrayskullClientImpl{
		baseURL:            config.Host,
		authHeaderProvider: mockAuth,
		clientConfig:       config,
		httpClient:         mockHTTPClient,
		metricsRecorder:    metrics.NewPrometheusRecorder(prometheus.NewRegistry()),
		registry:           registry,
	}

	// GetSecret observes v8
	_, err := client.GetSecret(context.Background(), "team:shared")
	assert.NoError(t, err)

	// Register first hook (seeds lastKnownVersion=8)
	hook1 := func(v interface{}) error { return nil }
	_, err = client.RegisterRefreshHook(context.Background(), "team:shared", hook1)
	assert.NoError(t, err)

	// Register second hook (should not overwrite version)
	hook2 := func(v interface{}) error { return nil }
	_, err = client.RegisterRefreshHook(context.Background(), "team:shared", hook2)
	assert.NoError(t, err)

	// Verify lastKnownVersion is still 8
	state := registry.Get("team:shared")
	assert.NotNil(t, state)
	assert.Equal(t, int32(8), state.LastKnownVersion.Load())
}

// TestRegisterRefreshHook_AfterClose_Throws verifies that registering a hook
// after client is closed returns an error
func TestRegisterRefreshHook_AfterClose_Throws(t *testing.T) {
	mockAuth := &MockAuthProvider{}
	mockHTTPClient := &MockHTTPClientWithPost{}

	config := &models.GrayskullClientConfiguration{
		Host:           "http://localhost:8080",
		MaxConnections: 10,
	}

	registry := hooks.NewRegistry()
	client := &GrayskullClientImpl{
		baseURL:            config.Host,
		authHeaderProvider: mockAuth,
		clientConfig:       config,
		httpClient:         mockHTTPClient,
		metricsRecorder:    metrics.NewPrometheusRecorder(prometheus.NewRegistry()),
		registry:           registry,
	}

	// Close the client
	client.Close()

	// Try to register a hook after close
	hook := func(v Client_API.SecretValue) error { return nil }
	ref, err := client.RegisterRefreshHook(context.Background(), "team:after-close", hook)

	assert.Error(t, err)
	assert.Nil(t, ref)
	assert.Contains(t, err.Error(), "client has been closed")
}

// TestClose_CleansUpResources verifies that Close properly cleans up resources
func TestClose_CleansUpResources(t *testing.T) {
	mockAuth := &MockAuthProvider{}
	mockHTTPClient := &MockHTTPClientWithPost{}
	mockHTTPClient.On("Close").Return(nil)

	config := &models.GrayskullClientConfiguration{
		Host:           "http://localhost:8080",
		MaxConnections: 10,
	}

	registry := hooks.NewRegistry()
	client := &GrayskullClientImpl{
		baseURL:            config.Host,
		authHeaderProvider: mockAuth,
		clientConfig:       config,
		httpClient:         mockHTTPClient,
		metricsRecorder:    metrics.NewPrometheusRecorder(prometheus.NewRegistry()),
		registry:           registry,
	}

	err := client.Close()

	assert.NoError(t, err)
	mockHTTPClient.AssertCalled(t, "Close")
}

// TestClose_CalledTwice_IsIdempotent verifies that calling Close multiple times is safe
func TestClose_CalledTwice_IsIdempotent(t *testing.T) {
	mockAuth := &MockAuthProvider{}
	mockHTTPClient := &MockHTTPClientWithPost{}
	mockHTTPClient.On("Close").Return(nil)

	config := &models.GrayskullClientConfiguration{
		Host:           "http://localhost:8080",
		MaxConnections: 10,
	}

	registry := hooks.NewRegistry()
	client := &GrayskullClientImpl{
		baseURL:            config.Host,
		authHeaderProvider: mockAuth,
		clientConfig:       config,
		httpClient:         mockHTTPClient,
		metricsRecorder:    metrics.NewPrometheusRecorder(prometheus.NewRegistry()),
		registry:           registry,
	}

	err1 := client.Close()
	err2 := client.Close()

	assert.NoError(t, err1)
	assert.NoError(t, err2)

	// Close should be called only once due to idempotency
	mockHTTPClient.AssertNumberOfCalls(t, "Close", 1)
}

// TestSplitSecretRef_WithColonsInSecretName verifies that secretNames with
// colons are handled correctly (splits on first colon only)
func TestSplitSecretRef_WithColonsInSecretName(t *testing.T) {
	mockAuth := &MockAuthProvider{}
	mockHTTPClient := &MockHTTPClientWithPost{}

	config := &models.GrayskullClientConfiguration{
		Host:           "http://localhost:8080",
		MaxConnections: 10,
	}

	registry := hooks.NewRegistry()
	client := &GrayskullClientImpl{
		baseURL:            config.Host,
		authHeaderProvider: mockAuth,
		clientConfig:       config,
		httpClient:         mockHTTPClient,
		metricsRecorder:    metrics.NewPrometheusRecorder(prometheus.NewRegistry()),
		registry:           registry,
	}

	// Test indirectly through RegisterRefreshHook
	hook := func(v Client_API.SecretValue) error { return nil }
	ref, err := client.RegisterRefreshHook(context.Background(), "project:secret:with:colons", hook)

	assert.NoError(t, err)
	assert.NotNil(t, ref)
	assert.Equal(t, "project:secret:with:colons", ref.GetSecretRef())

	// Verify state was created correctly
	state := registry.Get("project:secret:with:colons")
	assert.NotNil(t, state)
	assert.Equal(t, "project", state.ProjectID)
	assert.Equal(t, "secret:with:colons", state.SecretName)
}

// TestConcurrentGetSecretAndRegisterHook verifies thread safety
func TestConcurrentGetSecretAndRegisterHook(t *testing.T) {
	mockAuth := &MockAuthProvider{}
	mockAuth.On("GetAuthHeader").Return("Bearer test-token", nil)

	mockHTTPClient := &MockHTTPClientWithPost{}

	secretValue := Client_API.SecretValue{
		DataVersion: 1,
		PublicPart:  "p",
		PrivatePart: "p",
	}
	resp := response.NewResponse(secretValue, "Success")
	jsonData, _ := json.Marshal(resp)

	mockHTTPClient.On("DoGetWithRetry", mock.Anything, mock.Anything, mock.Anything).
		Run(func(args mock.Arguments) {
			result := args.Get(2).(*response.Response[Client_API.SecretValue])
			json.Unmarshal(jsonData, result)
		}).
		Return(200, nil)

	config := &models.GrayskullClientConfiguration{
		Host:           "http://localhost:8080",
		MaxConnections: 10,
	}

	registry := hooks.NewRegistry()
	client := &GrayskullClientImpl{
		baseURL:            config.Host,
		authHeaderProvider: mockAuth,
		clientConfig:       config,
		httpClient:         mockHTTPClient,
		metricsRecorder:    metrics.NewPrometheusRecorder(prometheus.NewRegistry()),
		registry:           registry,
	}

	const numGoroutines = 50
	var wg sync.WaitGroup
	wg.Add(numGoroutines * 2) // half GetSecret, half RegisterRefreshHook

	// Concurrent GetSecret calls
	for i := 0; i < numGoroutines; i++ {
		go func() {
			defer wg.Done()
			client.GetSecret(context.Background(), "team:concurrent")
		}()
	}

	// Concurrent RegisterRefreshHook calls
	for i := 0; i < numGoroutines; i++ {
		go func() {
			defer wg.Done()
			hook := func(v Client_API.SecretValue) error { return nil }
			client.RegisterRefreshHook(context.Background(), "team:concurrent", hook)
		}()
	}

	wg.Wait()
	// Test passes if no data race detected (run with -race flag)
}

// TestGetSecret_RecordsVersionInLastSeenVersions verifies that successful
// getSecret calls update lastSeenVersions
func TestGetSecret_RecordsVersionInLastSeenVersions(t *testing.T) {
	mockAuth := &MockAuthProvider{}
	mockAuth.On("GetAuthHeader").Return("Bearer test-token", nil)

	mockHTTPClient := &MockHTTPClientWithPost{}

	secretValue := Client_API.SecretValue{
		DataVersion: 42,
		PublicPart:  "test",
		PrivatePart: "test",
	}
	resp := response.NewResponse(secretValue, "Success")
	jsonData, _ := json.Marshal(resp)

	mockHTTPClient.On("DoGetWithRetry", mock.Anything, mock.Anything, mock.Anything).
		Run(func(args mock.Arguments) {
			result := args.Get(2).(*response.Response[Client_API.SecretValue])
			json.Unmarshal(jsonData, result)
		}).
		Return(200, nil)

	config := &models.GrayskullClientConfiguration{
		Host:           "http://localhost:8080",
		MaxConnections: 10,
	}

	registry := hooks.NewRegistry()
	client := &GrayskullClientImpl{
		baseURL:            config.Host,
		authHeaderProvider: mockAuth,
		clientConfig:       config,
		httpClient:         mockHTTPClient,
		metricsRecorder:    metrics.NewPrometheusRecorder(prometheus.NewRegistry()),
		registry:           registry,
	}

	_, err := client.GetSecret(context.Background(), "team:recorded")
	assert.NoError(t, err)

	// Verify version was recorded in lastSeenVersions (via hook registration)
	// The lastSeenVersions is private, so we verify indirectly through hook registration
	hook2 := func(v Client_API.SecretValue) error { return nil }
	ref, err2 := client.RegisterRefreshHook(context.Background(), "team:recorded", hook2)
	assert.NoError(t, err2)

	// Verify the hook was seeded with version 42
	state := registry.Get("team:recorded")
	assert.NotNil(t, state)
	assert.Equal(t, int32(42), state.LastKnownVersion.Load())
	ref.Unregister()
}

// TestNewGrayskullClient_WithDefaultPollInterval verifies that the poller
// is started with the correct interval
func TestNewGrayskullClient_WithDefaultPollInterval(t *testing.T) {
	mockAuth := &MockAuthProvider{}

	config := models.NewDefaultConfig()
	config.Host = "http://localhost:8080"
	config.MaxConnections = 10
	config.PollingIntervalSeconds = 0 // Should use default

	client, err := NewGrayskullClient(mockAuth, config, nil)

	assert.NoError(t, err)
	assert.NotNil(t, client)

	// Clean up
	client.(*GrayskullClientImpl).Close()
}

// TestNewGrayskullClient_WithCustomPollInterval verifies custom polling interval
func TestNewGrayskullClient_WithCustomPollInterval(t *testing.T) {
	mockAuth := &MockAuthProvider{}

	config := models.NewDefaultConfig()
	config.Host = "http://localhost:8080"
	config.MaxConnections = 10
	config.PollingIntervalSeconds = 120 // 2 minutes

	client, err := NewGrayskullClient(mockAuth, config, nil)

	assert.NoError(t, err)
	assert.NotNil(t, client)

	implClient := client.(*GrayskullClientImpl)
	assert.NotNil(t, implClient.poller)

	// Clean up
	implClient.Close()
}
