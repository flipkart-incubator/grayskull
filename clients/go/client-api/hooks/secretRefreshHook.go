package hooks

import "github.com/flipkart-incubator/grayskull/clients/go/client-api/models"

// SecretRefreshHook is invoked when a monitored secret is updated.
type SecretRefreshHook func(secret models.SecretValue) error
