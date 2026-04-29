package hooks

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net/url"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"

	apihooks "github.com/flipkart-incubator/grayskull/clients/go/client-api/hooks"
	apimodels "github.com/flipkart-incubator/grayskull/clients/go/client-api/models"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/constants"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/internal/models/request"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/internal/models/response"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/metrics"
	grayskullErrors "github.com/flipkart-incubator/grayskull/clients/go/client-impl/models/errors"
)

// Tunables. These mirror the corresponding constants in
// com.flipkart.grayskull.HookRefreshPoller and must stay in sync.
const (
	// dispatcherWorkers is the number of goroutines draining the dispatcher
	// channel; equivalent to Java's fixed thread pool of size 5.
	dispatcherWorkers = 5

	// shutdownAwait bounds how long Close() will wait for the scheduler and
	// dispatcher to drain gracefully before returning.
	shutdownAwait = 10 * time.Second

	// initialDelay is the wait between client construction and the first
	// poll. Matches Java's INITIAL_DELAY_SECONDS = 1.
	initialDelay = 1 * time.Second

	// maxBatchSecrets is the maximum number of secrets in a single batch
	// request. The poller chunks larger registries.
	maxBatchSecrets = 50

	// dispatcherQueueSize bounds the in-flight dispatcher work; per-secret
	// non-reentrancy already coalesces updates, so this only needs to absorb
	// short bursts before the workers catch up.
	dispatcherQueueSize = 256
)

// httpDoer is the narrow contract HookRefreshPoller needs from the HTTP
// client. It is satisfied by *internal.GrayskullHTTPClient and is also
// trivially mockable in tests.
type httpDoer interface {
	DoPostWithRetry(ctx context.Context, url string, body []byte, result any) (int, error)
}

// HookRefreshPoller owns the refresh-hook registry and drives server-side
// hook delivery. It mirrors com.flipkart.grayskull.HookRefreshPoller in the
// Java SDK, including its exact semantics for:
//
//   - latest-wins coalescing of pending updates per secret;
//   - per-secret non-reentrant dispatch (only one hook batch may run for a
//     given secret at a time);
//   - safe concurrent register / unregister against running dispatch;
//   - chunked batch polling at maxBatchSecrets per request.
//
// All exported methods are safe for concurrent use.
type HookRefreshPoller struct {
	httpClient httpDoer
	batchURL   string
	interval   time.Duration
	logger     *slog.Logger
	metrics    metrics.MetricsRecorder

	registryMu sync.Mutex
	registry   map[string]*SecretState

	dispatcherCh chan func()
	workersWg    sync.WaitGroup
	schedulerWg  sync.WaitGroup

	cancelCtx context.Context
	cancelFn  context.CancelFunc

	closeOnce sync.Once
}

// PollerConfig groups the dependencies HookRefreshPoller needs at construction.
type PollerConfig struct {
	HTTPClient      httpDoer
	BaseURL         string
	IntervalSeconds int
	Logger          *slog.Logger
	Metrics         metrics.MetricsRecorder
}

// NewHookRefreshPoller constructs a poller and starts its background scheduler
// and dispatcher workers. Callers must invoke Close to release resources.
//
// IntervalSeconds must be positive; if it is not, a sane default of 60s is
// used and a warning is logged.
func NewHookRefreshPoller(cfg PollerConfig) *HookRefreshPoller {
	logger := cfg.Logger
	if logger == nil {
		logger = slog.Default().WithGroup("grayskull-hook-poller")
	}
	if cfg.Metrics == nil {
		// Refuse to silently no-op metrics: the caller misconfigured us.
		// We still keep going by using a no-op so production stays alive.
		logger.Warn("HookRefreshPoller created without metrics recorder; using no-op")
		cfg.Metrics = noopRecorder{}
	}
	interval := time.Duration(cfg.IntervalSeconds) * time.Second
	if interval <= 0 {
		logger.Warn("Invalid polling interval; falling back to 60s",
			"interval_seconds", cfg.IntervalSeconds)
		interval = 60 * time.Second
	}

	ctx, cancel := context.WithCancel(context.Background())
	p := &HookRefreshPoller{
		httpClient:   cfg.HTTPClient,
		batchURL:     buildBatchURL(cfg.BaseURL),
		interval:     interval,
		logger:       logger,
		metrics:      cfg.Metrics,
		registry:     make(map[string]*SecretState),
		dispatcherCh: make(chan func(), dispatcherQueueSize),
		cancelCtx:    ctx,
		cancelFn:     cancel,
	}

	p.startWorkers()
	p.startScheduler()
	return p
}

