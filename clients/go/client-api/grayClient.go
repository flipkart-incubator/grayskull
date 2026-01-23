package client_api

import (
	"context"
	"github.com/flipkart-incubator/grayskull/clients/go/client-api/hooks"
	"github.com/flipkart-incubator/grayskull/clients/go/client-api/models"
)

// Client defines the interface for interacting with Grayskull secret management
type Client interface {
	// GetSecret retrieves a secret by its reference
	GetSecret(ctx context.Context, secretRef string) (*models.SecretValue, error)

	// RegisterRefreshHook registers a refresh hook for a secret.
	// Note: This is a placeholder implementation. The hook will be registered but
	// will not be invoked until the refresh mechanism (e.g., long-polling or SSE) is implemented.
	RegisterRefreshHook(ctx context.Context, secretRef string, hook hooks.SecretRefreshHook) (hooks.RefreshHandlerRef, error)
}
