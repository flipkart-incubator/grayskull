package grayskull

import "time"

// Violation represents a validation error
type Violation struct {
	Field   string `json:"field"`
	Message string `json:"message"`
}

// Secret represents a secret in Grayskull
type Secret struct {
	ProjectID          string            `json:"projectId"`
	Name               string            `json:"name"`
	SystemLabels       map[string]string `json:"systemLabels,omitempty"`
	CurrentDataVersion int               `json:"currentDataVersion"`
	LastRotated        time.Time         `json:"lastRotated,omitempty"`
	CreationTime       time.Time         `json:"creationTime"`
	UpdatedTime        time.Time         `json:"updatedTime"`
	CreatedBy          string            `json:"createdBy,omitempty"`
	UpdatedBy          string            `json:"updatedBy,omitempty"`
	State              string            `json:"state,omitempty"`
	Provider           string            `json:"provider,omitempty"`
	ProviderMeta       map[string]string `json:"providerMeta,omitempty"`
	MetadataVersion    int               `json:"metadataVersion"`
}

// ListSecretsResponse represents the API response for listing secrets
type ListSecretsResponse struct {
	Data struct {
		Secrets []Secret `json:"secrets"`
		Total   int      `json:"total"`
	} `json:"data"`
	Message    string      `json:"message,omitempty"`
	Code       string      `json:"code,omitempty"`
	Violations []Violation `json:"violations,omitempty"`
}

// SecretData represents the data of a secret
type SecretData struct {
	DataVersion int            `json:"dataVersion"`
	Data        map[string]any `json:"data"`
}

// SecretProvider represents a secret provider in Grayskull
type SecretProvider struct {
	Name        string            `json:"name"`
	Type        string            `json:"type"`
	Description string            `json:"description,omitempty"`
	Config      map[string]string `json:"config"`
	CreatedAt   time.Time         `json:"createdAt"`
	UpdatedAt   time.Time         `json:"updatedAt"`
}
