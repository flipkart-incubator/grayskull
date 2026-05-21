package hooks

import (
	"log/slog"
	"sync/atomic"

	apiHooks "github.com/flipkart-incubator/grayskull/clients/go/client-api/hooks"
)

// DefaultRefreshHandlerRef is  returned by RegisterRefreshHook
// when the background poller is wired up. Unregister is idempotent
type DefaultRefreshHandlerRef struct {
	secretRef    string
	active       atomic.Bool
	onUnregister func()
}

var _ apiHooks.RefreshHandlerRef = (*DefaultRefreshHandlerRef)(nil)

// NewDefaultRefreshHandlerRef returns a handle bound to secretRef. onUnregister
// runs at most once, on the first Unregister call.
func NewDefaultRefreshHandlerRef(secretRef string, onUnregister func()) *DefaultRefreshHandlerRef {
	r := &DefaultRefreshHandlerRef{secretRef: secretRef, onUnregister: onUnregister}
	r.active.Store(true)
	return r
}

func (r *DefaultRefreshHandlerRef) GetSecretRef() string { return r.secretRef }
func (r *DefaultRefreshHandlerRef) IsActive() bool       { return r.active.Load() }

func (r *DefaultRefreshHandlerRef) Unregister() {
	if !r.active.CompareAndSwap(true, false) {
		return
	}
	if r.onUnregister != nil {
		r.onUnregister()
	}
	slog.Debug("Unregistered refresh hook", "secretRef", r.secretRef)
}
