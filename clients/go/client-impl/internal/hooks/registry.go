package hooks

import (
	"sync"

	apiHooks "github.com/flipkart-incubator/grayskull/clients/go/client-api/hooks"
)

// Registry owns per-secret hook state. Safe for concurrent register,
// unregister, and iteration.
type Registry struct {
	mu    sync.RWMutex
	byRef map[string]*SecretState
}

// NewRegistry returns an empty Registry.
func NewRegistry() *Registry {
	return &Registry{byRef: make(map[string]*SecretState)}
}

// Register attaches hook to (projectID, secretName). initialKnownVersion
// seeds LastKnownVersion only on first registration for the secret;
// subsequent calls leave the existing version alone. Returns the handle
// used to unregister.
func (r *Registry) Register(projectID, secretName string, hook apiHooks.SecretRefreshHook, initialKnownVersion int) *DefaultRefreshHandlerRef {
	secretRef := projectID + ":" + secretName

	r.mu.Lock()
	state, ok := r.byRef[secretRef]
	if !ok {
		state = newSecretState(projectID, secretName)
		state.LastKnownVersion.Store(int32(initialKnownVersion))
		r.byRef[secretRef] = state
	}

	var handlerRef *DefaultRefreshHandlerRef
	handlerRef = NewDefaultRefreshHandlerRef(secretRef, func() {
		r.removeEntry(secretRef, handlerRef)
	})

	state.mu.Lock()
	state.hooks = append(state.hooks, hookEntry{handlerRef: handlerRef, hook: hook})
	state.mu.Unlock()
	r.mu.Unlock()

	return handlerRef
}

// removeEntry drops the entry matching handlerRef. Deletes the SecretState
// when its last hook is removed.
func (r *Registry) removeEntry(secretRef string, handlerRef *DefaultRefreshHandlerRef) {
	r.mu.Lock()
	defer r.mu.Unlock()
	state, ok := r.byRef[secretRef]
	if !ok {
		return
	}
	state.mu.Lock()
	for i, e := range state.hooks {
		if e.handlerRef == handlerRef {
			state.hooks = append(state.hooks[:i], state.hooks[i+1:]...)
			break
		}
	}
	empty := len(state.hooks) == 0
	state.mu.Unlock()
	if empty {
		delete(r.byRef, secretRef)
	}
}

// Get returns the SecretState for secretRef, or nil if absent.
func (r *Registry) Get(secretRef string) *SecretState {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return r.byRef[secretRef]
}

// Snapshot returns a copy of every registered SecretState; the returned
// slice must not be mutated.
func (r *Registry) Snapshot() []*SecretState {
	r.mu.RLock()
	defer r.mu.RUnlock()
	if len(r.byRef) == 0 {
		return nil
	}
	out := make([]*SecretState, 0, len(r.byRef))
	for _, v := range r.byRef {
		out = append(out, v)
	}
	return out
}
