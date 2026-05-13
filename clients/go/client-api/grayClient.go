package client_api

import (
	"context"

	"github.com/flipkart-incubator/grayskull/clients/go/client-api/hooks"
	"github.com/flipkart-incubator/grayskull/clients/go/client-api/models"
)

// Client defines the public API for interacting with Grayskull.
type Client interface {
	// GetSecret retrieves a secret by its reference.
	GetSecret(ctx context.Context, secretRef string) (*models.SecretValue, error)

	// RegisterRefreshHook registers hook to be invoked whenever the server reports
	// a newer version of secretRef.
	RegisterRefreshHook(ctx context.Context, secretRef string, hook hooks.SecretRefreshHook) (hooks.RefreshHandlerRef, error)

	// Close releases background resources used by the client.
	Close() error
}
