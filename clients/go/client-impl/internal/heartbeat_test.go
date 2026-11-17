package internal

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sync"
	"testing"
	"time"

	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/constants"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/internal/models/heartbeat"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

type heartbeatCall struct {
	url       string
	requestID any
	body      heartbeat.Request
	raw       []byte
	at        time.Time
}

// recordingClient records POSTs; the first failFirst fail with failStatus (503).
type recordingClient struct {
	mu         sync.Mutex
	calls      []heartbeatCall
	failFirst  int
	failStatus int
	block      bool
}

func (c *recordingClient) DoPost(ctx context.Context, url string, body []byte) (int, error) {
	var req heartbeat.Request
	if err := json.Unmarshal(body, &req); err != nil {
		return 0, err
	}
	c.mu.Lock()
	c.calls = append(c.calls, heartbeatCall{url: url, requestID: ctx.Value(constants.GrayskullRequestID), body: req, raw: body, at: time.Now()})
	fail := len(c.calls) <= c.failFirst
	status := c.failStatus
	c.mu.Unlock()
	if c.block {
		<-ctx.Done()
		return 0, ctx.Err()
	}
	if fail {
		if status == 0 {
			status = 503
		}
		return status, errors.New("request failed")
	}
	return 204, nil
}

func (c *recordingClient) snapshot() []heartbeatCall {
	c.mu.Lock()
	defer c.mu.Unlock()
	return append([]heartbeatCall(nil), c.calls...)
}

func held(n int) func() []heartbeat.HeldSecret {
	return func() []heartbeat.HeldSecret {
		out := make([]heartbeat.HeldSecret, n)
		for i := range out {
			out[i] = heartbeat.HeldSecret{ProjectID: "p", SecretName: fmt.Sprintf("s%d", i), Version: i + 1}
			if i%2 == 0 {
				applied := i
				out[i].RefreshHookAppliedVersion = &applied
			}
		}
		return out
	}
}

func newTestHeartbeat(client HeartbeatPoster, interval time.Duration, secrets func() []heartbeat.HeldSecret) *Heartbeat {
	return NewHeartbeat(HeartbeatConfig{BaseURL: "http://gs", HTTPClient: client, Interval: interval, HeldSecrets: secrets})
}

func TestHeartbeatBeat_NothingHeld_SendsNothing(t *testing.T) {
	c := &recordingClient{}
	assert.Equal(t, beatSent, newTestHeartbeat(c, time.Hour, held(0)).beat(context.Background()))
	assert.Empty(t, c.snapshot())
}

func TestHeartbeatBeat_PostsSecretsOnly(t *testing.T) {
	c := &recordingClient{}
	require.Equal(t, beatSent, newTestHeartbeat(c, time.Hour, held(2)).beat(context.Background()))

	calls := c.snapshot()
	require.Len(t, calls, 1)
	assert.Equal(t, "http://gs/v1/client/heartbeat", calls[0].url)
	assert.NotEmpty(t, calls[0].requestID)
	body := calls[0].body
	zero := 0
	assert.Equal(t, []heartbeat.HeldSecret{
		{ProjectID: "p", SecretName: "s0", Version: 1, RefreshHookAppliedVersion: &zero},
		{ProjectID: "p", SecretName: "s1", Version: 2},
	}, body.Secrets)
	assert.JSONEq(t, `{"secrets":[{"projectId":"p","secretName":"s0","version":1,"refreshHookAppliedVersion":0},`+
		`{"projectId":"p","secretName":"s1","version":2}]}`, string(calls[0].raw), "no envelope, no hook field without a hook")
}

func TestHeartbeatBeat_ChunksAtBatchSizeAndReportsFailure(t *testing.T) {
	c := &recordingClient{failFirst: 1}
	outcome := newTestHeartbeat(c, time.Hour, held(120)).beat(context.Background())

	assert.Equal(t, beatFailed, outcome, "a failed chunk fails the round")
	calls := c.snapshot()
	require.Len(t, calls, 3, "remaining chunks are still sent")
	for i, want := range []int{50, 50, 20} {
		assert.Len(t, calls[i].body.Secrets, want)
	}
}

func TestHeartbeatBeat_UnsupportedStatusStopsTheRound(t *testing.T) {
	for _, status := range []int{404, 405, 501} {
		c := &recordingClient{failFirst: 1000, failStatus: status}
		h := newTestHeartbeat(c, time.Hour, held(120))
		assert.Equal(t, beatUnsupported, h.beat(context.Background()), "status %d", status)
		assert.Len(t, c.snapshot(), 1, "status %d: remaining chunks are not sent", status)
		assert.Equal(t, beatUnsupported, h.beat(context.Background()), "logged once, still unsupported")
	}
}

func TestIsHeartbeatUnsupported(t *testing.T) {
	for _, s := range []int{404, 405, 501} {
		assert.True(t, isHeartbeatUnsupported(s), "%d", s)
	}
	for _, s := range []int{0, 400, 403, 500, 503} {
		assert.False(t, isHeartbeatUnsupported(s), "%d", s)
	}
}

