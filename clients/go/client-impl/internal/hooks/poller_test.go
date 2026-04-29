// Concurrency tests for HookRefreshPoller mirroring
// com.flipkart.grayskull.HookRefreshPollerConcurrencyTest from the Java SDK.
//
// We construct the poller directly with a mock HTTP client (no real network)
// and a long polling interval so the background scheduler does not interfere
// with explicit PollOnce() calls.
package hooks

import (
	"context"
	"encoding/json"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	apihooks "github.com/flipkart-incubator/grayskull/clients/go/client-api/hooks"
	apimodels "github.com/flipkart-incubator/grayskull/clients/go/client-api/models"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/internal/models/response"
)

const (
	testBaseURL          = "https://test.grayskull.com"
	testBatchURL         = "https://test.grayskull.com/v1/secrets/batch"
	longIntervalSeconds  = 3600
	updateDeliveryWindow = 2 * time.Second
)

// stubHTTPClient is a deterministic httpDoer for tests. Each call returns the
// next queued payload (or an empty BatchGetSecretsResponse if none remain).
type stubHTTPClient struct {
	mu        sync.Mutex
	responses []response.BatchGetSecretsResponse
	calls     int
	urlSeen   string
	bodiesIn  [][]byte
}

func (s *stubHTTPClient) DoPostWithRetry(ctx context.Context, url string, body []byte, result any) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.calls++
	s.urlSeen = url
	if body != nil {
		// Defensive copy: callers may reuse the buffer.
		copyBody := make([]byte, len(body))
		copy(copyBody, body)
		s.bodiesIn = append(s.bodiesIn, copyBody)
	}

	var payload response.BatchGetSecretsResponse
	if len(s.responses) > 0 {
		payload = s.responses[0]
		s.responses = s.responses[1:]
	}
	envelope := response.Response[response.BatchGetSecretsResponse]{Data: payload}
	raw, _ := json.Marshal(envelope)
	if err := json.Unmarshal(raw, result); err != nil {
		return 500, err
	}
	return 200, nil
}

func (s *stubHTTPClient) callCount() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.calls
}

func (s *stubHTTPClient) lastURL() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.urlSeen
}

func (s *stubHTTPClient) queueResponse(r response.BatchGetSecretsResponse) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.responses = append(s.responses, r)
}

func (s *stubHTTPClient) bodies() [][]byte {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make([][]byte, len(s.bodiesIn))
	copy(out, s.bodiesIn)
	return out
}

func newTestPoller(t *testing.T, http *stubHTTPClient) *HookRefreshPoller {
	t.Helper()
	p := NewHookRefreshPoller(PollerConfig{
		HTTPClient:      http,
		BaseURL:         testBaseURL,
		IntervalSeconds: longIntervalSeconds,
	})
	t.Cleanup(p.Close)
	return p
}

// 1. Parallel Register on the same secret: all hooks must be retained.
//    Mirrors HookRefreshPollerConcurrencyTest#parallelRegister_sameSecret_allHooksRetained.
func TestPoller_ParallelRegisterSameSecret_AllHooksRetained(t *testing.T) {
	stub := &stubHTTPClient{}
	p := newTestPoller(t, stub)

	const goroutines = 32
	const hooksPerGoroutine = 50
	expected := goroutines * hooksPerGoroutine

	var wg sync.WaitGroup
	wg.Add(goroutines)
	start := make(chan struct{})
	for i := 0; i < goroutines; i++ {
		go func() {
			defer wg.Done()
			<-start
			for j := 0; j < hooksPerGoroutine; j++ {
				_ = p.Register("acme", "shared",
					apihooks.SecretRefreshHook(func(s apimodels.SecretValue) error { return nil }))
			}
		}()
	}
	close(start)
	wg.Wait()

	state := p.lookup("acme:shared")
	require.NotNil(t, state)
	assert.Equal(t, expected, state.HookCount(),
		"all concurrent registrations must be retained")
}

