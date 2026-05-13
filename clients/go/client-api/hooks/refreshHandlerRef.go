package hooks

// RefreshHandlerRef is an interface that manages the lifecycle of a registered secret refresh hook.
// It provides methods to inspect the status of a registered hook and to unregister it when it's no longer needed.
// Instances are returned by GrayskullClient.RegisterRefreshHook.
type RefreshHandlerRef interface {
	// GetSecretRef returns the secret reference this hook is registered for.
	// Returns the secret reference in format "projectId:secretName", or empty string for no-op implementations.
	GetSecretRef() string

	// IsActive checks whether this hook is currently active and will be invoked on secret updates.
	// Returns true if the hook is active, false if inactive or unregistered.
	IsActive() bool

	// Unregister unregisters this hook, preventing future invocations.
	// After calling this method, IsActive() will return false.
	// This method is idempotent - calling it multiple times has no additional effect.
	Unregister()
}