// Register adds hook to the registry for (projectID, secretName). Multiple
// hooks may be registered for the same secret; each is delivered sequentially
// when an update arrives.
//
// The returned RefreshHandlerRef is the application's handle for unregistering.
// Hook must not be nil; callers should validate before invoking.
func (p *HookRefreshPoller) Register(projectID, secretName string, hook apihooks.SecretRefreshHook) apihooks.RefreshHandlerRef {
	secretRef := projectID + ":" + secretName
	state := p.computeRegister(secretRef, projectID, secretName, hook)
	p.logger.Debug("Registered refresh hook",
		"secretRef", secretRef, "totalHooks", state.HookCount())

	return NewDefaultRefreshHandlerRef(secretRef, func() {
		p.unregister(secretRef, hook)
	})
}

// computeRegister upserts SecretState in the registry under the registry
// mutex. The mutex is the Go analog of Java's ConcurrentHashMap.compute:
// it gives us atomic "get-or-create then mutate" semantics so concurrent
// Register calls for the same secret never lose hooks.
func (p *HookRefreshPoller) computeRegister(secretRef, projectID, secretName string, hook apihooks.SecretRefreshHook) *SecretState {
	p.registryMu.Lock()
	defer p.registryMu.Unlock()
	state, ok := p.registry[secretRef]
	if !ok {
		state = NewSecretState(projectID, secretName)
		p.registry[secretRef] = state
	}
	state.AddHook(hook)
	return state
}

// unregister removes hook from the SecretState for secretRef. When the last
// hook is removed, the SecretState itself is dropped from the registry.
func (p *HookRefreshPoller) unregister(secretRef string, hook apihooks.SecretRefreshHook) {
	p.registryMu.Lock()
	defer p.registryMu.Unlock()
	state, ok := p.registry[secretRef]
	if !ok {
		return
	}
	state.RemoveHook(hook)
	if state.IsEmpty() {
		delete(p.registry, secretRef)
	}
}

// snapshot returns a stable list of currently-registered SecretState values.
// We snapshot under the registry mutex so the poll cycle iterates a consistent
// view even if other goroutines call Register / Unregister concurrently.
func (p *HookRefreshPoller) snapshot() []*SecretState {
	p.registryMu.Lock()
	defer p.registryMu.Unlock()
	if len(p.registry) == 0 {
		return nil
	}
	out := make([]*SecretState, 0, len(p.registry))
	for _, s := range p.registry {
		out = append(out, s)
	}
	return out
}

// lookup returns the SecretState for secretRef or nil if not registered.
// Used by handleUpdatedSecret when correlating a server response back to the
// registry. The Java code uses ConcurrentHashMap.get; the mutex variant gives
// equivalent visibility guarantees in Go's memory model.
func (p *HookRefreshPoller) lookup(secretRef string) *SecretState {
	p.registryMu.Lock()
	defer p.registryMu.Unlock()
	return p.registry[secretRef]
}

// startScheduler launches the background goroutine that calls pollOnce on a
// fixed-delay schedule (the next tick begins p.interval after the previous
// tick completes), matching Java's scheduleWithFixedDelay.
func (p *HookRefreshPoller) startScheduler() {
	p.schedulerWg.Add(1)
	go func() {
		defer p.schedulerWg.Done()
		// Initial delay before the first poll.
		select {
		case <-p.cancelCtx.Done():
			return
		case <-time.After(initialDelay):
		}
		for {
			p.pollOnce()
			select {
			case <-p.cancelCtx.Done():
				return
			case <-time.After(p.interval):
			}
		}
	}()
}

// startWorkers launches the dispatcher worker pool.
func (p *HookRefreshPoller) startWorkers() {
	for i := 0; i < dispatcherWorkers; i++ {
		p.workersWg.Add(1)
		go func() {
			defer p.workersWg.Done()
			for task := range p.dispatcherCh {
				safeRun(p.logger, task)
			}
		}()
	}
}

