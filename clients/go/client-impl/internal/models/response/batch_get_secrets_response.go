package response

// BatchGetSecretsResponse is the response body for POST /v1/secrets/batch.
//
// Mirrors com.flipkart.grayskull.models.response.BatchGetSecretsResponse.
type BatchGetSecretsResponse struct {
	// UpdatedCount is the number of secrets in UpdatedSecrets. Provided by the
	// server for fast no-update detection without iterating the slice.
	UpdatedCount int `json:"updatedCount"`

	// UpdatedSecrets is the set of secrets whose dataVersion is strictly
	// greater than the client's lastKnownVersion. May be empty.
	UpdatedSecrets []UpdatedSecret `json:"updatedSecrets"`
}

// UpdatedSecret describes a single secret whose value the server believes the
// client should refresh. Mirrors
// com.flipkart.grayskull.models.response.BatchGetSecretsResponse.UpdatedSecret.
type UpdatedSecret struct {
	// ProjectID is the project that owns the secret.
	ProjectID string `json:"projectId"`

	// SecretName is the name of the secret within the project.
	SecretName string `json:"secretName"`

	// DataVersion is the new version of the secret. Always strictly greater
	// than the LastKnownVersion the client sent.
	DataVersion int32 `json:"dataVersion"`

	// PublicPart is the non-sensitive part of the secret payload.
	PublicPart string `json:"publicPart"`

	// PrivatePart is the sensitive part of the secret payload.
	PrivatePart string `json:"privatePart"`
}
