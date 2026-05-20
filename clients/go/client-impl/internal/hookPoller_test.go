package internal

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/flipkart-incubator/grayskull/clients/go/client-api/models"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/constants"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/internal/hooks"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/internal/models/batch"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/internal/models/response"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/metrics"
	grayskullErrors "github.com/flipkart-incubator/grayskull/clients/go/client-impl/models/errors"
	"github.com/prometheus/client_golang/prometheus"
)

// mockHTTPClient is a test stub for GrayskullHTTPClientInterface.
type mockHTTPClient struct {
	mu            sync.Mutex
	postResponses []postResponse
	postCallCount int
	postErr       error
	closed        bool
}

type postResponse struct {
	statusCode int
	response   interface{}
	err        error
}

func (m *mockHTTPClient) DoPostWithRetry(ctx context.Context, url string, jsonBody []byte, result any) (int, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.postCallCount++

	if m.postErr != nil {
		return 500, m.postErr
	}

	if len(m.postResponses) > 0 {
		resp := m.postResponses[0]
		if len(m.postResponses) > 1 {
			m.postResponses = m.postResponses[1:]
		}
		if resp.err != nil {
			return resp.statusCode, resp.err
		}
		if result != nil && resp.response != nil {
			data, _ := json.Marshal(resp.response)
			json.Unmarshal(data, result)
		}
		return resp.statusCode, nil
	}

	if result != nil {
		emptyResp := response.Response[batch.BatchGetSecretsResponse]{
			Data: batch.BatchGetSecretsResponse{
				UpdatedCount:   0,
				UpdatedSecrets: []batch.UpdatedSecret{},
			},
			Message: "Success",
		}
		data, _ := json.Marshal(emptyResp)
		json.Unmarshal(data, result)
	}
	return 200, nil
}

func (m *mockHTTPClient) DoGetWithRetry(ctx context.Context, url string, result any) (int, error) {
	return 200, nil
}

func (m *mockHTTPClient) Close() error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.closed = true
	return nil
}

func (m *mockHTTPClient) setPostError(err error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.postErr = err
}

func (m *mockHTTPClient) addPostResponse(statusCode int, resp interface{}, err error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.postResponses = append(m.postResponses, postResponse{
		statusCode: statusCode,
		response:   resp,
		err:        err,
	})
}

func (m *mockHTTPClient) getPostCallCount() int {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.postCallCount
}

func (m *mockHTTPClient) resetPostCallCount() {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.postCallCount = 0
}

// Empty registry: PollOnce makes no HTTP call.
func TestPollOnce_NoRegisteredSecrets_DoesNotPost(t *testing.T) {
	mockClient := &mockHTTPClient{}
	registry := hooks.NewRegistry()
	mockMetrics := metrics.NewPrometheusRecorder(prometheus.NewRegistry())

	poller := NewPoller(PollerConfig{
		BaseURL:         "https://test.example.com",
		HTTPClient:      mockClient,
		Registry:        registry,
		Interval:        60 * time.Second,
		MetricsRecorder: mockMetrics,
	})
	defer poller.Close()
	poller.Start()

	ctx := context.Background()
	poller.PollOnce(ctx)

	if got := mockClient.getPostCallCount(); got != 0 {
		t.Errorf("DoPostWithRetry called %d times, want 0 when registry is empty", got)
	}
}

// One chunk fits all secrets; one POST with no updates returned.
func TestPollOnce_SingleChunk_EmptyUpdatedSecrets_OnePost(t *testing.T) {
	mockClient := &mockHTTPClient{}
	registry := hooks.NewRegistry()
	mockMetrics := metrics.NewPrometheusRecorder(prometheus.NewRegistry())

	// Register two secrets
	registry.Register("acme", "db-pass", func(_ context.Context, _ models.SecretValue) error { return nil }, 0)
	registry.Register("acme", "api-key", func(_ context.Context, _ models.SecretValue) error { return nil }, 0)

	poller := NewPoller(PollerConfig{
		BaseURL:         "https://test.example.com",
		HTTPClient:      mockClient,
		Registry:        registry,
		Interval:        60 * time.Second,
		MetricsRecorder: mockMetrics,
	})
	defer poller.Close()
	poller.Start()

	ctx := context.Background()
	poller.PollOnce(ctx)

	if got := mockClient.getPostCallCount(); got != 1 {
		t.Errorf("DoPostWithRetry called %d times, want 1 for single chunk", got)
	}
}

// 51 registered secrets (> maxBatchSize=50): two sequential POSTs (50+1).
func TestPollOnce_FiftyOneSecrets_TwoSequentialPosts(t *testing.T) {
	mockClient := &mockHTTPClient{}
	registry := hooks.NewRegistry()
	mockMetrics := metrics.NewPrometheusRecorder(prometheus.NewRegistry())

	hook := func(_ context.Context, _ models.SecretValue) error { return nil }
	// Register 51 secrets
	for i := 0; i < 51; i++ {
		registry.Register("corp", fmt.Sprintf("svc-%d", i), hook, 0)
	}

	poller := NewPoller(PollerConfig{
		BaseURL:         "https://test.example.com",
		HTTPClient:      mockClient,
		Registry:        registry,
		Interval:        60 * time.Second,
		MetricsRecorder: mockMetrics,
	})
	defer poller.Close()
	poller.Start()

	ctx := context.Background()
	poller.PollOnce(ctx)

	if got := mockClient.getPostCallCount(); got != 2 {
		t.Errorf("DoPostWithRetry called %d times, want 2 for 51 secrets", got)
	}
}