// 2. Register/unregister race: every register paired with an unregister leaves
//    the registry empty.
//    Mirrors parallelRegisterUnregister_finalStateConsistent.
func TestPoller_ParallelRegisterUnregister_FinalStateEmpty(t *testing.T) {
	stub := &stubHTTPClient{}
	p := newTestPoller(t, stub)

	const pairs = 200
	var wg sync.WaitGroup
	wg.Add(pairs * 2)
	start := make(chan struct{})

	handles := make(chan apihooks.RefreshHandlerRef, pairs)

	for i := 0; i < pairs; i++ {
		go func() {
			defer wg.Done()
			<-start
			handles <- p.Register("acme", "race",
				apihooks.SecretRefreshHook(func(s apimodels.SecretValue) error { return nil }))
		}()
		go func() {
			defer wg.Done()
			<-start
			deadline := time.Now().Add(5 * time.Second)
			for {
				select {
				case h := <-handles:
					h.Unregister()
					return
				default:
					if time.Now().After(deadline) {
						return
					}
					time.Sleep(time.Millisecond)
				}
			}
		}()
	}
	close(start)
	wg.Wait()

	// Drain stragglers (a consumer may have given up before pairing).
	close(handles)
	for h := range handles {
		h.Unregister()
	}

	p.registryMu.Lock()
	size := len(p.registry)
	p.registryMu.Unlock()
	assert.Equal(t, 0, size, "every register paired with an unregister; registry must be empty")
}

// 3. Latest-wins coalescing: while a slow hook blocks on v=1, two more polls
//    deliver v=2, v=3. After release, only v=1 (already in flight) and the
//    latest (v=3) must be observed.
//    Mirrors latestWinsCoalescing_newerValueDeliveredAfterSlowHook.
func TestPoller_LatestWinsCoalescing(t *testing.T) {
	stub := &stubHTTPClient{}
	p := newTestPoller(t, stub)

	hookEntered := make(chan struct{})
	releaseFirst := make(chan struct{})
	var deliveredMu sync.Mutex
	var delivered []int

	hook := apihooks.SecretRefreshHook(func(v apimodels.SecretValue) error {
		deliveredMu.Lock()
		first := len(delivered) == 0
		delivered = append(delivered, v.DataVersion)
		deliveredMu.Unlock()
		if first {
			close(hookEntered)
			<-releaseFirst
		}
		return nil
	})
	p.Register("acme", "fast", hook)

	stub.queueResponse(response.BatchGetSecretsResponse{
		UpdatedCount:   1,
		UpdatedSecrets: []response.UpdatedSecret{{ProjectID: "acme", SecretName: "fast", DataVersion: 1, PublicPart: "p", PrivatePart: "q"}},
	})
	stub.queueResponse(response.BatchGetSecretsResponse{
		UpdatedCount:   1,
		UpdatedSecrets: []response.UpdatedSecret{{ProjectID: "acme", SecretName: "fast", DataVersion: 2, PublicPart: "p", PrivatePart: "q"}},
	})
	stub.queueResponse(response.BatchGetSecretsResponse{
		UpdatedCount:   1,
		UpdatedSecrets: []response.UpdatedSecret{{ProjectID: "acme", SecretName: "fast", DataVersion: 3, PublicPart: "p", PrivatePart: "q"}},
	})

	p.PollOnce()
	require.Eventually(t, func() bool {
		select {
		case <-hookEntered:
			return true
		default:
			return false
		}
	}, updateDeliveryWindow, 10*time.Millisecond, "first hook invocation must start")

	p.PollOnce()
	p.PollOnce()
	close(releaseFirst)

	require.Eventually(t, func() bool {
		deliveredMu.Lock()
		defer deliveredMu.Unlock()
		for _, v := range delivered {
			if v == 3 {
				return true
			}
		}
		return false
	}, updateDeliveryWindow, 10*time.Millisecond, "v=3 must eventually be delivered")

	deliveredMu.Lock()
	defer deliveredMu.Unlock()
	assert.Contains(t, delivered, 1, "first (blocked) version must have been delivered: %v", delivered)
	assert.Contains(t, delivered, 3, "latest version must eventually be delivered: %v", delivered)
	// No older version may appear after v=3.
	lastIdx := -1
	for i, v := range delivered {
		if v == 3 {
			lastIdx = i
		}
	}
	for i := lastIdx + 1; i < len(delivered); i++ {
		assert.GreaterOrEqual(t, delivered[i], 3,
			"no older version must be delivered after v=3: %v", delivered)
	}
}

