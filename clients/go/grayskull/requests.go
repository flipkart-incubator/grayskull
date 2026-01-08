package grayskull

// ListSecretsRequest represents the request parameters for listing secrets
type ListSecretsRequest struct {
	Offset int
	Limit  int
}

// CreateSecretRequest represents the request to create a new secret
type CreateSecretRequest struct {
	Name        string            `json:"name"`
	Description string            `json:"description,omitempty"`
	Tags        map[string]string `json:"tags,omitempty"`
	Data        map[string]any    `json:"data"`
}

// UpgradeSecretDataRequest represents the request to update secret data
type UpgradeSecretDataRequest struct {
	Data map[string]any `json:"data"`
}

// CreateProviderRequest represents the request to create a new secret provider
type CreateProviderRequest struct {
	Name        string            `json:"name"`
	Type        string            `json:"type"`
	Description string            `json:"description,omitempty"`
	Config      map[string]string `json:"config"`
}