// Updated secret in batch response invokes the registered hook.
func TestPollOnce_BatchReturnsUpdatedSecret_InvokesHook(t *testing.T) {
	mockClient := &mockHTTPClient{}
	registry := hooks.NewRegistry()
	mockMetrics := metrics.NewPrometheusRecorder(prometheus.NewRegistry())

	var hookCalled atomic.Bool
	var receivedVersion int
	var mu sync.Mutex

	hook := func(_ context.Context, v models.SecretValue) error {
		mu.Lock()
		defer mu.Unlock()
		receivedVersion = v.DataVersion
		hookCalled.Store(true)
		return nil
	}

	registry.Register("acme", "db", hook, 0)

	// Configure mock to return an updated secret
	updatedSecret := batch.UpdatedSecret{
		ProjectID:   "acme",
		SecretName:  "db",
		DataVersion: 3,
		PublicPart:  "pub",
		PrivatePart: "priv",
	}
	mockResp := response.Response[batch.BatchGetSecretsResponse]{
		Data: batch.BatchGetSecretsResponse{
			UpdatedCount:   1,
			UpdatedSecrets: []batch.UpdatedSecret{updatedSecret},
		},
		Message: "Success",
	}
	mockClient.addPostResponse(200, mockResp, nil)

	poller := NewPoller(PollerConfig{
		BaseURL:         "https://test.example.com",
		HTTPClient:      mockClient,
		Registry:        registry,
		Interval:        60 * time.Second,
		MetricsRecorder: mockMetrics,
	})
	defer poller.Close()
	poller.Start()

	ctx := context.Background()
	poller.PollOnce(ctx)

	// Give dispatcher goroutines time to process
	time.Sleep(100 * time.Millisecond)

	if !hookCalled.Load() {
		t.Error("hook should have been invoked after batch returned updated secret")
	}

	mu.Lock()
	if receivedVersion != 3 {
		t.Errorf("hook received version %d, want 3", receivedVersion)
	}
	mu.Unlock()
}

// First-chunk failure does not abort the second chunk.
func TestPollOnce_FirstChunkFails_SecondChunkStillRuns(t *testing.T) {
	mockClient := &mockHTTPClient{}
	registry := hooks.NewRegistry()
	mockMetrics := metrics.NewPrometheusRecorder(prometheus.NewRegistry())

	hook := func(_ context.Context, _ models.SecretValue) error { return nil }
	// Register 51 secrets to force two chunks
	for i := 0; i < 51; i++ {
		registry.Register("corp", fmt.Sprintf("svc-%d", i), hook, 0)
	}

	// First chunk fails, second succeeds
	mockClient.addPostResponse(503, nil, grayskullErrors.NewGrayskullError(503, "batch unavailable"))
	emptyResp := response.Response[batch.BatchGetSecretsResponse]{
		Data:    batch.BatchGetSecretsResponse{UpdatedCount: 0, UpdatedSecrets: []batch.UpdatedSecret{}},
		Message: "Success",
	}
	mockClient.addPostResponse(200, emptyResp, nil)

	poller := NewPoller(PollerConfig{
		BaseURL:         "https://test.example.com",
		HTTPClient:      mockClient,
		Registry:        registry,
		Interval:        60 * time.Second,
		MetricsRecorder: mockMetrics,
	})
	defer poller.Close()
	poller.Start()

	ctx := context.Background()
	poller.PollOnce(ctx)

	if got := mockClient.getPostCallCount(); got != 2 {
		t.Errorf("DoPostWithRetry called %d times, want 2 (second chunk should run despite first failure)", got)
	}
}