// 4. Wake-up race: an update placed on PendingUpdate while the dispatcher is
//    inside its drain loop must still be delivered.
//    Mirrors wakeUpRace_updateBetweenDrainAndRelease_isDelivered.
func TestPoller_WakeUpRace_LateUpdateIsDelivered(t *testing.T) {
	stub := &stubHTTPClient{}
	p := newTestPoller(t, stub)

	firstReceived := make(chan struct{})
	hold := make(chan struct{})
	var seenMu sync.Mutex
	var seen []int

	hook := apihooks.SecretRefreshHook(func(v apimodels.SecretValue) error {
		seenMu.Lock()
		first := len(seen) == 0
		seen = append(seen, v.DataVersion)
		seenMu.Unlock()
		if first {
			close(firstReceived)
			<-hold
		}
		return nil
	})
	p.Register("acme", "wake", hook)

	stub.queueResponse(response.BatchGetSecretsResponse{
		UpdatedCount:   1,
		UpdatedSecrets: []response.UpdatedSecret{{ProjectID: "acme", SecretName: "wake", DataVersion: 1, PublicPart: "p", PrivatePart: "q"}},
	})
	p.PollOnce()
	<-firstReceived

	state := p.lookup("acme:wake")
	require.NotNil(t, state)
	state.PendingUpdate.Store(&apimodels.SecretValue{DataVersion: 99, PublicPart: "p", PrivatePart: "q"})

	close(hold)

	require.Eventually(t, func() bool {
		seenMu.Lock()
		defer seenMu.Unlock()
		for _, v := range seen {
			if v == 99 {
				return true
			}
		}
		return false
	}, updateDeliveryWindow, 10*time.Millisecond,
		"update set on SecretState during dispatch must be delivered")
}

// 5. Per-secret non-reentrancy: only one dispatcher worker may execute hooks
//    for a given secret at a time.
//    Mirrors perSecretDispatchIsNonReentrant_noConcurrentInvocations.
func TestPoller_PerSecretDispatchIsNonReentrant(t *testing.T) {
	stub := &stubHTTPClient{}
	p := newTestPoller(t, stub)

	var inFlight atomic.Int32
	var maxInFlight atomic.Int32

	hook := apihooks.SecretRefreshHook(func(v apimodels.SecretValue) error {
		now := inFlight.Add(1)
		for {
			cur := maxInFlight.Load()
			if now <= cur || maxInFlight.CompareAndSwap(cur, now) {
				break
			}
		}
		time.Sleep(40 * time.Millisecond)
		inFlight.Add(-1)
		return nil
	})
	p.Register("acme", "serial", hook)

	for v := int32(1); v <= 3; v++ {
		stub.queueResponse(response.BatchGetSecretsResponse{
			UpdatedCount:   1,
			UpdatedSecrets: []response.UpdatedSecret{{ProjectID: "acme", SecretName: "serial", DataVersion: v, PublicPart: "p", PrivatePart: "q"}},
		})
	}

	p.PollOnce()
	p.PollOnce()
	p.PollOnce()

	// Wait for at least one delivery; non-reentrancy is on maxInFlight.
	time.Sleep(250 * time.Millisecond)
	assert.Equal(t, int32(1), maxInFlight.Load(),
		"only one dispatcher task may execute hooks for a given secret at a time")
}

