package heartbeat

// Request is the body of POST /v1/heartbeat.
type Request struct {
	Secrets []HeldSecret `json:"secrets"`
}

// HeldSecret is one secret version held by the client.
type HeldSecret struct {
	ProjectID   string `json:"projectId"`
	SecretName  string `json:"secretName"`
	Version     int    `json:"version"`
	RefreshHook bool   `json:"refreshHook"`
}