// pollOnce performs a single poll cycle: it snapshots the registry, splits
// it into batches of at most maxBatchSecrets, sends each batch to the server,
// and routes any updates through the dispatcher.
//
// Exported method visibility is package-private (lowercase) on the surface,
// but exposed here as PollOnce for white-box tests in this package.
func (p *HookRefreshPoller) pollOnce() {
	states := p.snapshot()
	if len(states) == 0 {
		return
	}

	requestID := uuid.New().String()
	ctx := context.WithValue(p.cancelCtx, constants.GrayskullRequestID, requestID)

	startNs := time.Now()
	statusCode := 0

	defer func() {
		duration := time.Since(startNs)
		p.metrics.RecordRequest("batchGetSecrets", statusCode, duration)
	}()

	entries := make([]request.BatchGetSecretsRequestEntry, 0, len(states))
	for _, s := range states {
		entries = append(entries, request.BatchGetSecretsRequestEntry{
			ProjectID:        s.ProjectID,
			SecretName:       s.SecretName,
			LastKnownVersion: s.LastKnownVersion.Load(),
		})
	}
	if len(entries) == 0 {
		return
	}

	totalSecrets := len(entries)
	pollFailed := false
	anySecretUpdated := false

	for from := 0; from < len(entries); from += maxBatchSecrets {
		to := from + maxBatchSecrets
		if to > len(entries) {
			to = len(entries)
		}
		chunk := entries[from:to]

		body, err := json.Marshal(request.BatchGetSecretsRequest{Secrets: chunk})
		if err != nil {
			pollFailed = true
			p.logger.Error("Batch refresh marshal failed",
				"requestId", requestID,
				"from", from+1, "to", to,
				"error", err)
			continue
		}

		if totalSecrets > maxBatchSecrets {
			p.logger.Debug("Polling batch refresh chunk",
				"requestId", requestID, "from", from+1, "to", to, "total", totalSecrets)
		} else {
			p.logger.Debug("Polling batch refresh",
				"requestId", requestID, "total", totalSecrets)
		}

		var parsed response.Response[response.BatchGetSecretsResponse]
		status, postErr := p.httpClient.DoPostWithRetry(ctx, p.batchURL, body, &parsed)
		if postErr != nil {
			pollFailed = true
			statusCode = resolveStatusCode(postErr, status)
			p.logger.Error("Batch refresh failed",
				"requestId", requestID,
				"from", from+1, "to", to,
				"status", statusCode,
				"error", postErr)
			continue
		}
		if !pollFailed {
			statusCode = status
		}

		payload := parsed.Data
		if len(payload.UpdatedSecrets) == 0 {
			continue
		}
		for _, item := range payload.UpdatedSecrets {
			p.handleUpdatedSecret(item)
			anySecretUpdated = true
		}
	}

	if !pollFailed && !anySecretUpdated {
		p.logger.Debug("No secret versions advanced this cycle", "requestId", requestID)
	}
}

// PollOnce exposes the internal poll cycle for white-box tests in this
// package and in callers that need deterministic control over polling. It is
// safe to call concurrently with the background scheduler.
func (p *HookRefreshPoller) PollOnce() {
	p.pollOnce()
}

// handleUpdatedSecret stages the new value for the secret (latest-wins) and
// schedules dispatch.
func (p *HookRefreshPoller) handleUpdatedSecret(item response.UpdatedSecret) {
	secretRef := item.ProjectID + ":" + item.SecretName
	state := p.lookup(secretRef)
	if state == nil {
		return
	}
	value := apimodels.SecretValue{
		DataVersion: int(item.DataVersion),
		PublicPart:  item.PublicPart,
		PrivatePart: item.PrivatePart,
	}
	// Atomic store — overwrites any older pending update (latest-wins coalescing).
	state.PendingUpdate.Store(&value)
	p.submit(func() { p.runHooksFor(secretRef, state) })
}

// runHooksFor drains state.PendingUpdate and invokes every hook sequentially.
// Non-reentrant per-secret: if another worker is already draining, this call
// returns immediately. The worker that "owns" execution drains in a loop so
// updates that arrive between iterations are still delivered.
func (p *HookRefreshPoller) runHooksFor(secretRef string, state *SecretState) {
	if !state.IsExecuting.CompareAndSwap(false, true) {
		return
	}
	defer func() {
		state.IsExecuting.Store(false)
		// Wake-up race: an update may have landed between the last
		// PendingUpdate.Swap(nil) and the IsExecuting.Store(false) above. If
		// so, re-submit so we don't strand the value.
		if state.PendingUpdate.Load() != nil {
			p.submit(func() { p.runHooksFor(secretRef, state) })
		}
	}()

	for {
		next := state.PendingUpdate.Swap(nil)
		if next == nil {
			return
		}
		p.deliverToHooks(secretRef, state, *next)
		state.LastKnownVersion.Store(int32(next.DataVersion))
	}
}

