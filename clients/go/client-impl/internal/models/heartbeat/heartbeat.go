package heartbeat

// Request is the body of POST /v1/client/heartbeat.
type Request struct {
	Secrets []HeldSecret `json:"secrets"`
}

// HeldSecret is one usage-ledger entry.
type HeldSecret struct {
	ProjectID                 string `json:"projectId"`
	SecretName                string `json:"secretName"`
	Version                   int    `json:"version"`
	RefreshHookAppliedVersion *int   `json:"refreshHookAppliedVersion,omitempty"`
}