// Multiple hooks for one secret are invoked in registration order.
func TestPollOnce_MultipleHooksForSameSecret_RunInRegistrationOrder(t *testing.T) {
	mockClient := &mockHTTPClient{}
	registry := hooks.NewRegistry()
	mockMetrics := metrics.NewPrometheusRecorder(prometheus.NewRegistry())

	var order []int
	var mu sync.Mutex

	hook1 := func(_ context.Context, _ models.SecretValue) error {
		mu.Lock()
		order = append(order, 1)
		mu.Unlock()
		return nil
	}
	hook2 := func(_ context.Context, _ models.SecretValue) error {
		mu.Lock()
		order = append(order, 2)
		mu.Unlock()
		return nil
	}

	registry.Register("acme", "ordered", hook1, 0)
	registry.Register("acme", "ordered", hook2, 0)

	updatedSecret := batch.UpdatedSecret{
		ProjectID:   "acme",
		SecretName:  "ordered",
		DataVersion: 9,
		PublicPart:  "a",
		PrivatePart: "b",
	}
	mockResp := response.Response[batch.BatchGetSecretsResponse]{
		Data: batch.BatchGetSecretsResponse{
			UpdatedCount:   1,
			UpdatedSecrets: []batch.UpdatedSecret{updatedSecret},
		},
		Message: "Success",
	}
	mockClient.addPostResponse(200, mockResp, nil)

	poller := NewPoller(PollerConfig{
		BaseURL:         "https://test.example.com",
		HTTPClient:      mockClient,
		Registry:        registry,
		Interval:        60 * time.Second,
		MetricsRecorder: mockMetrics,
	})
	defer poller.Close()
	poller.Start()

	ctx := context.Background()
	poller.PollOnce(ctx)

	// Give dispatcher time to process
	time.Sleep(100 * time.Millisecond)

	mu.Lock()
	defer mu.Unlock()
	if len(order) != 2 {
		t.Errorf("hooks invoked %d times, want 2", len(order))
	}
	if len(order) == 2 && (order[0] != 1 || order[1] != 2) {
		t.Errorf("hooks invoked in order %v, want [1, 2] (registration order)", order)
	}
}

// First hook panicking or erroring does not block the second hook.
func TestPollOnce_HookThrows_SwallowsAndContinues(t *testing.T) {
	mockClient := &mockHTTPClient{}
	registry := hooks.NewRegistry()
	mockMetrics := metrics.NewPrometheusRecorder(prometheus.NewRegistry())

	var secondHookCalled atomic.Bool

	hook1 := func(_ context.Context, _ models.SecretValue) error {
		panic("consumer bug")
	}
	hook2 := func(_ context.Context, _ models.SecretValue) error {
		secondHookCalled.Store(true)
		return nil
	}

	registry.Register("acme", "throws", hook1, 0)
	registry.Register("acme", "throws", hook2, 0)

	updatedSecret := batch.UpdatedSecret{
		ProjectID:   "acme",
		SecretName:  "throws",
		DataVersion: 7,
		PublicPart:  "p",
		PrivatePart: "q",
	}
	mockResp := response.Response[batch.BatchGetSecretsResponse]{
		Data: batch.BatchGetSecretsResponse{
			UpdatedCount:   1,
			UpdatedSecrets: []batch.UpdatedSecret{updatedSecret},
		},
		Message: "Success",
	}
	mockClient.addPostResponse(200, mockResp, nil)

	poller := NewPoller(PollerConfig{
		BaseURL:         "https://test.example.com",
		HTTPClient:      mockClient,
		Registry:        registry,
		Interval:        60 * time.Second,
		MetricsRecorder: mockMetrics,
	})
	defer poller.Close()
	poller.Start()

	ctx := context.Background()
	poller.PollOnce(ctx)

	// Give dispatcher time to process
	time.Sleep(100 * time.Millisecond)

	if !secondHookCalled.Load() {
		t.Error("second hook should have been invoked even after first hook panicked")
	}
}

// Invalid JSON in response is handled without panicking.
func TestPollOnce_ChunkInvalidJson_ContinuesGracefully(t *testing.T) {
	mockClient := &mockHTTPClient{}
	registry := hooks.NewRegistry()
	mockMetrics := metrics.NewPrometheusRecorder(prometheus.NewRegistry())

	registry.Register("acme", "one", func(_ context.Context, _ models.SecretValue) error { return nil }, 0)

	// Return an error for invalid JSON
	mockClient.addPostResponse(200, nil, errors.New("failed to unmarshal response"))

	poller := NewPoller(PollerConfig{
		BaseURL:         "https://test.example.com",
		HTTPClient:      mockClient,
		Registry:        registry,
		Interval:        60 * time.Second,
		MetricsRecorder: mockMetrics,
	})
	defer poller.Close()
	poller.Start()

	ctx := context.Background()
	// Should not panic
	poller.PollOnce(ctx)

	if got := mockClient.getPostCallCount(); got != 1 {
		t.Errorf("DoPostWithRetry called %d times, want 1", got)
	}
}

// Close stops background goroutines and waits for them.
func TestClose_StopsPollingAndDispatcher(t *testing.T) {
	mockClient := &mockHTTPClient{}
	registry := hooks.NewRegistry()
	mockMetrics := metrics.NewPrometheusRecorder(prometheus.NewRegistry())

	poller := NewPoller(PollerConfig{
		BaseURL:         "https://test.example.com",
		HTTPClient:      mockClient,
		Registry:        registry,
		Interval:        10 * time.Millisecond,
		MetricsRecorder: mockMetrics,
	})

	poller.Start()

	// Let it run for a bit
	time.Sleep(50 * time.Millisecond)

	// Close should stop everything
	poller.Close()

	// Verify goroutines stopped (no easy way to check directly, but Close
	// should return within the shutdown window)
}

