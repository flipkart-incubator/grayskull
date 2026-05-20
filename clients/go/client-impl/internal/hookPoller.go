package internal

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"runtime/debug"
	"sync"
	"time"

	apiHooks "github.com/flipkart-incubator/grayskull/clients/go/client-api/hooks"
	apiModels "github.com/flipkart-incubator/grayskull/clients/go/client-api/models"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/constants"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/internal/hooks"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/internal/models/batch"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/internal/models/response"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/metrics"
	"github.com/google/uuid"
)

// Poller is the background batch-refresh loop (one per client). Mirrors
// HookRefreshPoller in the Java SDK: a single fetcher (pollLoop) and a
// fixed dispatcher pool so a slow hook on one secret can't stall others.
type Poller struct {
	registry        *hooks.Registry
	httpClient      GrayskullHTTPClientInterface
	batchURL        string
	interval        time.Duration
	requestTimeout  time.Duration
	metricsRecorder metrics.MetricsRecorder
	logger          *slog.Logger
	marshalRequest  func(v any) ([]byte, error)

	dispatchCh chan dispatchJob

	// hookCtx is the root ctx for consumer hooks; cancelled on Close.
	hookCtx       context.Context
	cancelHookCtx context.CancelFunc

	startOnce sync.Once
	stopOnce  sync.Once
	stopCh    chan struct{}
	wg        sync.WaitGroup
}

type dispatchJob struct {
	secretRef string
	state     *hooks.SecretState
	// requestID identifies the poll cycle that observed the update;
	// surfaced to the hook via ctx for correlation.
	requestID string
}

const (
	dispatcherWorkers = 5
	maxBatchSize      = 50
)

// ErrShutdownTimeout is returned by Close when workers don't drain in time.
var ErrShutdownTimeout = errors.New("poller shutdown timed out; workers abandoned")

// var so tests can shorten it.
var shutdownAwait = 10 * time.Second

// PollerConfig configures a new Poller.
type PollerConfig struct {
	BaseURL         string
	HTTPClient      GrayskullHTTPClientInterface
	Registry        *hooks.Registry
	Interval        time.Duration
	MetricsRecorder metrics.MetricsRecorder
	Logger          *slog.Logger

	// RequestTimeout bounds a single poll cycle. Defaults to Interval.
	RequestTimeout time.Duration

	// MarshalRequest defaults to json.Marshal. Test seam.
	MarshalRequest func(v any) ([]byte, error)
}

// NewPoller constructs a Poller. Call Start to begin polling, Close to stop.
func NewPoller(cfg PollerConfig) *Poller {
	interval := cfg.Interval
	if interval <= 0 {
		interval = time.Duration(constants.DefaultPollingIntervalSeconds) * time.Second
	}
	requestTimeout := cfg.RequestTimeout
	if requestTimeout <= 0 {
		requestTimeout = interval
	}
	logger := cfg.Logger
	if logger == nil {
		logger = slog.Default().With("component", "grayskull-poller")
	}
	marshal := cfg.MarshalRequest
	if marshal == nil {
		marshal = json.Marshal
	}
	hookCtx, cancelHookCtx := context.WithCancel(context.Background())
	return &Poller{
		registry:        cfg.Registry,
		httpClient:      cfg.HTTPClient,
		batchURL:        fmt.Sprintf("%s/v1/secrets/batch", cfg.BaseURL),
		interval:        interval,
		requestTimeout:  requestTimeout,
		metricsRecorder: cfg.MetricsRecorder,
		logger:          logger,
		marshalRequest:  marshal,
		dispatchCh:      make(chan dispatchJob, dispatcherWorkers*4),
		hookCtx:         hookCtx,
		cancelHookCtx:   cancelHookCtx,
		stopCh:          make(chan struct{}),
	}
}

// Start launches the fetcher and dispatcher pool. Idempotent.
func (p *Poller) Start() {
	p.startOnce.Do(func() {
		for i := 0; i < dispatcherWorkers; i++ {
			p.wg.Add(1)
			go p.dispatcherLoop()
		}
		p.wg.Add(1)
		go p.pollLoop()
	})
}

// Close signals shutdown and waits up to shutdownAwait for workers to drain.
// Returns ErrShutdownTimeout on timeout. Idempotent.
func (p *Poller) Close() error {
	p.stopOnce.Do(func() {
		close(p.stopCh)
		p.cancelHookCtx()
	})

	done := make(chan struct{})
	go func() {
		p.wg.Wait()
		close(done)
	}()
	select {
	case <-done:
		return nil
	case <-time.After(shutdownAwait):
		p.logger.Warn("poller did not stop within shutdown window; abandoning workers",
			"timeout", shutdownAwait)
		return ErrShutdownTimeout
	}
}

func (p *Poller) pollLoop() {
	defer p.wg.Done()

	ticker := time.NewTicker(p.interval)
	defer ticker.Stop()

	for {
		select {
		case <-p.stopCh:
			return
		case <-ticker.C:
			p.safePollOnce()
		}
	}
}

// safePollOnce wraps PollOnce in recover() to keep the ticker alive.
func (p *Poller) safePollOnce() {
	defer func() {
		if r := recover(); r != nil {
			p.logger.Error("panic in poll cycle; suppressing to keep poller alive",
				"panic", r, "stack", string(debug.Stack()))
		}
	}()

	requestID := uuid.NewString()
	ctx := context.WithValue(context.Background(), constants.GrayskullRequestID, requestID)
	ctx, cancel := context.WithTimeout(ctx, p.requestTimeout)
	defer cancel()

	p.PollOnce(ctx)
}

