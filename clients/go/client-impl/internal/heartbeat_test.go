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
	at        time.Time
}

// recordingClient records heartbeat POSTs; failFirst calls fail with a 503, block holds calls until ctx ends.
type recordingClient struct {
	mu        sync.Mutex
	calls     []heartbeatCall
	failFirst int
	block     bool
}

func (c *recordingClient) DoPostWithRetry(ctx context.Context, url string, body []byte, _ any) (int, error) {
	var req heartbeat.Request
	if err := json.Unmarshal(body, &req); err != nil {
		return 0, err
	}
	c.mu.Lock()
	c.calls = append(c.calls, heartbeatCall{url: url, requestID: ctx.Value(constants.GrayskullRequestID), body: req, at: time.Now()})
	fail := len(c.calls) <= c.failFirst
	c.mu.Unlock()
	if c.block {
		<-ctx.Done()
		return 0, ctx.Err()
	}
	if fail {
		return 503, errors.New("unavailable")
	}
	return 204, nil
}

func (c *recordingClient) DoGetWithRetry(context.Context, string, any) (int, error) { return 200, nil }
func (c *recordingClient) Close() error                                             { return nil }

func (c *recordingClient) snapshot() []heartbeatCall {
	c.mu.Lock()
	defer c.mu.Unlock()
	return append([]heartbeatCall(nil), c.calls...)
}

func held(n int) func() []heartbeat.HeldSecret {
	return func() []heartbeat.HeldSecret {
		out := make([]heartbeat.HeldSecret, n)
		for i := range out {
			out[i] = heartbeat.HeldSecret{ProjectID: "p", SecretName: fmt.Sprintf("s%d", i), Version: i + 1, RefreshHook: i%2 == 0}
		}
		return out
	}
}

func newTestHeartbeat(client GrayskullHTTPClientInterface, interval time.Duration, secrets func() []heartbeat.HeldSecret) *Heartbeat {
	return NewHeartbeat(HeartbeatConfig{BaseURL: "http://gs", HTTPClient: client, Interval: interval, HeldSecrets: secrets})
}

func TestHeartbeatBeat_NothingHeld_SendsNothing(t *testing.T) {
	c := &recordingClient{}
	assert.True(t, newTestHeartbeat(c, time.Hour, held(0)).Beat(context.Background()))
	assert.Empty(t, c.snapshot())
}

func TestHeartbeatBeat_PostsHeldSecrets(t *testing.T) {
	c := &recordingClient{}
	require.True(t, newTestHeartbeat(c, time.Hour, held(2)).Beat(context.Background()))

	calls := c.snapshot()
	require.Len(t, calls, 1)
	assert.Equal(t, "http://gs/v1/heartbeat", calls[0].url)
	assert.NotEmpty(t, calls[0].requestID)
	assert.Equal(t, []heartbeat.HeldSecret{
		{ProjectID: "p", SecretName: "s0", Version: 1, RefreshHook: true},
		{ProjectID: "p", SecretName: "s1", Version: 2, RefreshHook: false},
	}, calls[0].body.Secrets)
}

func TestHeartbeatBeat_ChunksAtBatchSizeAndReportsFailure(t *testing.T) {
	c := &recordingClient{failFirst: 1}
	ok := newTestHeartbeat(c, time.Hour, held(120)).Beat(context.Background())

	assert.False(t, ok, "a failed chunk fails the round")
	calls := c.snapshot()
	require.Len(t, calls, 3, "remaining chunks are still sent")
	for i, want := range []int{50, 50, 20} {
		assert.Len(t, calls[i].body.Secrets, want)
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
