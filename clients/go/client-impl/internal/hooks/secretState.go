package hooks

import (
	"sync"
	"sync/atomic"

	apiHooks "github.com/flipkart-incubator/grayskull/clients/go/client-api/hooks"
	"github.com/flipkart-incubator/grayskull/clients/go/client-api/models"
)

// hookEntry pairs a hook with the handler ref token that registered it
type hookEntry struct {
	token *DefaultRefreshHandlerRef
	hook  apiHooks.SecretRefreshHook
}

// SecretState is the per-secret runtime state owned by the registry.
type SecretState struct {
	ProjectID  string
	SecretName string

	// LastKnownVersion is the highest version delivered to hooks so far. The
	// poller advances this BEFORE invoking hooks (at-most-once semantics).
	LastKnownVersion atomic.Int32

	// pending holds at most one staged update. Newer updates overwrite older
	// ones so consumers always observe the latest value, never a stale one.
	pending atomic.Pointer[models.SecretValue]

	// isExecuting guarantees only one dispatcher goroutine runs hooks for
	// this secret at any time (non-reentrant per-secret).
	isExecuting atomic.Bool

	mu    sync.RWMutex
	hooks []hookEntry // ordered by registration time
}

func newSecretState(projectID, secretName string) *SecretState {
	return &SecretState{ProjectID: projectID, SecretName: secretName}
}

// SetPending stages an update for later delivery.
func (s *SecretState) SetPending(v *models.SecretValue) { s.pending.Store(v) }

// TakePending drains and returns the staged value (nil if none).
func (s *SecretState) TakePending() *models.SecretValue { return s.pending.Swap(nil) }

// HasPending reports whether a value is staged.
func (s *SecretState) HasPending() bool { return s.pending.Load() != nil }

// TryAcquireExecution flips the executing flag from false to true.
// Returns false if another runner already owns the slot.
func (s *SecretState) TryAcquireExecution() bool {
	return s.isExecuting.CompareAndSwap(false, true)
}

// ReleaseExecution clears the executing flag.
func (s *SecretState) ReleaseExecution() { s.isExecuting.Store(false) }

// SnapshotHooks returns a copy of the registered hooks in registration order.
// Returning a copy keeps the caller from holding the lock during invocation.
func (s *SecretState) SnapshotHooks() []apiHooks.SecretRefreshHook {
	s.mu.RLock()
	defer s.mu.RUnlock()
	if len(s.hooks) == 0 {
		return nil
	}
	out := make([]apiHooks.SecretRefreshHook, 0, len(s.hooks))
	for _, e := range s.hooks {
		out = append(out, e.hook)
	}
	return out
}
