package internal

import (
	"context"
	"encoding/json"
	"log/slog"
	"math/rand/v2"
	"net/http"
	"runtime/debug"
	"sync"
	"time"

	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/constants"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/internal/models/heartbeat"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/metrics"
	"github.com/google/uuid"
)

const (
	heartbeatBackoffBase = 5 * time.Minute
	heartbeatBackoffCap  = time.Hour
)

// Heartbeat periodically reports the secrets held by the client to POST /v1/heartbeat.
// Delay is interval ± 1/24 after a successful round and capped exponential backoff
// (5m doubling to 1h, ± 10%, never above interval) after a failed one.
type Heartbeat struct {
	httpClient      GrayskullHTTPClientInterface
	url             string
	interval        time.Duration
	heldSecrets     func() []heartbeat.HeldSecret
	metricsRecorder metrics.MetricsRecorder
	logger          *slog.Logger

	ctx       context.Context
	cancel    context.CancelFunc
	done      chan struct{}
	startOnce sync.Once
}

// HeartbeatConfig configures a new Heartbeat.
type HeartbeatConfig struct {
	BaseURL         string
	HTTPClient      GrayskullHTTPClientInterface
	Interval        time.Duration
	HeldSecrets     func() []heartbeat.HeldSecret
	MetricsRecorder metrics.MetricsRecorder
	Logger          *slog.Logger
}

// NewHeartbeat constructs a Heartbeat. Call Start to begin, Close to stop.
func NewHeartbeat(cfg HeartbeatConfig) *Heartbeat {
	interval := cfg.Interval
	if interval <= 0 {
		interval = time.Duration(constants.DefaultHeartbeatIntervalSeconds) * time.Second
	}
	logger := cfg.Logger
	if logger == nil {
		logger = slog.Default().With("component", "grayskull-heartbeat")
	}
	ctx, cancel := context.WithCancel(context.Background())
	return &Heartbeat{
		httpClient:      cfg.HTTPClient,
		url:             cfg.BaseURL + "/v1/heartbeat",
		interval:        interval,
		heldSecrets:     cfg.HeldSecrets,
		metricsRecorder: cfg.MetricsRecorder,
		logger:          logger,
		ctx:             ctx,
		cancel:          cancel,
		done:            make(chan struct{}),
	}
}

// Start launches the heartbeat loop; the first round runs one interval from now. Idempotent.
func (h *Heartbeat) Start() {
	h.startOnce.Do(func() { go h.run() })
}

// Close stops the loop, cancelling an in-flight request, and waits for it to exit. Idempotent.
func (h *Heartbeat) Close() {
	h.cancel()
	h.startOnce.Do(func() { close(h.done) }) // never started: nothing to wait for
	<-h.done
}

func (h *Heartbeat) run() {
	defer close(h.done)
	failures := 0
	timer := time.NewTimer(h.nextDelay(failures))
	defer timer.Stop()
	for {
		select {
		case <-h.ctx.Done():
			return
		case <-timer.C:
		}
		if h.safeBeat() {
			failures = 0
		} else {
			failures++
		}
		timer.Reset(h.nextDelay(failures))
	}
}

// safeBeat keeps the loop alive across panics; a panic counts as a failed round.
func (h *Heartbeat) safeBeat() (ok bool) {
	defer func() {
		if r := recover(); r != nil {
			h.logger.Error("panic in heartbeat; suppressing", "panic", r, "stack", string(debug.Stack()))
			ok = false
		}
	}()
	return h.Beat(h.ctx)
}

// Beat sends every held secret in chunks of maxBatchSize. Reports whether all chunks
// succeeded; true when nothing is held.
func (h *Heartbeat) Beat(ctx context.Context) bool {
	held := h.heldSecrets()
	ok := true
	for from := 0; from < len(held); from += maxBatchSize {
		to := min(from+maxBatchSize, len(held))
		ok = h.send(ctx, held[from:to]) && ok
	}
	return ok
}

func (h *Heartbeat) send(ctx context.Context, chunk []heartbeat.HeldSecret) bool {
	start := time.Now()
	status := http.StatusInternalServerError
	defer func() {
		if h.metricsRecorder != nil {
			h.metricsRecorder.RecordRequest("heartbeat", status, time.Since(start))
		}
	}()

	body, err := json.Marshal(heartbeat.Request{Secrets: chunk})
	if err != nil {
		h.logger.Error("failed to marshal heartbeat", "err", err)
		return false
	}
	ctx = context.WithValue(ctx, constants.GrayskullRequestID, uuid.NewString())
	code, err := h.httpClient.DoPostWithRetry(ctx, h.url, body, nil)
	if code != 0 {
		status = code
	}
	if err != nil {
		if ctx.Err() == nil {
			h.logger.Warn("heartbeat failed; retrying with backoff", "secrets", len(chunk), "status", status, "err", err)
		}
		return false
	}
	return true
}

// nextDelay returns interval ± 1/24 when failures == 0, else capped exponential backoff ± 10%.
func (h *Heartbeat) nextDelay(failures int) time.Duration {
	if failures == 0 {
		return jitter(h.interval, h.interval/24)
	}
	backoff := heartbeatBackoffBase << min(failures-1, 20)
	backoff = min(backoff, heartbeatBackoffCap, h.interval)
	return jitter(backoff, backoff/10)
}

func jitter(d, spread time.Duration) time.Duration {
	if spread > 0 {
		d += time.Duration(rand.Int64N(int64(2*spread+1))) - spread
	}
	return max(d, time.Millisecond)
}