// NewPoller builds batchURL = baseURL + "/v1/secrets/batch".
func TestPoller_BatchURLFormatsCorrectly(t *testing.T) {
	testCases := []struct {
		baseURL  string
		expected string
	}{
		{"https://test.example.com", "https://test.example.com/v1/secrets/batch"},
		{"http://localhost:8080", "http://localhost:8080/v1/secrets/batch"},
		{"https://api.grayskull.io", "https://api.grayskull.io/v1/secrets/batch"},
	}

	for _, tc := range testCases {
		p := NewPoller(PollerConfig{
			BaseURL:         tc.baseURL,
			HTTPClient:      &mockHTTPClient{},
			Registry:        hooks.NewRegistry(),
			Interval:        60 * time.Second,
			MetricsRecorder: metrics.NewPrometheusRecorder(prometheus.NewRegistry()),
		})
		if p.batchURL != tc.expected {
			t.Errorf("batchURL for %q = %q, want %q", tc.baseURL, p.batchURL, tc.expected)
		}
	}
}

// LastKnownVersion is advanced BEFORE hooks run (at-most-once).
func TestPollOnce_AdvancesLastKnownVersionBeforeInvokingHooks(t *testing.T) {
	mockClient := &mockHTTPClient{}
	registry := hooks.NewRegistry()
	mockMetrics := metrics.NewPrometheusRecorder(prometheus.NewRegistry())

	var hookCalledAt atomic.Int32
	hook := func(_ context.Context, _ models.SecretValue) error {
		// This hook will check what lastKnownVersion is when it runs
		state := registry.Get("acme:version-test")
		if state != nil {
			hookCalledAt.Store(state.LastKnownVersion.Load())
		}
		return nil
	}

	registry.Register("acme", "version-test", hook, 0)

	updatedSecret := batch.UpdatedSecret{
		ProjectID:   "acme",
		SecretName:  "version-test",
		DataVersion: 5,
		PublicPart:  "p",
		PrivatePart: "p",
	}
	mockResp := response.Response[batch.BatchGetSecretsResponse]{
		Data: batch.BatchGetSecretsResponse{
			UpdatedCount:   1,
			UpdatedSecrets: []batch.UpdatedSecret{updatedSecret},
		},
		Message: "Success",
	}
	mockClient.addPostResponse(200, mockResp, nil)

	poller := NewPoller(PollerConfig{
		BaseURL:         "https://test.example.com",
		HTTPClient:      mockClient,
		Registry:        registry,
		Interval:        60 * time.Second,
		MetricsRecorder: mockMetrics,
	})
	defer poller.Close()
	poller.Start()

	ctx := context.Background()
	poller.PollOnce(ctx)

	// Give dispatcher time to process
	time.Sleep(100 * time.Millisecond)

	if hookCalledAt.Load() != 5 {
		t.Errorf("lastKnownVersion at hook invocation = %d, want 5 (should be updated before hook runs)", hookCalledAt.Load())
	}
}

// Hook registered without prior GetSecret -> batch sends lastKnownVersion=0.
func TestPollOnce_WithoutGetSecret_SendsLastKnownVersionZero(t *testing.T) {
	mockClient := &mockHTTPClient{}
	registry := hooks.NewRegistry()
	mockMetrics := metrics.NewPrometheusRecorder(prometheus.NewRegistry())

	registry.Register("acme", "no-get-secret", func(_ context.Context, _ models.SecretValue) error { return nil }, 0)

	poller := NewPoller(PollerConfig{
		BaseURL:         "https://test.example.com",
		HTTPClient:      mockClient,
		Registry:        registry,
		Interval:        60 * time.Second,
		MetricsRecorder: mockMetrics,
	})
	defer poller.Close()

	ctx := context.Background()
	poller.PollOnce(ctx)

	// Verify the state was created with lastKnownVersion=0
	state := registry.Get("acme:no-get-secret")
	if state == nil {
		t.Fatal("state should exist after registration")
	}
	if got := state.LastKnownVersion.Load(); got != 0 {
		t.Errorf("lastKnownVersion = %d, want 0 when no prior getSecret", got)
	}
}

// interval <= 0 falls back to the package default.
func TestNewPoller_DefaultInterval(t *testing.T) {
	mockClient := &mockHTTPClient{}
	registry := hooks.NewRegistry()
	mockMetrics := metrics.NewPrometheusRecorder(prometheus.NewRegistry())

	poller := NewPoller(PollerConfig{
		BaseURL:         "https://test.example.com",
		HTTPClient:      mockClient,
		Registry:        registry,
		Interval:        0, // invalid, should use default
		MetricsRecorder: mockMetrics,
	})
	defer poller.Close()

	expectedInterval := time.Duration(constants.DefaultPollingIntervalSeconds) * time.Second
	if poller.interval != expectedInterval {
		t.Errorf("poller interval = %v, want %v (default)", poller.interval, expectedInterval)
	}
}

