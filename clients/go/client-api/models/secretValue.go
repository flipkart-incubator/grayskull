package models

// SecretValue represents a secret value with versioning
type SecretValue struct {
	// DataVersion represents the version number of the secret data
	DataVersion int `json:"dataVersion"`

	// PublicPart contains the public part of the secret
	PublicPart string `json:"publicPart"`

	// PrivatePart contains the private/sensitive part of the secret
	PrivatePart string `json:"privatePart,omitempty"`
}