// PollOnce runs one poll cycle.
func (p *Poller) PollOnce(ctx context.Context) {
	states := p.registry.Snapshot()
	if len(states) == 0 {
		return
	}

	entries := make([]batch.Entry, len(states))
	for i, s := range states {
		entries[i] = batch.Entry{
			ProjectID:        s.ProjectID,
			SecretName:       s.SecretName,
			LastKnownVersion: int(s.LastKnownVersion.Load()),
		}
	}

	for from := 0; from < len(entries); from += maxBatchSize {
		to := from + maxBatchSize
		if to > len(entries) {
			to = len(entries)
		}
		p.pollChunk(ctx, entries[from:to])
	}
}

// pollChunk POSTs one batch chunk and stages any returned updates. One
// HTTP call = one metric observation (avoids collapsing per-cycle).
func (p *Poller) pollChunk(ctx context.Context, chunk []batch.Entry) {
	startTime := time.Now()
	statusCode := 0
	defer func() {
		if p.metricsRecorder != nil {
			p.metricsRecorder.RecordRequest("batchGetSecrets", statusCode, time.Since(startTime))
		}
	}()

	body, err := p.marshalRequest(batch.BatchGetSecretsRequest{Secrets: chunk})
	if err != nil {
		statusCode = http.StatusInternalServerError
		p.logger.Error("failed to marshal batch refresh body", "err", err)
		return
	}

	var parsed response.Response[batch.BatchGetSecretsResponse]
	code, err := p.httpClient.DoPostWithRetry(ctx, p.batchURL, body, &parsed)
	statusCode = code
	if err != nil {
		if statusCode == 0 {
			statusCode = http.StatusInternalServerError
		}
		p.logger.Error("batch refresh failed", "err", err)
		return
	}

	requestID, _ := ctx.Value(constants.GrayskullRequestID).(string)
	for _, item := range parsed.Data.UpdatedSecrets {
		p.handleUpdatedSecret(item, requestID)
	}
}

// handleUpdatedSecret stages the update (coalescing on pending: newest
// wins) and queues a dispatch job. requestID is for hook-side correlation.
func (p *Poller) handleUpdatedSecret(item batch.UpdatedSecret, requestID string) {
	secretRef := item.ProjectID + ":" + item.SecretName
	state := p.registry.Get(secretRef)
	if state == nil {
		return
	}
	state.SetPending(&apiModels.SecretValue{
		DataVersion: item.DataVersion,
		PublicPart:  item.PublicPart,
		PrivatePart: item.PrivatePart,
	})

	// Non-blocking: if full, resubmitIfPending or the next PollOnce picks
	// this up (LastKnownVersion hasn't advanced yet).
	select {
	case p.dispatchCh <- dispatchJob{secretRef: secretRef, state: state, requestID: requestID}:
	default:
	}
}

func (p *Poller) dispatcherLoop() {
	defer p.wg.Done()
	for {
		select {
		case <-p.stopCh:
			return
		case job := <-p.dispatchCh:
			p.runHooksFor(job.secretRef, job.state, job.requestID)
		}
	}
}

// runHooksFor drains pending and invokes hooks sequentially. Non-reentrant
// per-secret. LastKnownVersion is bumped BEFORE the hook runs (at-most-once:
// a panicking hook skips that version; next server bump still delivers).
func (p *Poller) runHooksFor(secretRef string, state *hooks.SecretState, requestID string) {
	if !state.TryAcquireExecution() {
		return
	}
	defer func() {
		state.ReleaseExecution()
		p.resubmitIfPending(secretRef, state, requestID)
	}()

	ctx := p.hookCtx
	if requestID != "" {
		ctx = context.WithValue(ctx, constants.GrayskullRequestID, requestID)
	}

	for {
		value := state.TakePending()
		if value == nil {
			return
		}
		state.LastKnownVersion.Store(int32(value.DataVersion))
		for _, hook := range state.SnapshotHooks() {
			p.invokeHookSafe(ctx, secretRef, hook, *value)
		}
	}
}

// resubmitIfPending re-enqueues if a newer update arrived during the drain.
// The default arm is intentional: the value stays in state.pending, and the
// next handleUpdatedSecret or PollOnce will pick it up. Coalescing-to-latest
// is by design.
func (p *Poller) resubmitIfPending(secretRef string, state *hooks.SecretState, requestID string) {
	if !state.HasPending() {
		return
	}
	select {
	case p.dispatchCh <- dispatchJob{secretRef: secretRef, state: state, requestID: requestID}:
	case <-p.stopCh:
	default:
	}
}

// invokeHookSafe runs one consumer hook under recover() so one broken hook
// doesn't block delivery to the others.
func (p *Poller) invokeHookSafe(ctx context.Context, secretRef string, hook apiHooks.SecretRefreshHook, value apiModels.SecretValue) {
	startTime := time.Now()
	success := true
	defer func() {
		if r := recover(); r != nil {
			success = false
			p.logger.Error("consumer hook panicked",
				"secretRef", secretRef, "panic", r, "stack", string(debug.Stack()))
		}
		if p.metricsRecorder != nil {
			code := http.StatusOK
			if !success {
				code = http.StatusInternalServerError
			}
			// TODO(metrics-cardinality): embeds secretRef for Java SDK
			// parity; fix in both SDKs together.
			p.metricsRecorder.RecordRequest("hook.execute."+secretRef, code, time.Since(startTime))
		}
	}()
	if err := hook(ctx, value); err != nil {
		success = false
		p.logger.Error("consumer hook returned error", "secretRef", secretRef, "err", err)
	}
}
