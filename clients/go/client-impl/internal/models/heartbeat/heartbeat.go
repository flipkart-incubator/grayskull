package heartbeat

const (
	SchemaVersion   = 1
	TriggerPeriodic = "PERIODIC"
)

// Request is the body of POST /v1/client/heartbeat.
type Request struct {
	SchemaVersion int          `json:"schemaVersion"`
	Trigger       string       `json:"trigger"`
	SDK           SDK          `json:"sdk"`
	SentAt        int64        `json:"sentAt"`
	Secrets       []HeldSecret `json:"secrets"`
}

// SDK identifies the reporting SDK.
type SDK struct {
	Language string `json:"language"`
	Version  string `json:"version"`
}

// HeldSecret is one usage-ledger entry.
type HeldSecret struct {
	ProjectID          string  `json:"projectId"`
	SecretName         string  `json:"secretName"`
	Version            int     `json:"version"`
	RefreshHook        bool    `json:"refreshHook"`
	GetCount           int64   `json:"getCount"`
	ErrorCount         int64   `json:"errorCount"`
	FirstAccessAt      int64   `json:"firstAccessAt"`
	LastAccessAt       int64   `json:"lastAccessAt"`
	HookAppliedVersion int     `json:"hookAppliedVersion"`
	HookFailureCount   int64   `json:"hookFailureCount"`
	LastHookError      *string `json:"lastHookError"`
}