// deliverToHooks invokes every registered hook with value, recording per-hook
// latency / status. A hook that returns an error or panics is logged but does
// not affect delivery to the remaining hooks (matching Java's try/catch).
func (p *HookRefreshPoller) deliverToHooks(secretRef string, state *SecretState, value apimodels.SecretValue) {
	hooks := state.Hooks() // stable snapshot — see SecretState.Hooks for guarantees.
	for _, hook := range hooks {
		startNs := time.Now()
		status := 200
		func() {
			defer func() {
				if r := recover(); r != nil {
					status = 500
					p.logger.Error("Consumer hook panicked",
						"secretRef", secretRef, "panic", r)
				}
			}()
			if err := hook(value); err != nil {
				status = 500
				p.logger.Error("Consumer hook failed",
					"secretRef", secretRef, "error", err)
			}
		}()
		duration := time.Since(startNs)
		p.metrics.RecordRequest("hook.execute."+secretRef, status, duration)
	}
}

// submit enqueues task on the dispatcher channel. If the channel is full
// (extreme back-pressure) the task is run inline on the caller's goroutine so
// we never silently drop an update; this preserves "latest-wins is delivered"
// even under load. If Close has been invoked, the task is dropped.
func (p *HookRefreshPoller) submit(task func()) {
	if p.cancelCtx.Err() != nil {
		return
	}
	select {
	case p.dispatcherCh <- task:
	default:
		p.logger.Warn("Hook dispatcher queue full; running inline")
		safeRun(p.logger, task)
	}
}

// Close stops the scheduler, drains the dispatcher, and bounds total
// shutdown time at shutdownAwait. Safe to call multiple times; only the
// first call performs work.
func (p *HookRefreshPoller) Close() {
	p.closeOnce.Do(func() {
		p.cancelFn()
		// Wait for the scheduler to exit before closing the dispatcher
		// channel: this guarantees no further sends.
		schedDone := make(chan struct{})
		go func() {
			p.schedulerWg.Wait()
			close(schedDone)
		}()
		select {
		case <-schedDone:
		case <-time.After(shutdownAwait):
			p.logger.Warn("Poller scheduler did not stop within deadline; closing dispatcher anyway",
				"timeout", shutdownAwait)
		}

		close(p.dispatcherCh)

		workersDone := make(chan struct{})
		go func() {
			p.workersWg.Wait()
			close(workersDone)
		}()
		select {
		case <-workersDone:
		case <-time.After(shutdownAwait):
			p.logger.Warn("Poller dispatcher did not drain within deadline",
				"timeout", shutdownAwait)
		}
	})
}

// buildBatchURL safely appends "/v1/secrets/batch" to baseURL. It panics on
// invalid URLs because the baseURL has already been validated upstream by the
// configuration validator.
func buildBatchURL(baseURL string) string {
	parsed, err := url.Parse(strings.TrimRight(baseURL, "/"))
	if err != nil {
		panic(fmt.Errorf("grayskull: invalid baseURL: %w", err))
	}
	parsed.Path = strings.TrimRight(parsed.Path, "/") + "/v1/secrets/batch"
	return parsed.String()
}

// resolveStatusCode prefers an HTTP status embedded inside a typed Grayskull
// error over the transport-layer status, since the typed status is what the
// server actually returned (or 500 for a network failure). Mirrors
// GrayskullException.getStatusCode() in Java.
func resolveStatusCode(err error, fallback int) int {
	type statusCarrier interface {
		StatusCode() int
	}
	var sc *grayskullErrors.GrayskullError
	if errors.As(err, &sc) {
		if code := sc.StatusCode(); code > 0 {
			return code
		}
	}
	if fallback > 0 {
		return fallback
	}
	return 500
}

// safeRun executes fn and logs any panic; it never lets a panicking hook tear
// down the dispatcher worker.
func safeRun(logger *slog.Logger, fn func()) {
	defer func() {
		if r := recover(); r != nil {
			logger.Error("Dispatcher task panicked", "panic", r)
		}
	}()
	fn()
}

// noopRecorder is a fallback metrics.MetricsRecorder used only when a caller
// constructs the poller without a recorder. It deliberately drops everything.
type noopRecorder struct{}

func (noopRecorder) RecordRequest(string, int, time.Duration) {}
func (noopRecorder) RecordRetry(string, int, bool)            {}
