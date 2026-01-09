package models

// SecretValue represents a secret value retrieved from Grayskull.
//
// Contains the actual secret data including both public and private parts,
// along with version information.
type SecretValue struct {
	// DataVersion represents the version number of the secret data.
	DataVersion int `json:"dataVersion"`

	// PublicPart contains the public part of the secret.
	PublicPart string `json:"publicPart"`

	// PrivatePart contains the private/sensitive part of the secret.
	PrivatePart string `json:"privatePart"`
}
