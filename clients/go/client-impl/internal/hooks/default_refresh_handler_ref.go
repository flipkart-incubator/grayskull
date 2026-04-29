package hooks

import (
	"log/slog"
	"sync/atomic"

	apihooks "github.com/flipkart-incubator/grayskull/clients/go/client-api/hooks"
)

// DefaultRefreshHandlerRef is the RefreshHandlerRef returned by
// HookRefreshPoller.Register. Unregister is idempotent: only the first call
// runs the underlying onUnregister callback; subsequent calls are no-ops.
//
// Mirrors com.flipkart.grayskull.hooks.DefaultRefreshHandlerRef.
type DefaultRefreshHandlerRef struct {
	secretRef    string
	onUnregister func()
	active       atomic.Bool
}

// Compile-time interface satisfaction check.
var _ apihooks.RefreshHandlerRef = (*DefaultRefreshHandlerRef)(nil)

// NewDefaultRefreshHandlerRef creates a handle for a hook registered against
// secretRef. onUnregister is invoked exactly once — on the first call to
// Unregister — and must not be nil.
func NewDefaultRefreshHandlerRef(secretRef string, onUnregister func()) *DefaultRefreshHandlerRef {
	r := &DefaultRefreshHandlerRef{
		secretRef:    secretRef,
		onUnregister: onUnregister,
	}
	r.active.Store(true)
	return r
}

// GetSecretRef returns the "projectId:secretName" reference this handle was
// registered against.
func (r *DefaultRefreshHandlerRef) GetSecretRef() string {
	return r.secretRef
}

// IsActive reports whether the hook is still registered.
func (r *DefaultRefreshHandlerRef) IsActive() bool {
	return r.active.Load()
}

// Unregister deactivates the handle and invokes the onUnregister callback
// the first time it is called. Subsequent calls are silent no-ops.
func (r *DefaultRefreshHandlerRef) Unregister() {
	if !r.active.CompareAndSwap(true, false) {
		return
	}
	if r.onUnregister != nil {
		r.onUnregister()
	}
	slog.Debug("Unregistered refresh hook", "secretRef", r.secretRef)
}
