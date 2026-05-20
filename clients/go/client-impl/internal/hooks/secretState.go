package hooks

import (
	"sync"
	"sync/atomic"

	apiHooks "github.com/flipkart-incubator/grayskull/clients/go/client-api/hooks"
	"github.com/flipkart-incubator/grayskull/clients/go/client-api/models"
)

// hookEntry pairs a hook with its handler ref (identity key for Unregister).
type hookEntry struct {
	handlerRef *DefaultRefreshHandlerRef
	hook       apiHooks.SecretRefreshHook
}

// SecretState is the per-secret runtime state owned by the registry.
type SecretState struct {
	ProjectID  string
	SecretName string

	// LastKnownVersion is advanced BEFORE hooks run (at-most-once delivery).
	LastKnownVersion atomic.Int32

	// pending holds at most one staged update; newer values overwrite older
	// ones so consumers always see the latest version.
	pending atomic.Pointer[models.SecretValue]

	// isExecuting makes hook execution non-reentrant per-secret.
	isExecuting atomic.Bool

	mu    sync.RWMutex
	hooks []hookEntry // registration order
}

func newSecretState(projectID, secretName string) *SecretState {
	return &SecretState{ProjectID: projectID, SecretName: secretName}
}

// SetPending stages an update. Nil is a no-op (it's TakePending's "empty"
// sentinel; accepting it would silently clear a queued update).
func (s *SecretState) SetPending(v *models.SecretValue) {
	if v == nil {
		return
	}
	s.pending.Store(v)
}

// TakePending drains and returns the staged value, or nil if none.
func (s *SecretState) TakePending() *models.SecretValue { return s.pending.Swap(nil) }

// HasPending reports whether a value is staged.
func (s *SecretState) HasPending() bool { return s.pending.Load() != nil }

// TryAcquireExecution returns true on success, false if another runner
// already owns the slot.
func (s *SecretState) TryAcquireExecution() bool {
	return s.isExecuting.CompareAndSwap(false, true)
}

// ReleaseExecution clears the execution slot.
func (s *SecretState) ReleaseExecution() { s.isExecuting.Store(false) }

// SnapshotHooks returns a copy of the hooks in registration order so callers
// don't hold the lock during invocation.
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
