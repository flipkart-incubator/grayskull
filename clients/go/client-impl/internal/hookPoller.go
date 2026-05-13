package internal

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
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

// Poller is the background batch-refresh loop. One Poller per client.
type Poller struct {
	registry        *hooks.Registry
	httpClient      GrayskullHTTPClientInterface
	batchURL        string
	interval        time.Duration
	metricsRecorder metrics.MetricsRecorder
	logger          *slog.Logger

	// dispatchCh fans staged updates out to a small pool of goroutines so that
	// a slow hook for one secret cannot stall delivery for another.
	dispatchCh chan dispatchJob

	stopOnce sync.Once
	stopCh   chan struct{}
	wg       sync.WaitGroup
}

type dispatchJob struct {
	secretRef string
	state     *hooks.SecretState
}

const (
	defaultPollingIntervalSeconds = 60
	dispatcherWorkers             = 5
	maxBatchSize                  = 50
	shutdownAwait                 = 10 * time.Second
)

// PollerConfig configures a new Poller. Mirrors the HookRefreshPoller constructor.
type PollerConfig struct {
	BaseURL         string
	HTTPClient      GrayskullHTTPClientInterface
	Registry        *hooks.Registry
	Interval        time.Duration
	MetricsRecorder metrics.MetricsRecorder
	Logger          *slog.Logger
}

// NewPoller constructs a Poller. Callers must invoke Start to begin polling and
// Close to release goroutines.
func NewPoller(cfg PollerConfig) *Poller {
	interval := cfg.Interval
	if interval <= 0 {
		interval = time.Duration(defaultPollingIntervalSeconds) * time.Second
	}
	logger := cfg.Logger
	if logger == nil {
		logger = slog.Default().With("component", "grayskull-poller")
	}
	return &Poller{
		registry:        cfg.Registry,
		httpClient:      cfg.HTTPClient,
		batchURL:        buildBatchURL(cfg.BaseURL),
		interval:        interval,
		metricsRecorder: cfg.MetricsRecorder,
		logger:          logger,
		dispatchCh:      make(chan dispatchJob, dispatcherWorkers*4),
		stopCh:          make(chan struct{}),
	}
}

// Start kicks off the ticker goroutine and the dispatcher worker pool.
// Safe to call once; subsequent calls are no-ops.
func (p *Poller) Start() {
	for i := 0; i < dispatcherWorkers; i++ {
		p.wg.Add(1)
		go p.dispatcherLoop()
	}
	p.wg.Add(1)
	go p.pollLoop()
}

// Close stops the ticker and dispatcher and waits up to shutdownAwait
// for in-flight work to finish.
func (p *Poller) Close() {
	p.stopOnce.Do(func() {
		close(p.stopCh)
	})
	done := make(chan struct{})
	go func() {
		p.wg.Wait()
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(shutdownAwait):
		p.logger.Warn("poller did not stop within shutdown window; abandoning workers",
			"timeout", shutdownAwait)
	}
}

