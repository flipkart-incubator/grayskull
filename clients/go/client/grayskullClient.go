// Package client provides the main interface for interacting with the Grayskull secret management service.
// It provides methods for retrieving secrets and registering refresh hooks.
//
// This client is safe for concurrent use by multiple goroutines.
package client

import (
	"context"
	"github.com/grayskull/go-client/client/hooks"
	"github.com/grayskull/go-client/client/models"
)

// GrayskullClient is the main interface for interacting with the Grayskull secret management service.
// It provides methods for retrieving secrets and registering refresh hooks.
//
// This client is safe for concurrent use by multiple goroutines.
type GrayskullClient interface {
	// GetSecret retrieves a secret from the Grayskull server.
	// The secretRef should be in the format "projectId:secretName".
	// For example: "my-project:database-password"
	GetSecret(ctx context.Context, secretRef string) (*models.SecretValue, error)

	// RegisterRefreshHook registers a callback function to be invoked when a secret is updated.
	// The hook will be called asynchronously whenever the server pushes an update for the monitored secret.
	//
	// Parameters:
	//   - secretRef: The secret reference to monitor, in format "projectId:secretName"
	//   - hook: The callback function to execute when the secret is updated
	//
	// Returns:
	//   - RefreshHandlerRef: A handle that can be used to unregister the hook
	//   - error: An error if the registration fails
	RegisterRefreshHook(secretRef string, hook hooks.SecretRefreshHook) (hooks.RefreshHandlerRef, error)

	// Close releases any resources used by the client.
	// It should be called when the client is no longer needed.
	Close() error
}
