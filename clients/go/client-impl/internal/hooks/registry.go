package hooks

import (
	"sync"

	apiHooks "github.com/flipkart-incubator/grayskull/clients/go/client-api/hooks"
)

// Registry owns per-secret hook state. Concurrent-safe for interleaved register /
// unregister / iteration.
type Registry struct {
	mu    sync.RWMutex
	byRef map[string]*SecretState
}

// NewRegistry returns an empty Registry.
func NewRegistry() *Registry {
	return &Registry{byRef: make(map[string]*SecretState)}
}

// Register attaches hook to (projectID, secretName), seeding lastKnownVersion
// from initialKnownVersion only when the state row is newly created. Subsequent
// registrations for the same secret leave the existing version untouched so the
// poller continues to advance it correctly. Returns the handle the caller uses
// to remove the hook.
func (r *Registry) Register(projectID, secretName string, hook apiHooks.SecretRefreshHook, initialKnownVersion int) *DefaultRefreshHandlerRef {
	secretRef := projectID + ":" + secretName

	r.mu.Lock()
	state, ok := r.byRef[secretRef]
	if !ok {
		state = newSecretState(projectID, secretName)
		state.LastKnownVersion.Store(int32(initialKnownVersion))
		r.byRef[secretRef] = state
	}

	var token *DefaultRefreshHandlerRef
	token = NewDefaultRefreshHandlerRef(secretRef, func() {
		r.removeEntry(secretRef, token)
	})

	state.mu.Lock()
	state.hooks = append(state.hooks, hookEntry{token: token, hook: hook})
	state.mu.Unlock()
	r.mu.Unlock()

	return token
}

// removeEntry drops the entry whose token pointer matches
func (r *Registry) removeEntry(secretRef string, token *DefaultRefreshHandlerRef) {
	r.mu.Lock()
	defer r.mu.Unlock()
	state, ok := r.byRef[secretRef]
	if !ok {
		return
	}
	state.mu.Lock()
	for i, e := range state.hooks {
		if e.token == token {
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

// Snapshot returns a copy of every registered SecretState. Callers must not
// mutate the returned slice.
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

// Len returns the number of registered secrets.
func (r *Registry) Len() int {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return len(r.byRef)
}
