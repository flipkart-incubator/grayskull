// Package request holds wire-format request bodies sent by the Grayskull Go SDK.
//
// The JSON shapes here are part of the SDK <-> server contract and must remain
// byte-identical to the corresponding Java DTOs in com.flipkart.grayskull.models.request.
package request

// BatchGetSecretsRequest is the request body for POST /v1/secrets/batch.
//
// Mirrors com.flipkart.grayskull.models.request.BatchGetSecretsRequest.
type BatchGetSecretsRequest struct {
	// Secrets is the list of secrets the client is asking the server about.
	Secrets []BatchGetSecretsRequestEntry `json:"secrets"`
}

// BatchGetSecretsRequestEntry is one row in BatchGetSecretsRequest.Secrets,
// describing a single secret and the version the client most recently observed.
//
// Mirrors com.flipkart.grayskull.models.request.BatchGetSecretsRequest.Entry.
type BatchGetSecretsRequestEntry struct {
	// ProjectID is the project that owns the secret.
	ProjectID string `json:"projectId"`

	// SecretName is the name of the secret within the project.
	SecretName string `json:"secretName"`

	// LastKnownVersion is the highest dataVersion the client has already
	// processed; the server returns a fresher value only when its current
	// version is strictly greater.
	LastKnownVersion int32 `json:"lastKnownVersion"`
}
