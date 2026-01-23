package hooks

import (
	"github.com/flipkart-incubator/grayskull/clients/go/client-api/hooks"
	"log/slog"
)

// NoOpRefreshHandlerRef is a placeholder implementation of RefreshHandlerRef.
// It allows clients to register hooks without errors, but the hooks are not invoked
// until full server-sent events support is implemented in a future version.
type NoOpRefreshHandlerRef struct{}

// Compile-time check to ensure NoOpRefreshHandlerRef implements RefreshHandlerRef interface
var _ hooks.RefreshHandlerRef = (*NoOpRefreshHandlerRef)(nil)

var (
	// instance is the singleton instance of the no-op refresh hook handle.
	instance = &NoOpRefreshHandlerRef{}
)

// GetInstance returns the singleton instance of the no-op refresh hook handle.
func GetInstance() hooks.RefreshHandlerRef {
	return instance
}

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