// pollLoop runs PollOnce on a fixed-delay.
func (p *Poller) pollLoop() {
	defer p.wg.Done()
	defer p.recoverFromPanic("pollLoop")

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

// safePollOnce wraps PollOnce in a recover() so a panic in one cycle cannot kill
// the ticker goroutine.
func (p *Poller) safePollOnce() {
	defer func() {
		if r := recover(); r != nil {
			p.logger.Error("panic in poll cycle; suppressing to keep poller alive",
				"panic", r, "stack", string(debug.Stack()))
		}
	}()
	ctx, cancel := p.newRequestContext()
	defer cancel()
	p.PollOnce(ctx)
}

func (p *Poller) newRequestContext() (context.Context, context.CancelFunc) {
	ctx := context.WithValue(context.Background(), constants.GrayskullRequestID, uuid.NewString())
	// Bound a single poll cycle so a hung server can't pin the goroutine forever
	return context.WithTimeout(ctx, 5*p.interval)
}

// PollOnce executes a single poll cycle. Exported so tests can drive it directly
// without waiting on the ticker; the running poller invokes it from safePollOnce.
func (p *Poller) PollOnce(ctx context.Context) {
	states := p.registry.Snapshot()
	if len(states) == 0 {
		return
	}

	startTime := time.Now()
	statusCode := 0

	entries := make([]batch.Entry, 0, len(states))
	for _, s := range states {
		entries = append(entries, batch.Entry{
			ProjectID:        s.ProjectID,
			SecretName:       s.SecretName,
			LastKnownVersion: int(s.LastKnownVersion.Load()),
		})
	}

	totalSecrets := len(entries)
	pollFailed := false
	anyUpdated := false

	for from := 0; from < totalSecrets; from += maxBatchSize {
		to := from + maxBatchSize
		if to > totalSecrets {
			to = totalSecrets
		}
		chunk := entries[from:to]

		body, err := json.Marshal(batch.BatchGetSecretsRequest{Secrets: chunk})
		if err != nil {
			pollFailed = true
			statusCode = 500
			p.logger.Error("failed to marshal batch refresh body",
				"from", from+1, "to", to, "err", err)
			continue
		}

		var parsed response.Response[batch.BatchGetSecretsResponse]
		code, err := p.httpClient.DoPostWithRetry(ctx, p.batchURL, body, &parsed)
		if !pollFailed {
			statusCode = code
		}
		if err != nil {
			pollFailed = true
			if code != 0 {
				statusCode = code
			} else {
				statusCode = 500
			}
			p.logger.Error("batch refresh failed",
				"from", from+1, "to", to, "err", err)
			continue
		}

		payload := parsed.Data
		if len(payload.UpdatedSecrets) == 0 {
			continue
		}
		for _, item := range payload.UpdatedSecrets {
			p.handleUpdatedSecret(item)
			anyUpdated = true
		}
	}

	if !pollFailed && !anyUpdated {
		p.logger.Debug("no secret versions advanced this cycle")
	}
	if p.metricsRecorder != nil {
		p.metricsRecorder.RecordRequest("batchGetSecrets", statusCode, time.Since(startTime))
	}
}

// handleUpdatedSecret stages an update and queues a dispatch job.
func (p *Poller) handleUpdatedSecret(item batch.UpdatedSecret) {
	secretRef := item.ProjectID + ":" + item.SecretName
	state := p.registry.Get(secretRef)
	if state == nil {
		return
	}
	value := &apiModels.SecretValue{
		DataVersion: item.DataVersion,
		PublicPart:  item.PublicPart,
		PrivatePart: item.PrivatePart,
	}
	state.SetPending(value)

	// Non-blocking submit: if every worker is busy and the channel is full, the
	// dispatcher will pick this state up via the follow-up path after it drains
	// the current job
	select {
	case p.dispatchCh <- dispatchJob{secretRef: secretRef, state: state}:
	default:
		p.logger.Debug("dispatch channel full; relying on follow-up resubmit",
			"secretRef", secretRef)
	}
}

// dispatcherLoop drains dispatchCh and runs hooks per-secret. Each worker can
// process any secretRef; the per-secret isExecuting CAS in runHooksFor ensures
// only one worker runs hooks for the same secret at a time.
func (p *Poller) dispatcherLoop() {
	defer p.wg.Done()
	defer p.recoverFromPanic("dispatcherLoop")

	for {
		select {
		case <-p.stopCh:
			return
		case job := <-p.dispatchCh:
			p.runHooksFor(job.secretRef, job.state)
		}
	}
}

// runHooksFor delivers pending updates to every registered hook, sequentially.
func (p *Poller) runHooksFor(secretRef string, state *hooks.SecretState) {
	if !state.TryAcquireExecution() {
		return
	}
	defer func() {
		state.ReleaseExecution()
		// If a newer update slipped in after the drain loop but before we
		// released the slot, re-queue so we don't lose it.
		if state.HasPending() {
			select {
			case p.dispatchCh <- dispatchJob{secretRef: secretRef, state: state}:
			case <-p.stopCh:
			default:
			}
		}
	}()

	for {
		value := state.TakePending()
		if value == nil {
			return
		}
		state.LastKnownVersion.Store(int32(value.DataVersion))
		p.deliverToHooks(secretRef, state, *value)
	}
}

// deliverToHooks invokes every registered hook for state, recovering from each
// hook's panics so one broken consumer cannot block delivery to the others.
func (p *Poller) deliverToHooks(secretRef string, state *hooks.SecretState, value apiModels.SecretValue) {
	for _, hook := range state.SnapshotHooks() {
		p.invokeHookSafe(secretRef, hook, value)
	}
}

func (p *Poller) invokeHookSafe(secretRef string, hook apiHooks.SecretRefreshHook, value apiModels.SecretValue) {
	startTime := time.Now()
	success := true
	defer func() {
		if r := recover(); r != nil {
			success = false
			p.logger.Error("consumer hook panicked",
				"secretRef", secretRef,
				"panic", r,
				"stack", string(debug.Stack()))
		}
		if p.metricsRecorder != nil {
			code := 200
			if !success {
				code = 500
			}
			p.metricsRecorder.RecordRequest("hook.execute."+secretRef, code, time.Since(startTime))
		}
	}()
	if err := hook(value); err != nil {
		success = false
		p.logger.Error("consumer hook returned error",
			"secretRef", secretRef, "err", err)
	}
}

// recoverFromPanic is the last-line guard for the two long-running goroutines.
// A panic here is logged and swallowed so the host application keeps running.
func (p *Poller) recoverFromPanic(loop string) {
	if r := recover(); r != nil {
		p.logger.Error("unrecoverable panic in poller goroutine; loop exiting",
			"loop", loop, "panic", r, "stack", string(debug.Stack()))
	}
}

func buildBatchURL(baseURL string) string {
	// Server contract: POST <baseURL>/v1/secrets/batch
	return fmt.Sprintf("%s/v1/secrets/batch", baseURL)
}
