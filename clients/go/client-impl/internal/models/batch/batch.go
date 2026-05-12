package batch

// BatchGetSecretsRequest is the request body for POST /v1/secrets/batch.
type BatchGetSecretsRequest struct {
	Secrets []Entry `json:"secrets"`
}

// Entry is one row in a BatchGetSecretsRequest. LastKnownVersion is the
// version the caller has already observed for (ProjectID, SecretName); the
// server replies with rows whose version is strictly greater.
type Entry struct {
	ProjectID        string `json:"projectId"`
	SecretName       string `json:"secretName"`
	LastKnownVersion int    `json:"lastKnownVersion"`
}

// BatchGetSecretsResponse is the response body for POST /v1/secrets/batch.
type BatchGetSecretsResponse struct {
	UpdatedCount   int             `json:"updatedCount"`
	UpdatedSecrets []UpdatedSecret `json:"updatedSecrets"`
}

// UpdatedSecret is a single row in BatchGetSecretsResponse.
type UpdatedSecret struct {
	ProjectID   string `json:"projectId"`
	SecretName  string `json:"secretName"`
	DataVersion int    `json:"dataVersion"`
	PublicPart  string `json:"publicPart"`
	PrivatePart string `json:"privatePart,omitempty"`
}
