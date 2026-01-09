package client

import (
	"context"
	"github.com/grayskull/client/models"
)

// Client defines the interface for interacting with Grayskull secret management
type Client interface {
	// GetSecret retrieves a secret by its reference
	GetSecret(ctx context.Context, secretRef string) (*models.SecretValue, error)

	// Close releases any resources used by the client
	Close() error
}