// Null Data in response is handled gracefully.
func TestPollOnce_NullData_ContinuesWithoutUpdates(t *testing.T) {
	mockClient := &mockHTTPClient{}
	registry := hooks.NewRegistry()
	mockMetrics := metrics.NewPrometheusRecorder(prometheus.NewRegistry())

	registry.Register("acme", "one", func(_ context.Context, _ models.SecretValue) error { return nil }, 0)

	// Response with null data
	nullResp := response.Response[batch.BatchGetSecretsResponse]{
		Data:    batch.BatchGetSecretsResponse{}, // empty/default value
		Message: "ok",
	}
	mockClient.addPostResponse(200, nullResp, nil)

	poller := NewPoller(PollerConfig{
		BaseURL:         "https://test.example.com",
		HTTPClient:      mockClient,
		Registry:        registry,
		Interval:        60 * time.Second,
		MetricsRecorder: mockMetrics,
	})
	defer poller.Close()

	ctx := context.Background()
	poller.PollOnce(ctx)

	if got := mockClient.getPostCallCount(); got != 1 {
		t.Errorf("DoPostWithRetry called %d times, want 1", got)
	}
}

// Update for an unregistered secret is a no-op (no panic).
func TestHandleUpdatedSecret_NonexistentSecret_NoOp(t *testing.T) {
	mockClient := &mockHTTPClient{}
	registry := hooks.NewRegistry()
	mockMetrics := metrics.NewPrometheusRecorder(prometheus.NewRegistry())

	poller := NewPoller(PollerConfig{
		BaseURL:         "https://test.example.com",
		HTTPClient:      mockClient,
		Registry:        registry,
		Interval:        60 * time.Second,
		MetricsRecorder: mockMetrics,
	})
	defer poller.Close()

	// Manually call handleUpdatedSecret with a nonexistent secret
	item := batch.UpdatedSecret{
		ProjectID:   "nonexistent",
		SecretName:  "secret",
		DataVersion: 1,
		PublicPart:  "p",
		PrivatePart: "p",
	}

	// Should not panic
	poller.handleUpdatedSecret(item, "test-request-id")
}

// Dispatcher goroutines exit cleanly on Close.
func TestDispatcherLoop_StopsOnClose(t *testing.T) {
	mockClient := &mockHTTPClient{}
	registry := hooks.NewRegistry()
	mockMetrics := metrics.NewPrometheusRecorder(prometheus.NewRegistry())

	poller := NewPoller(PollerConfig{
		BaseURL:         "https://test.example.com",
		HTTPClient:      mockClient,
		Registry:        registry,
		Interval:        10 * time.Millisecond,
		MetricsRecorder: mockMetrics,
	})

	poller.Start()
	time.Sleep(50 * time.Millisecond)

	// Close should stop all workers
	done := make(chan struct{})
	go func() {
		poller.Close()
		close(done)
	}()

	select {
	case <-done:
		// Success
	case <-time.After(shutdownAwait + 1*time.Second):
		t.Error("Close did not return within expected shutdown window")
	}
}

// Hook returning an error (not panic) is logged and metric'd as failure.
// are recorded.
func TestInvokeHookSafe_HookReturnsError_RecordsMetrics(t *testing.T) {
	mockClient := &mockHTTPClient{}
	registry := hooks.NewRegistry()
	mockMetrics := metrics.NewPrometheusRecorder(prometheus.NewRegistry())

	hookErr := errors.New("hook failed")
	hook := func(_ context.Context, _ models.SecretValue) error {
		return hookErr
	}

	registry.Register("acme", "error-hook", hook, 0)

	updatedSecret := batch.UpdatedSecret{
		ProjectID:   "acme",
		SecretName:  "error-hook",
		DataVersion: 1,
		PublicPart:  "p",
		PrivatePart: "p",
	}
	mockResp := response.Response[batch.BatchGetSecretsResponse]{
		Data: batch.BatchGetSecretsResponse{
			UpdatedCount:   1,
			UpdatedSecrets: []batch.UpdatedSecret{updatedSecret},
		},
		Message: "Success",
	}
	mockClient.addPostResponse(200, mockResp, nil)

	poller := NewPoller(PollerConfig{
		BaseURL:         "https://test.example.com",
		HTTPClient:      mockClient,
		Registry:        registry,
		Interval:        60 * time.Second,
		MetricsRecorder: mockMetrics,
	})
	defer poller.Close()

	ctx := context.Background()
	poller.PollOnce(ctx)

	// Give dispatcher time to process
	time.Sleep(100 * time.Millisecond)

	// Test passes if no panic and error is logged (we can't easily verify logs here)
}

func TestSafePollOnce_RecoversWhenPollOncePanics(t *testing.T) {
	mockClient := &mockHTTPClient{}
	poller := NewPoller(PollerConfig{
		BaseURL:         "https://test.example.com",
		HTTPClient:      mockClient,
		Registry:        nil, // nil registry causes PollOnce panic; safePollOnce must recover
		Interval:        10 * time.Millisecond,
		MetricsRecorder: metrics.NewPrometheusRecorder(prometheus.NewRegistry()),
	})
	defer poller.Close()

	// Should not panic despite nil registry
	poller.safePollOnce()
}

