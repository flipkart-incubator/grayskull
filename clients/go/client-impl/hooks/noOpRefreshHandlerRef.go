package hooks

import "log"

// NoOpRefreshHandlerRef is a placeholder implementation of RefreshHandlerRef.
// This allows clients to register hooks without errors, but the hooks are not invoked
// until full server sent events support is implemented in a future version.
type NoOpRefreshHandlerRef struct{}

var (
	// Instance is the singleton instance of the no-op refresh hook handle.
	Instance = &NoOpRefreshHandlerRef{}
)

// GetSecretRef returns an empty string as this is a no-op implementation.
func (n *NoOpRefreshHandlerRef) GetSecretRef() string {
	return ""
}

// IsActive always returns false as this is a no-op implementation.
func (n *NoOpRefreshHandlerRef) IsActive() bool {
	return false
}

// Unregister logs a debug message as this is a no-op implementation.
func (n *NoOpRefreshHandlerRef) Unregister() {
	log.Println("Unregister called on no-op refresh hook handle (placeholder implementation)")
	// No-op
}
