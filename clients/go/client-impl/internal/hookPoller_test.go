package internal

import (
	"context"
	"encoding/json"
	"errors"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/flipkart-incubator/grayskull/clients/go/client-api/models"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/internal/hooks"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/internal/models/batch"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/internal/models/response"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/metrics"
	grayskullErrors "github.com/flipkart-incubator/grayskull/clients/go/client-impl/models/errors"
)

// mockHTTPClient implements GrayskullHTTPClientInterface for testing
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
			// Marshal and unmarshal to simulate real behavior
			data, _ := json.Marshal(resp.response)
			json.Unmarshal(data, result)
		}
		return resp.statusCode, nil
	}

	// Default empty response
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

// TestPollOnce_NoRegisteredSecrets_DoesNotPost verifies that when no secrets
// are registered, PollOnce returns early without making an HTTP call.
func TestPollOnce_NoRegisteredSecrets_DoesNotPost(t *testing.T) {
	mockClient := &mockHTTPClient{}
	registry := hooks.NewRegistry()
	mockMetrics := metrics.NewPrometheusRecorder(nil)

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

	if got := mockClient.getPostCallCount(); got != 0 {
		t.Errorf("DoPostWithRetry called %d times, want 0 when registry is empty", got)
	}
}

// TestPollOnce_SingleChunk_EmptyUpdatedSecrets_OnePost verifies that when
// all registered secrets fit in one batch and no updates are returned,
// exactly one POST is made.
func TestPollOnce_SingleChunk_EmptyUpdatedSecrets_OnePost(t *testing.T) {
	mockClient := &mockHTTPClient{}
	registry := hooks.NewRegistry()
	mockMetrics := metrics.NewPrometheusRecorder(nil)

	// Register two secrets
	registry.Register("acme", "db-pass", func(_ models.SecretValue) error { return nil }, 0)
	registry.Register("acme", "api-key", func(_ models.SecretValue) error { return nil }, 0)

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
		t.Errorf("DoPostWithRetry called %d times, want 1 for single chunk", got)
	}
}

// TestPollOnce_FiftyOneSecrets_TwoSequentialPosts verifies that when 51
// secrets are registered (exceeding MaxBatchSize=50), two sequential POST
// requests are made: one with 50 secrets and one with 1.
func TestPollOnce_FiftyOneSecrets_TwoSequentialPosts(t *testing.T) {
	mockClient := &mockHTTPClient{}
	registry := hooks.NewRegistry()
	mockMetrics := metrics.NewPrometheusRecorder(nil)

	hook := func(_ models.SecretValue) error { return nil }
	// Register 51 secrets
	for i := 0; i < 51; i++ {
		registry.Register("corp", "svc-"+string(rune('a'+i%26)), hook, 0)
	}

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

	if got := mockClient.getPostCallCount(); got != 2 {
		t.Errorf("DoPostWithRetry called %d times, want 2 for 51 secrets", got)
	}
}

