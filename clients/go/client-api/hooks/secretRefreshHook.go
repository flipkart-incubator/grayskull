package hooks

import (
	"context"

	"github.com/flipkart-incubator/grayskull/clients/go/client-api/models"
)

// SecretRefreshHook is invoked when a monitored secret is updated. ctx
// carries the poll-cycle request id and is cancelled on SDK shutdown;
// consumers should honor it for blocking work.
type SecretRefreshHook func(ctx context.Context, secret models.SecretValue) error
