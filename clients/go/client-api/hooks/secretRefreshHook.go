package hooks

import "github.com/flipkart-incubator/grayskull/clients/go/client-api/models"

// SecretRefreshHook defines an interface for callbacks that are invoked when a secret is updated.
//
// This hook is registered via GrayskullClient.RegisterRefreshHook and will be called
// asynchronously when the monitored secret is updated on the server.
//
// Note: This is currently part of a placeholder implementation. While hooks can be registered,
// they will not be invoked until the refresh mechanism (e.g., long-polling or SSE) is implemented.
type SecretRefreshHook interface {
	OnUpdate(secret models.SecretValue) error
}
