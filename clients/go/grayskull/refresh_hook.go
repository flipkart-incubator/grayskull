package grayskull

import (
	"fmt"
	"sync"
)

// SecretRefreshHook is a function type for secret refresh callbacks.
// It will be called when a secret is updated on the server.
type SecretRefreshHook func(secret *Secret) error

// RefreshHandlerRef is an interface for managing the lifecycle of a registered refresh hook
type RefreshHandlerRef interface {
	// GetSecretRef returns the secret reference this hook is registered for
	GetSecretRef() string
	// IsActive returns true if the hook is active and will be called on updates
	IsActive() bool
	// Unregister removes the hook so it won't be called on future updates
	Unregister()
}

// refreshHandlerRef implements the RefreshHandlerRef interface
type refreshHandlerRef struct {
	client     *Client
	projectID  string
	secretName string
	hook       SecretRefreshHook
	isActive   bool
	mu         sync.RWMutex
}

// newRefreshHandlerRef creates a new refreshHandlerRef instance
func newRefreshHandlerRef(client *Client, projectID, secretName string, hook SecretRefreshHook) *refreshHandlerRef {
	return &refreshHandlerRef{
		client:     client,
		projectID:  projectID,
		secretName: secretName,
		hook:       hook,
		isActive:   true,
	}
}

// GetSecretRef returns the secret reference in "projectId:secretName" format
func (r *refreshHandlerRef) GetSecretRef() string {
	return fmt.Sprintf("%s:%s", r.projectID, r.secretName)
}

// IsActive returns whether the hook is active
func (r *refreshHandlerRef) IsActive() bool {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return r.isActive
}

// Unregister unregisters the refresh hook
func (r *refreshHandlerRef) Unregister() {
	r.mu.Lock()
	defer r.mu.Unlock()

	if r.isActive {
		// TODO: Implement actual unregistration when SSE is implemented
		r.isActive = false
	}
}

// RegisterRefreshHook registers a callback function that will be called when the specified secret is updated.
// The returned RefreshHandlerRef can be used to unregister the hook when it's no longer needed.
// Note: This is currently a placeholder implementation. The hook will not be called until server-sent events
// support is added in a future release.
func (c *Client) RegisterRefreshHook(projectID, secretName string, hook SecretRefreshHook) RefreshHandlerRef {
	// Create a new handler reference
	handler := newRefreshHandlerRef(c, projectID, secretName, hook)

	// TODO: Implement actual server-sent events subscription
	// For now, this is a no-op implementation

	return handler
}

// unregisterRefreshHook removes a registered refresh hook
func (c *Client) unregisterRefreshHook(projectID, secretName string, hook SecretRefreshHook) {
	// TODO: Implement actual unregistration logic when SSE is implemented
}