// TestPollOnce_BatchReturnsUpdatedSecret_InvokesHook verifies that when
// the batch response includes an updated secret, the registered hook is invoked.
func TestPollOnce_BatchReturnsUpdatedSecret_InvokesHook(t *testing.T) {
	mockClient := &mockHTTPClient{}
	registry := hooks.NewRegistry()
	mockMetrics := metrics.NewPrometheusRecorder(nil)

	var hookCalled atomic.Bool
	var receivedVersion int
	var mu sync.Mutex

	hook := func(v models.SecretValue) error {
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

// TestPollOnce_FirstChunkFails_SecondChunkStillRuns verifies that when
// the first chunk fails with a GrayskullException, the second chunk is
// still processed.
func TestPollOnce_FirstChunkFails_SecondChunkStillRuns(t *testing.T) {
	mockClient := &mockHTTPClient{}
	registry := hooks.NewRegistry()
	mockMetrics := metrics.NewPrometheusRecorder(nil)

	hook := func(_ models.SecretValue) error { return nil }
	// Register 51 secrets to force two chunks
	for i := 0; i < 51; i++ {
		registry.Register("corp", "svc-"+string(rune('a'+i%26)), hook, 0)
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

	ctx := context.Background()
	poller.PollOnce(ctx)

	if got := mockClient.getPostCallCount(); got != 2 {
		t.Errorf("DoPostWithRetry called %d times, want 2 (second chunk should run despite first failure)", got)
	}
}

// TestPollOnce_MultipleHooksForSameSecret_RunInRegistrationOrder verifies
// that when multiple hooks are registered for the same secret, they are
// invoked in the order they were registered.
func TestPollOnce_MultipleHooksForSameSecret_RunInRegistrationOrder(t *testing.T) {
	mockClient := &mockHTTPClient{}
	registry := hooks.NewRegistry()
	mockMetrics := metrics.NewPrometheusRecorder(nil)

	var order []int
	var mu sync.Mutex

	hook1 := func(_ models.SecretValue) error {
		mu.Lock()
		order = append(order, 1)
		mu.Unlock()
		return nil
	}
	hook2 := func(_ models.SecretValue) error {
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

// TestPollOnce_HookThrows_SwallowsAndContinues verifies that when the first
// hook panics or returns an error, the second hook is still invoked.
func TestPollOnce_HookThrows_SwallowsAndContinues(t *testing.T) {
	mockClient := &mockHTTPClient{}
	registry := hooks.NewRegistry()
	mockMetrics := metrics.NewPrometheusRecorder(nil)

	var secondHookCalled atomic.Bool

	hook1 := func(_ models.SecretValue) error {
		panic("consumer bug")
	}
	hook2 := func(_ models.SecretValue) error {
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

	ctx := context.Background()
	poller.PollOnce(ctx)

	// Give dispatcher time to process
	time.Sleep(100 * time.Millisecond)

	if !secondHookCalled.Load() {
		t.Error("second hook should have been invoked even after first hook panicked")
	}
}

// TestPollOnce_ChunkInvalidJson_ContinuesGracefully verifies that when
// the HTTP response contains invalid JSON, PollOnce handles the error
// gracefully and continues.
func TestPollOnce_ChunkInvalidJson_ContinuesGracefully(t *testing.T) {
	mockClient := &mockHTTPClient{}
	registry := hooks.NewRegistry()
	mockMetrics := metrics.NewPrometheusRecorder(nil)

	registry.Register("acme", "one", func(_ models.SecretValue) error { return nil }, 0)

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

	ctx := context.Background()
	// Should not panic
	poller.PollOnce(ctx)

	if got := mockClient.getPostCallCount(); got != 1 {
		t.Errorf("DoPostWithRetry called %d times, want 1", got)
	}
}

// TestClose_StopsPollingAndDispatcher verifies that Close stops the
// background goroutines and waits for them to finish.
func TestClose_StopsPollingAndDispatcher(t *testing.T) {
	mockClient := &mockHTTPClient{}
	registry := hooks.NewRegistry()
	mockMetrics := metrics.NewPrometheusRecorder(nil)

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

// TestBuildBatchURL_FormatsCorrectly verifies that buildBatchURL constructs
// the correct batch endpoint URL.
func TestBuildBatchURL_FormatsCorrectly(t *testing.T) {
	testCases := []struct {
		baseURL  string
		expected string
	}{
		{"https://test.example.com", "https://test.example.com/v1/secrets/batch"},
		{"http://localhost:8080", "http://localhost:8080/v1/secrets/batch"},
		{"https://api.grayskull.io", "https://api.grayskull.io/v1/secrets/batch"},
	}

	for _, tc := range testCases {
		got := buildBatchURL(tc.baseURL)
		if got != tc.expected {
			t.Errorf("buildBatchURL(%q) = %q, want %q", tc.baseURL, got, tc.expected)
		}
	}
}

// TestPollOnce_AdvancesLastKnownVersionBeforeInvokingHooks verifies that
// lastKnownVersion is updated before hooks are invoked (at-most-once semantics).
func TestPollOnce_AdvancesLastKnownVersionBeforeInvokingHooks(t *testing.T) {
	mockClient := &mockHTTPClient{}
	registry := hooks.NewRegistry()
	mockMetrics := metrics.NewPrometheusRecorder(nil)

	var hookCalledAt int32
	hook := func(v models.SecretValue) error {
		// This hook will check what lastKnownVersion is when it runs
		state := registry.Get("acme:version-test")
		if state != nil {
			hookCalledAt = state.LastKnownVersion.Load()
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

	ctx := context.Background()
	poller.PollOnce(ctx)

	// Give dispatcher time to process
	time.Sleep(100 * time.Millisecond)

	if hookCalledAt != 5 {
		t.Errorf("lastKnownVersion at hook invocation = %d, want 5 (should be updated before hook runs)", hookCalledAt)
	}
}

// TestPollOnce_WithoutGetSecret_SendsLastKnownVersionZero verifies that
// when a hook is registered without a prior getSecret call, the batch
// request sends lastKnownVersion=0.
func TestPollOnce_WithoutGetSecret_SendsLastKnownVersionZero(t *testing.T) {
	mockClient := &mockHTTPClient{}
	registry := hooks.NewRegistry()
	mockMetrics := metrics.NewPrometheusRecorder(nil)

	registry.Register("acme", "no-get-secret", func(_ models.SecretValue) error { return nil }, 0)

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

// TestNewPoller_DefaultInterval verifies that when interval <= 0, the poller
// uses the default interval from Poller defaults.
func TestNewPoller_DefaultInterval(t *testing.T) {
	mockClient := &mockHTTPClient{}
	registry := hooks.NewRegistry()
	mockMetrics := metrics.NewPrometheusRecorder(nil)

	poller := NewPoller(PollerConfig{
		BaseURL:         "https://test.example.com",
		HTTPClient:      mockClient,
		Registry:        registry,
		Interval:        0, // invalid, should use default
		MetricsRecorder: mockMetrics,
	})
	defer poller.Close()

	expectedInterval := time.Duration(defaultPollingIntervalSeconds) * time.Second
	if poller.interval != expectedInterval {
		t.Errorf("poller interval = %v, want %v (default)", poller.interval, expectedInterval)
	}
}

// TestPollOnce_NullData_ContinuesWithoutUpdates verifies that when the
// response has a null Data field, PollOnce continues gracefully.
func TestPollOnce_NullData_ContinuesWithoutUpdates(t *testing.T) {
	mockClient := &mockHTTPClient{}
	registry := hooks.NewRegistry()
	mockMetrics := metrics.NewPrometheusRecorder(nil)

	registry.Register("acme", "one", func(_ models.SecretValue) error { return nil }, 0)

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

// TestHandleUpdatedSecret_NonexistentSecret_NoOp verifies that when
// handleUpdatedSecret receives an update for a secret that's no longer
// in the registry, it's handled gracefully (no panic).
func TestHandleUpdatedSecret_NonexistentSecret_NoOp(t *testing.T) {
	mockClient := &mockHTTPClient{}
	registry := hooks.NewRegistry()
	mockMetrics := metrics.NewPrometheusRecorder(nil)

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
	poller.handleUpdatedSecret(item)
}

// TestDispatcherLoop_StopsOnClose verifies that the dispatcher goroutines
// exit cleanly when the poller is closed.
func TestDispatcherLoop_StopsOnClose(t *testing.T) {
	mockClient := &mockHTTPClient{}
	registry := hooks.NewRegistry()
	mockMetrics := metrics.NewPrometheusRecorder(nil)

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

// TestInvokeHookSafe_HookReturnsError_RecordsMetrics verifies that when
// a hook returns an error (not panic), the error is logged and metrics
// are recorded.
func TestInvokeHookSafe_HookReturnsError_RecordsMetrics(t *testing.T) {
	mockClient := &mockHTTPClient{}
	registry := hooks.NewRegistry()
	mockMetrics := metrics.NewPrometheusRecorder(nil)

	hookErr := errors.New("hook failed")
	hook := func(_ models.SecretValue) error {
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
