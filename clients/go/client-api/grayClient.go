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

	// RegisterRefreshHook registers hook to be invoked whenever the server reports
	// a newer version of secretRef. The background batch poller calls POST /v1/secrets/batch
	// on a fixed interval and delivers updates in registration order with at-most-once
	// semantics. The returned RefreshHandlerRef can be used to unregister the hook.
	RegisterRefreshHook(ctx context.Context, secretRef string, hook hooks.SecretRefreshHook) (hooks.RefreshHandlerRef, error)
}