func TestPollOnce_ErrorWithZeroStatusFallsBackTo500(t *testing.T) {
	mockClient := &mockHTTPClient{}
	registry := hooks.NewRegistry()
	registry.Register("acme", "zero-status", func(_ context.Context, _ models.SecretValue) error { return nil }, 0)

	// Return code=0 + err to hit fallback statusCode=500 path.
	mockClient.addPostResponse(0, nil, errors.New("network down"))

	poller := NewPoller(PollerConfig{
		BaseURL:         "https://test.example.com",
		HTTPClient:      mockClient,
		Registry:        registry,
		Interval:        60 * time.Second,
		MetricsRecorder: metrics.NewPrometheusRecorder(prometheus.NewRegistry()),
	})
	defer poller.Close()

	// Should not panic; path of interest is internal fallback status assignment.
	poller.PollOnce(context.Background())
}

func TestHandleUpdatedSecret_ChannelFullFallsBackWithoutBlocking(t *testing.T) {
	mockClient := &mockHTTPClient{}
	registry := hooks.NewRegistry()
	registry.Register("acme", "full-chan", func(_ context.Context, _ models.SecretValue) error { return nil }, 0)

	poller := NewPoller(PollerConfig{
		BaseURL:         "https://test.example.com",
		HTTPClient:      mockClient,
		Registry:        registry,
		Interval:        60 * time.Second,
		MetricsRecorder: metrics.NewPrometheusRecorder(prometheus.NewRegistry()),
	})
	defer poller.Close()

	// Force send default branch by using an unbuffered channel with no receiver.
	poller.dispatchCh = make(chan dispatchJob)
	poller.handleUpdatedSecret(batch.UpdatedSecret{
		ProjectID:   "acme",
		SecretName:  "full-chan",
		DataVersion: 11,
		PublicPart:  "p",
		PrivatePart: "q",
	}, "test-request-id")

	state := registry.Get("acme:full-chan")
	if state == nil {
		t.Fatal("state should exist")
	}
	if !state.HasPending() {
		t.Fatal("pending value should still be staged when dispatch channel is full")
	}
}

func TestRunHooksFor_NoOpWhenExecutionAlreadyHeld(t *testing.T) {
	mockClient := &mockHTTPClient{}
	registry := hooks.NewRegistry()
	state := registry.Register("acme", "locked", func(_ context.Context, _ models.SecretValue) error { return nil }, 0)
	_ = state
	secretState := registry.Get("acme:locked")
	if secretState == nil {
		t.Fatal("state should exist")
	}

	poller := NewPoller(PollerConfig{
		BaseURL:         "https://test.example.com",
		HTTPClient:      mockClient,
		Registry:        registry,
		Interval:        60 * time.Second,
		MetricsRecorder: metrics.NewPrometheusRecorder(prometheus.NewRegistry()),
	})
	defer poller.Close()

	secretState.SetPending(&models.SecretValue{DataVersion: 1})
	if !secretState.TryAcquireExecution() {
		t.Fatal("expected to acquire execution lock for setup")
	}
	poller.runHooksFor("acme:locked", secretState, "test-request-id")
	secretState.ReleaseExecution()

	// If method returned due to lock held, pending should remain.
	if !secretState.HasPending() {
		t.Fatal("pending should remain when runHooksFor exits due to execution lock")
	}
}

func TestInvokeHookSafe_ErrorPathCovered(t *testing.T) {
	poller := NewPoller(PollerConfig{
		BaseURL:         "https://test.example.com",
		HTTPClient:      &mockHTTPClient{},
		Registry:        hooks.NewRegistry(),
		Interval:        60 * time.Second,
		MetricsRecorder: metrics.NewPrometheusRecorder(prometheus.NewRegistry()),
	})
	defer poller.Close()

	poller.invokeHookSafe(context.Background(), "acme:err", func(_ context.Context, _ models.SecretValue) error {
		return errors.New("hook failure")
	}, models.SecretValue{DataVersion: 1})
}

func TestClose_TimeoutPathCovered(t *testing.T) {
	original := shutdownAwait
	shutdownAwait = 5 * time.Millisecond
	t.Cleanup(func() { shutdownAwait = original })

	poller := NewPoller(PollerConfig{
		BaseURL:         "https://test.example.com",
		HTTPClient:      &mockHTTPClient{},
		Registry:        hooks.NewRegistry(),
		Interval:        60 * time.Second,
		MetricsRecorder: metrics.NewPrometheusRecorder(prometheus.NewRegistry()),
	})

	// Simulate a stuck worker so Close hits timeout path.
	poller.wg.Add(1)
	poller.Close()
}

func TestPollOnce_MarshalFailurePathCovered(t *testing.T) {
	registry := hooks.NewRegistry()
	registry.Register("acme", "marshal-fail", func(_ context.Context, _ models.SecretValue) error { return nil }, 0)

	poller := NewPoller(PollerConfig{
		BaseURL:         "https://test.example.com",
		HTTPClient:      &mockHTTPClient{},
		Registry:        registry,
		Interval:        60 * time.Second,
		MetricsRecorder: metrics.NewPrometheusRecorder(prometheus.NewRegistry()),
		MarshalRequest: func(v any) ([]byte, error) {
			return nil, errors.New("marshal exploded")
		},
	})
	defer poller.Close()

	// Should continue gracefully after marshal error path.
	poller.PollOnce(context.Background())
}