// 6. Unregister during dispatch must not break iteration.
//    Mirrors unregisterDuringDispatch_doesNotThrow.
func TestPoller_UnregisterDuringDispatch_NoPanic(t *testing.T) {
	stub := &stubHTTPClient{}
	p := newTestPoller(t, stub)

	hookEntered := make(chan struct{})
	release := make(chan struct{})
	slow := apihooks.SecretRefreshHook(func(v apimodels.SecretValue) error {
		close(hookEntered)
		<-release
		return nil
	})
	other := apihooks.SecretRefreshHook(func(v apimodels.SecretValue) error { return nil })

	p.Register("acme", "concurrent-mod", slow)
	otherHandle := p.Register("acme", "concurrent-mod", other)

	stub.queueResponse(response.BatchGetSecretsResponse{
		UpdatedCount:   1,
		UpdatedSecrets: []response.UpdatedSecret{{ProjectID: "acme", SecretName: "concurrent-mod", DataVersion: 1, PublicPart: "p", PrivatePart: "q"}},
	})

	assert.NotPanics(t, func() {
		p.PollOnce()
		<-hookEntered
		otherHandle.Unregister()
		close(release)
		time.Sleep(100 * time.Millisecond)
	})
}

// 7. handleUpdatedSecret for an unknown secretRef is silently dropped.
//    Mirrors handleUpdatedSecret_forUnknownSecretRef_isIgnored.
func TestPoller_HandleUpdatedSecret_UnknownRef_IsIgnored(t *testing.T) {
	stub := &stubHTTPClient{}
	p := newTestPoller(t, stub)

	var calls atomic.Int32
	handle := p.Register("acme", "stale",
		apihooks.SecretRefreshHook(func(v apimodels.SecretValue) error {
			calls.Add(1)
			return nil
		}))
	handle.Unregister()

	// Keep registry non-empty so pollOnce does not short-circuit on
	// registry.isEmpty() — register a different secret.
	p.Register("acme", "other",
		apihooks.SecretRefreshHook(func(v apimodels.SecretValue) error { return nil }))

	stub.queueResponse(response.BatchGetSecretsResponse{
		UpdatedCount:   1,
		UpdatedSecrets: []response.UpdatedSecret{{ProjectID: "acme", SecretName: "stale", DataVersion: 7, PublicPart: "p", PrivatePart: "q"}},
	})
	p.PollOnce()

	time.Sleep(100 * time.Millisecond)
	assert.Equal(t, int32(0), calls.Load(),
		"updates for an unregistered secretRef must be ignored")
}

// 8. Empty registry short-circuits without an HTTP call.
func TestPoller_EmptyRegistry_NoHTTPCall(t *testing.T) {
	stub := &stubHTTPClient{}
	p := newTestPoller(t, stub)

	p.PollOnce()
	assert.Equal(t, 0, stub.callCount(),
		"empty registry must short-circuit before issuing any HTTP request")
}

// 9. Batch chunking: more than maxBatchSecrets registered secrets must be
//    split across multiple POSTs.
func TestPoller_BatchChunking_MoreThanMax_SplitsIntoMultiplePOSTs(t *testing.T) {
	stub := &stubHTTPClient{}
	p := newTestPoller(t, stub)

	const total = maxBatchSecrets + 10
	for i := 0; i < total; i++ {
		p.Register("acme", "secret-"+itoa(i),
			apihooks.SecretRefreshHook(func(v apimodels.SecretValue) error { return nil }))
	}
	p.PollOnce()

	expectedChunks := (total + maxBatchSecrets - 1) / maxBatchSecrets
	assert.Equal(t, expectedChunks, stub.callCount(),
		"more than maxBatchSecrets must be split across exactly that many POSTs")
	assert.Equal(t, testBatchURL, stub.lastURL())
}