func TestHeartbeatNextDelay(t *testing.T) {
	h := newTestHeartbeat(&recordingClient{}, 24*time.Hour, held(0))
	within := func(t *testing.T, got, want, spread time.Duration) {
		t.Helper()
		assert.GreaterOrEqual(t, got, want-spread)
		assert.LessOrEqual(t, got, want+spread)
	}

	for range 100 {
		within(t, h.nextDelay(0), 24*time.Hour, time.Hour)
	}
	for i, minutes := range []time.Duration{5, 10, 20, 40, 60, 60, 60} {
		want := minutes * time.Minute
		within(t, h.nextDelay(i+1), want, want/10)
	}
	assert.LessOrEqual(t, newTestHeartbeat(&recordingClient{}, 30*time.Second, held(0)).nextDelay(3), 33*time.Second,
		"backoff never exceeds the interval")
}

func TestHeartbeatLoop_FirstBeatAfterIntervalAndKeepsGoingAfterFailures(t *testing.T) {
	c := &recordingClient{failFirst: 2}
	h := newTestHeartbeat(c, 200*time.Millisecond, held(1))
	start := time.Now()
	h.Start()
	defer h.Close()

	require.Eventually(t, func() bool { return len(c.snapshot()) >= 4 }, 5*time.Second, 10*time.Millisecond)
	calls := c.snapshot()
	assert.GreaterOrEqual(t, calls[0].at.Sub(start), 150*time.Millisecond, "first beat waits one interval")
	for i := 1; i < 4; i++ {
		gap := calls[i].at.Sub(calls[i-1].at)
		assert.True(t, gap >= 150*time.Millisecond && gap <= 2*time.Second, "gap %d was %s", i, gap)
	}
}

func TestHeartbeatLoop_UnsupportedWaitsFullInterval(t *testing.T) {
	// Unsupported (404) rounds wait the full interval, not the backoff.
	c := &recordingClient{failFirst: 1000, failStatus: 404}
	h := newTestHeartbeat(c, 200*time.Millisecond, held(1))
	h.Start()
	defer h.Close()

	require.Eventually(t, func() bool { return len(c.snapshot()) >= 3 }, 5*time.Second, 10*time.Millisecond)
	calls := c.snapshot()
	for i := 1; i < 3; i++ {
		assert.GreaterOrEqual(t, calls[i].at.Sub(calls[i-1].at), 150*time.Millisecond, "gap %d", i)
	}
}

func TestHeartbeatClose_InterruptsInFlightAndIsIdempotent(t *testing.T) {
	c := &recordingClient{block: true}
	h := newTestHeartbeat(c, 20*time.Millisecond, held(1))
	h.Start()
	require.Eventually(t, func() bool { return len(c.snapshot()) == 1 }, 2*time.Second, 5*time.Millisecond)

	done := make(chan struct{})
	go func() { h.Close(); h.Close(); close(done) }()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("Close did not return while a heartbeat was in flight")
	}
	time.Sleep(60 * time.Millisecond)
	assert.Len(t, c.snapshot(), 1, "no heartbeat after Close")
}

func TestHeartbeatClose_WithoutStartReturns(t *testing.T) {
	h := newTestHeartbeat(&recordingClient{}, time.Hour, held(1))
	h.Close()
	h.Start() // no-op after Close
	h.Close()
}

// heartbeatMetricsSpy records RecordRequest calls.
type heartbeatMetricsSpy struct {
	mu    sync.Mutex
	calls []string
}

func (m *heartbeatMetricsSpy) RecordRequest(name string, statusCode int, _ time.Duration) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.calls = append(m.calls, fmt.Sprintf("%s:%d", name, statusCode))
}

func (m *heartbeatMetricsSpy) RecordRetry(string, int, bool) {}

func (m *heartbeatMetricsSpy) snapshot() []string {
	m.mu.Lock()
	defer m.mu.Unlock()
	return append([]string(nil), m.calls...)
}

func TestNewHeartbeat_DefaultsIntervalAndLogger(t *testing.T) {
	h := NewHeartbeat(HeartbeatConfig{BaseURL: "http://gs", HTTPClient: &recordingClient{}})
	defer h.Close()

	assert.Equal(t, time.Duration(constants.DefaultHeartbeatIntervalSeconds)*time.Second, h.interval)
	assert.NotNil(t, h.logger)
}

func TestHeartbeatSafeBeat_PanicIsRecoveredAsFailure(t *testing.T) {
	h := newTestHeartbeat(&recordingClient{}, time.Hour, func() []heartbeat.HeldSecret { panic("store unavailable") })

	assert.NotPanics(t, func() { assert.Equal(t, beatFailed, h.safeBeat()) })
}

func TestHeartbeatSend_RecordsMetricWhenRecorderConfigured(t *testing.T) {
	spy := &heartbeatMetricsSpy{}
	h := NewHeartbeat(HeartbeatConfig{
		BaseURL: "http://gs", HTTPClient: &recordingClient{}, Interval: time.Hour,
		HeldSecrets: held(1), MetricsRecorder: spy,
	})
	defer h.Close()

	assert.Equal(t, beatSent, h.beat(context.Background()))
	assert.Equal(t, []string{"heartbeat:204"}, spy.snapshot())
}

func TestHeartbeatSend_RecordsFailureStatusInMetric(t *testing.T) {
	spy := &heartbeatMetricsSpy{}
	h := NewHeartbeat(HeartbeatConfig{
		BaseURL: "http://gs", HTTPClient: &recordingClient{failFirst: 1}, Interval: time.Hour,
		HeldSecrets: held(1), MetricsRecorder: spy,
	})
	defer h.Close()

	assert.Equal(t, beatFailed, h.beat(context.Background()))
	assert.Equal(t, []string{"heartbeat:503"}, spy.snapshot())
}