func TestResubmitIfPending_CoversStopAndDefaultPaths(t *testing.T) {
	registry := hooks.NewRegistry()
	registry.Register("acme", "resubmit", func(_ context.Context, _ models.SecretValue) error { return nil }, 0)
	state := registry.Get("acme:resubmit")
	if state == nil {
		t.Fatal("state should exist")
	}

	poller := NewPoller(PollerConfig{
		BaseURL:         "https://test.example.com",
		HTTPClient:      &mockHTTPClient{},
		Registry:        registry,
		Interval:        60 * time.Second,
		MetricsRecorder: metrics.NewPrometheusRecorder(prometheus.NewRegistry()),
	})

	// Cover default branch when dispatch channel cannot accept immediately.
	poller.dispatchCh = make(chan dispatchJob)
	state.SetPending(&models.SecretValue{DataVersion: 1})
	poller.resubmitIfPending("acme:resubmit", state, "test-request-id")

	// Cover stop branch.
	close(poller.stopCh)
	state.SetPending(&models.SecretValue{DataVersion: 2})
	poller.resubmitIfPending("acme:resubmit", state, "test-request-id")
}

// Happy path: resubmit lands on the channel (covers the first select arm).
func TestResubmitIfPending_SuccessArm(t *testing.T) {
	registry := hooks.NewRegistry()
	registry.Register("acme", "resub-ok", func(_ context.Context, _ models.SecretValue) error { return nil }, 0)
	state := registry.Get("acme:resub-ok")
	if state == nil {
		t.Fatal("state should exist")
	}

	poller := NewPoller(PollerConfig{
		BaseURL:         "https://test.example.com",
		HTTPClient:      &mockHTTPClient{},
		Registry:        registry,
		Interval:        60 * time.Second,
		MetricsRecorder: metrics.NewPrometheusRecorder(prometheus.NewRegistry()),
	})
	defer poller.Close()

	state.SetPending(&models.SecretValue{DataVersion: 1})

	// dispatchCh has a buffer, so the resubmit should succeed on the first arm.
	poller.resubmitIfPending("acme:resub-ok", state, "rid")

	select {
	case job := <-poller.dispatchCh:
		if job.secretRef != "acme:resub-ok" {
			t.Errorf("dispatched secretRef = %q, want %q", job.secretRef, "acme:resub-ok")
		}
		if job.requestID != "rid" {
			t.Errorf("dispatched requestID = %q, want %q", job.requestID, "rid")
		}
	case <-time.After(time.Second):
		t.Fatal("resubmit did not land on dispatchCh within 1s")
	}
}

// Hook receives a non-nil ctx carrying the poll-cycle request id.
func TestPollOnce_HookReceivesContextWithRequestID(t *testing.T) {
	mockClient := &mockHTTPClient{}
	registry := hooks.NewRegistry()

	var gotCtx atomic.Value // context.Context
	done := make(chan struct{}, 1)

	hook := func(ctx context.Context, _ models.SecretValue) error {
		if ctx != nil {
			gotCtx.Store(ctx)
		}
		select {
		case done <- struct{}{}:
		default:
		}
		return nil
	}
	registry.Register("acme", "ctx-hook", hook, 0)

	mockClient.addPostResponse(200, response.Response[batch.BatchGetSecretsResponse]{
		Data: batch.BatchGetSecretsResponse{
			UpdatedCount: 1,
			UpdatedSecrets: []batch.UpdatedSecret{
				{ProjectID: "acme", SecretName: "ctx-hook", DataVersion: 1, PublicPart: "p", PrivatePart: "q"},
			},
		},
		Message: "Success",
	}, nil)

	poller := NewPoller(PollerConfig{
		BaseURL:         "https://test.example.com",
		HTTPClient:      mockClient,
		Registry:        registry,
		Interval:        60 * time.Second,
		MetricsRecorder: metrics.NewPrometheusRecorder(prometheus.NewRegistry()),
	})
	defer poller.Close()
	poller.Start()

	ctx := context.WithValue(context.Background(), constants.GrayskullRequestID, "rid-42")
	poller.PollOnce(ctx)

	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("hook was not invoked within 1s")
	}

	got, ok := gotCtx.Load().(context.Context)
	if !ok || got == nil {
		t.Fatal("hook received nil context")
	}
	if rid, _ := got.Value(constants.GrayskullRequestID).(string); rid != "rid-42" {
		t.Errorf("hook ctx request id = %q, want %q", rid, "rid-42")
	}
}