// 10. URL routing: PollOnce hits the /v1/secrets/batch endpoint.
func TestPoller_PollOnce_UsesBatchURL(t *testing.T) {
	stub := &stubHTTPClient{}
	p := newTestPoller(t, stub)

	p.Register("acme", "x",
		apihooks.SecretRefreshHook(func(v apimodels.SecretValue) error { return nil }))
	p.PollOnce()

	assert.Equal(t, testBatchURL, stub.lastURL())
}

// 11. LastKnownVersion advances after delivery.
func TestPoller_LastKnownVersion_AdvancesAfterDelivery(t *testing.T) {
	stub := &stubHTTPClient{}
	p := newTestPoller(t, stub)

	delivered := make(chan struct{})
	var once sync.Once
	hook := apihooks.SecretRefreshHook(func(v apimodels.SecretValue) error {
		if v.DataVersion == 7 {
			once.Do(func() { close(delivered) })
		}
		return nil
	})
	p.Register("acme", "v", hook)

	stub.queueResponse(response.BatchGetSecretsResponse{
		UpdatedCount: 1,
		UpdatedSecrets: []response.UpdatedSecret{{
			ProjectID: "acme", SecretName: "v", DataVersion: 7,
			PublicPart: "p", PrivatePart: "q",
		}},
	})
	p.PollOnce()
	<-delivered

	state := p.lookup("acme:v")
	require.NotNil(t, state)
	assert.Equal(t, int32(7), state.LastKnownVersion.Load())
}

// 12. Hooks that panic do not kill the dispatcher.
func TestPoller_HookPanic_DoesNotKillDispatcher(t *testing.T) {
	stub := &stubHTTPClient{}
	p := newTestPoller(t, stub)

	delivered := make(chan struct{}, 1)
	bad := apihooks.SecretRefreshHook(func(v apimodels.SecretValue) error {
		panic("boom")
	})
	good := apihooks.SecretRefreshHook(func(v apimodels.SecretValue) error {
		delivered <- struct{}{}
		return nil
	})
	p.Register("acme", "panic", bad)
	p.Register("acme", "panic", good)

	stub.queueResponse(response.BatchGetSecretsResponse{
		UpdatedCount: 1,
		UpdatedSecrets: []response.UpdatedSecret{{
			ProjectID: "acme", SecretName: "panic", DataVersion: 1,
			PublicPart: "p", PrivatePart: "q",
		}},
	})
	p.PollOnce()

	select {
	case <-delivered:
	case <-time.After(updateDeliveryWindow):
		t.Fatal("good hook must still be invoked even when an earlier hook panics")
	}
}

// 13. Bodies sent on the wire must contain projectId / secretName / lastKnownVersion.
//     Validates JSON parity with Java's BatchGetSecretsRequest.
func TestPoller_RequestBodyShapeIsJavaCompatible(t *testing.T) {
	stub := &stubHTTPClient{}
	p := newTestPoller(t, stub)

	p.Register("acme", "shape",
		apihooks.SecretRefreshHook(func(v apimodels.SecretValue) error { return nil }))
	p.PollOnce()

	require.Len(t, stub.bodies(), 1)
	var body map[string]any
	require.NoError(t, json.Unmarshal(stub.bodies()[0], &body))

	secrets, ok := body["secrets"].([]any)
	require.True(t, ok, "request body must have a 'secrets' array")
	require.Len(t, secrets, 1)
	entry := secrets[0].(map[string]any)
	assert.Equal(t, "acme", entry["projectId"])
	assert.Equal(t, "shape", entry["secretName"])
	assert.Contains(t, entry, "lastKnownVersion")
}

// itoa is a tiny stdlib-free helper to avoid importing strconv solely for this.
func itoa(i int) string {
	if i == 0 {
		return "0"
	}
	digits := []byte{}
	for i > 0 {
		digits = append([]byte{byte('0' + i%10)}, digits...)
		i /= 10
	}
	return string(digits)
}
