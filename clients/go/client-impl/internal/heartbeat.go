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
	heartbeatPath        = "/v1/client/heartbeat"
	heartbeatBackoffBase = 5 * time.Minute
	heartbeatBackoffCap  = time.Hour
)

// beatOutcome is the result of one heartbeat round.
type beatOutcome int

const (
	beatSent beatOutcome = iota
	beatFailed
	beatUnsupported
)

// HeartbeatPoster sends one POST without retries.
type HeartbeatPoster interface {
	DoPost(ctx context.Context, url string, jsonBody []byte) (int, error)
}

// Heartbeat sends the usage-ledger snapshot to POST /v1/client/heartbeat.
type Heartbeat struct {
	httpClient      HeartbeatPoster
	url             string
	interval        time.Duration
	heldSecrets     func() []heartbeat.HeldSecret
	metricsRecorder metrics.MetricsRecorder
	logger          *slog.Logger

	unsupportedLogged sync.Once

	ctx       context.Context
	cancel    context.CancelFunc
	done      chan struct{}
	startOnce sync.Once
}

// HeartbeatConfig configures a new Heartbeat.
type HeartbeatConfig struct {
	BaseURL         string
	HTTPClient      HeartbeatPoster
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
		url:             cfg.BaseURL + heartbeatPath,
		interval:        interval,
		heldSecrets:     cfg.HeldSecrets,
		metricsRecorder: cfg.MetricsRecorder,
		logger:          logger,
		ctx:             ctx,
		cancel:          cancel,
		done:            make(chan struct{}),
	}
}

// Start launches the loop. Idempotent.
func (h *Heartbeat) Start() {
	h.startOnce.Do(func() { go h.run() })
}

// Close stops the loop and waits for it to exit. Idempotent.
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
		if h.safeBeat() == beatFailed {
			failures++
		} else {
			failures = 0
		}
		timer.Reset(h.nextDelay(failures))
	}
}

// safeBeat keeps the loop alive across panics; a panic counts as a failed round.
func (h *Heartbeat) safeBeat() (outcome beatOutcome) {
	defer func() {
		if r := recover(); r != nil {
			h.logger.Error("panic in heartbeat; suppressing", "panic", r, "stack", string(debug.Stack()))
			outcome = beatFailed
		}
	}()
	return h.beat(h.ctx)
}

// beat sends the snapshot in chunks of maxBatchSize; nothing when empty.
func (h *Heartbeat) beat(ctx context.Context) beatOutcome {
	held := h.heldSecrets()
	if len(held) == 0 {
		return beatSent
	}
	result := beatSent
	for from := 0; from < len(held); from += maxBatchSize {
		to := min(from+maxBatchSize, len(held))
		switch h.send(ctx, held[from:to]) {
		case beatUnsupported:
			return beatUnsupported
		case beatFailed:
			result = beatFailed
		}
	}
	return result
}

func (h *Heartbeat) send(ctx context.Context, chunk []heartbeat.HeldSecret) beatOutcome {
	start := time.Now()
	status := http.StatusInternalServerError
	defer func() {
		if h.metricsRecorder != nil {
			h.metricsRecorder.RecordRequest("heartbeat", status, time.Since(start))
		}
	}()

	// Marshal cannot fail for this type.
	body, _ := json.Marshal(heartbeat.Request{Secrets: chunk})
	ctx = context.WithValue(ctx, constants.GrayskullRequestID, uuid.NewString())
	code, err := h.httpClient.DoPost(ctx, h.url, body)
	if code != 0 {
		status = code
	}
	if err == nil {
		return beatSent
	}
	if isHeartbeatUnsupported(status) {
		h.unsupportedLogged.Do(func() {
			h.logger.Info("Grayskull server does not support client heartbeats yet; retrying after the full interval",
				"status", status)
		})
		return beatUnsupported
	}
	if ctx.Err() == nil {
		h.logger.Warn("heartbeat failed; retrying with backoff", "secrets", len(chunk), "status", status, "err", err)
	}
	return beatFailed
}

func isHeartbeatUnsupported(status int) bool {
	return status == http.StatusNotFound || status == http.StatusMethodNotAllowed || status == http.StatusNotImplemented
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
