// Package hooks contains internal building blocks for the Grayskull Go SDK's
// server-driven refresh-hook machinery: the per-secret runtime state, the
// default RefreshHandlerRef returned to applications, and the background
// HookRefreshPoller that owns the dispatch lifecycle.
package hooks

import (
	"sync"
	"sync/atomic"

	apihooks "github.com/flipkart-incubator/grayskull/clients/go/client-api/hooks"
	apimodels "github.com/flipkart-incubator/grayskull/clients/go/client-api/models"
)

// SecretState holds the per-secret runtime state managed by HookRefreshPoller.
//
// Mirrors com.flipkart.grayskull.hooks.SecretState in the Java SDK. All fields
// are safe for concurrent use:
//
//   - LastKnownVersion is updated only after a successful hook delivery, so
//     races on it are benign (CAS-equivalent semantics via atomic.Int32).
//   - hooks is guarded by a copy-on-write slice protected by hooksMu, giving
//     readers (the dispatcher iterating over the list) a stable snapshot
//     without blocking concurrent unregister operations. This mirrors Java's
//     CopyOnWriteArrayList.
//   - IsExecuting acts as a non-reentrant per-secret latch (see runHooksFor).
//   - PendingUpdate stages the latest unprocessed update; older values are
//     coalesced (overwritten) by newer ones.
type SecretState struct {
	ProjectID  string
	SecretName string

	LastKnownVersion atomic.Int32

	hooksMu sync.RWMutex
	hooks   []apihooks.SecretRefreshHook

	IsExecuting   atomic.Bool
	PendingUpdate atomic.Pointer[apimodels.SecretValue]
}

// NewSecretState constructs a SecretState for (projectID, secretName).
func NewSecretState(projectID, secretName string) *SecretState {
	return &SecretState{
		ProjectID:  projectID,
		SecretName: secretName,
	}
}

// AddHook appends hook to the registered hooks list using copy-on-write so
// concurrent iteration via Hooks() is safe and lock-free for readers.
func (s *SecretState) AddHook(hook apihooks.SecretRefreshHook) {
	s.hooksMu.Lock()
	defer s.hooksMu.Unlock()
	// Defensive copy: writers never mutate a slice that may be observed by readers.
	cp := make([]apihooks.SecretRefreshHook, len(s.hooks)+1)
	copy(cp, s.hooks)
	cp[len(s.hooks)] = hook
	s.hooks = cp
}

// RemoveHook removes the first occurrence of hook from the registered hooks.
// Removal is identity-based on the function value: SecretRefreshHook is a
// function type and is therefore matched by reference. Returns true when a
// hook was removed.
//
// Note: Go forbids comparing function values with == and instead requires
// reflect.ValueOf(...).Pointer() to compare the underlying code pointers.
func (s *SecretState) RemoveHook(hook apihooks.SecretRefreshHook) bool {
	s.hooksMu.Lock()
	defer s.hooksMu.Unlock()
	idx := indexOfHook(s.hooks, hook)
	if idx < 0 {
		return false
	}
	cp := make([]apihooks.SecretRefreshHook, 0, len(s.hooks)-1)
	cp = append(cp, s.hooks[:idx]...)
	cp = append(cp, s.hooks[idx+1:]...)
	s.hooks = cp
	return true
}

// Hooks returns a stable snapshot of the registered hooks. The returned slice
// is safe to iterate concurrently with AddHook / RemoveHook calls because the
// underlying array is never mutated in place.
func (s *SecretState) Hooks() []apihooks.SecretRefreshHook {
	s.hooksMu.RLock()
	defer s.hooksMu.RUnlock()
	return s.hooks
}

// HookCount returns the current number of registered hooks. It is intended
// for tests and diagnostics; callers should not rely on it being consistent
// with a subsequent Hooks() call from a different goroutine.
func (s *SecretState) HookCount() int {
	s.hooksMu.RLock()
	defer s.hooksMu.RUnlock()
	return len(s.hooks)
}

// IsEmpty reports whether no hooks remain registered.
func (s *SecretState) IsEmpty() bool {
	return s.HookCount() == 0
}
