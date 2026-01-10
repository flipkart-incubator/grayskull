package hooks

import (
	"github.com/grayskull/client/hooks"
	"log/slog"
)

// NoOpRefreshHandlerRef is a placeholder implementation of RefreshHandlerRef.
// It allows clients to register hooks without errors, but the hooks are not invoked
// until full server-sent events support is implemented in a future version.
type NoOpRefreshHandlerRef struct{}

var (
	// Instance is the singleton instance of the no-op refresh hook handle.
	Instance = &NoOpRefreshHandlerRef{}
)

// GetSecretRef returns an empty string as this is a no-op implementation.
func (n *NoOpRefreshHandlerRef) GetSecretRef() string {
	return ""
}

// IsActive always returns false for this no-op implementation.
func (n *NoOpRefreshHandlerRef) IsActive() bool {
	return false
}

// Unregister logs a debug message and performs no operation.
func (n *NoOpRefreshHandlerRef) Unregister() {
	slog.Debug("Unregister called on no-op refresh hook handle (placeholder implementation)")
	// No-op
}

// Ensure NoOpRefreshHandlerRef implements RefreshHandlerRef
var _ hooks.RefreshHandlerRef = (*NoOpRefreshHandlerRef)(nil)