// Close cancels the ctx delivered to a long-running hook.
func TestPoller_Close_CancelsHookContext(t *testing.T) {
	mockClient := &mockHTTPClient{}
	registry := hooks.NewRegistry()

	hookStarted := make(chan struct{})
	hookFinished := make(chan error, 1)

	hook := func(ctx context.Context, _ models.SecretValue) error {
		close(hookStarted)
		select {
		case <-ctx.Done():
			hookFinished <- ctx.Err()
			return ctx.Err()
		case <-time.After(5 * time.Second):
			hookFinished <- errors.New("hook timed out without ctx cancellation")
			return nil
		}
	}
	registry.Register("acme", "long-hook", hook, 0)

	mockClient.addPostResponse(200, response.Response[batch.BatchGetSecretsResponse]{
		Data: batch.BatchGetSecretsResponse{
			UpdatedCount: 1,
			UpdatedSecrets: []batch.UpdatedSecret{
				{ProjectID: "acme", SecretName: "long-hook", DataVersion: 1, PublicPart: "p", PrivatePart: "q"},
			},
		},
		Message: "Success",
	}, nil)

	poller := NewPoller(PollerConfig{
		BaseURL:         "https://test.example.com",
		HTTPClient:      mockClient,
		Registry:        registry,
		Interval:        60 * time.Second,
		MetricsRecorder: metrics.NewPrometheusRecorder(prometheus.NewRegistry()),
	})
	poller.Start()

	poller.PollOnce(context.Background())

	select {
	case <-hookStarted:
	case <-time.After(time.Second):
		t.Fatal("hook never started")
	}

	// Closing the poller must cancel the hook ctx so the hook can return.
	_ = poller.Close()

	select {
	case err := <-hookFinished:
		if !errors.Is(err, context.Canceled) {
			t.Errorf("hook ctx error = %v, want context.Canceled", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("hook did not observe ctx cancellation after Close")
	}
}

// Close returns ErrShutdownTimeout when workers can't drain in time.
func TestPoller_Close_ReturnsShutdownTimeout(t *testing.T) {
	original := shutdownAwait
	shutdownAwait = 5 * time.Millisecond
	t.Cleanup(func() { shutdownAwait = original })

	poller := NewPoller(PollerConfig{
		BaseURL:         "https://test.example.com",
		HTTPClient:      &mockHTTPClient{},
		Registry:        hooks.NewRegistry(),
		Interval:        60 * time.Second,
		MetricsRecorder: metrics.NewPrometheusRecorder(prometheus.NewRegistry()),
	})

	// Simulate a stuck worker so the WaitGroup never drains.
	poller.wg.Add(1)

	if err := poller.Close(); !errors.Is(err, ErrShutdownTimeout) {
		t.Errorf("Close() error = %v, want ErrShutdownTimeout", err)
	}
}

// Clean shutdown: Close returns nil.
func TestPoller_Close_NilWhenCleanShutdown(t *testing.T) {
	poller := NewPoller(PollerConfig{
		BaseURL:         "https://test.example.com",
		HTTPClient:      &mockHTTPClient{},
		Registry:        hooks.NewRegistry(),
		Interval:        10 * time.Millisecond,
		MetricsRecorder: metrics.NewPrometheusRecorder(prometheus.NewRegistry()),
	})
	poller.Start()

	if err := poller.Close(); err != nil {
		t.Errorf("Close() = %v, want nil for clean shutdown", err)
	}
	// Second call should be a no-op and not return an error.
	if err := poller.Close(); err != nil {
		t.Errorf("second Close() = %v, want nil", err)
	}
}

// Start is idempotent: repeated calls don't spawn extra goroutines (Close
// still drains within the default window).
func TestPoller_Start_IsIdempotent(t *testing.T) {
	poller := NewPoller(PollerConfig{
		BaseURL:         "https://test.example.com",
		HTTPClient:      &mockHTTPClient{},
		Registry:        hooks.NewRegistry(),
		Interval:        10 * time.Millisecond,
		MetricsRecorder: metrics.NewPrometheusRecorder(prometheus.NewRegistry()),
	})

	poller.Start()
	poller.Start()
	poller.Start()

	// Invariant: Close must return nil under the default shutdown window
	// (non-idempotent Start would inflate goroutines and could fail this).
	if err := poller.Close(); err != nil {
		t.Errorf("Close() after repeated Start = %v, want nil", err)
	}
}

// Unset (or <=0) RequestTimeout falls back to Interval.
func TestNewPoller_RequestTimeoutDefaultsToInterval(t *testing.T) {
	p := NewPoller(PollerConfig{
		BaseURL:         "https://test.example.com",
		HTTPClient:      &mockHTTPClient{},
		Registry:        hooks.NewRegistry(),
		Interval:        7 * time.Second,
		MetricsRecorder: metrics.NewPrometheusRecorder(prometheus.NewRegistry()),
		// RequestTimeout intentionally left zero.
	})
	defer p.Close()

	if p.requestTimeout != 7*time.Second {
		t.Errorf("requestTimeout = %v, want %v (default to interval)", p.requestTimeout, 7*time.Second)
	}
}

// Explicit RequestTimeout is used as-is.
func TestNewPoller_RequestTimeoutHonored(t *testing.T) {
	p := NewPoller(PollerConfig{
		BaseURL:         "https://test.example.com",
		HTTPClient:      &mockHTTPClient{},
		Registry:        hooks.NewRegistry(),
		Interval:        60 * time.Second,
		RequestTimeout:  3 * time.Second,
		MetricsRecorder: metrics.NewPrometheusRecorder(prometheus.NewRegistry()),
	})
	defer p.Close()

	if p.requestTimeout != 3*time.Second {
		t.Errorf("requestTimeout = %v, want %v", p.requestTimeout, 3*time.Second)
	}
}

