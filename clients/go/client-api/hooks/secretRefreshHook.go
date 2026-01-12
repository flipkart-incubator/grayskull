package hooks

import "github.com/grayskull/client-api/models"

// SecretRefreshHook defines an interface for callbacks that are invoked when a secret is updated.
//
// This hook is registered via GrayskullClient.RegisterRefreshHook and will be called
// asynchronously when the monitored secret is updated on the server.
//
// Note: This is currently part of a placeholder implementation. While hooks can be registered,
// they will not be invoked until server-sent events support is added in a future release.
type SecretRefreshHook interface {
	OnUpdate(secret models.SecretValue) error
}
