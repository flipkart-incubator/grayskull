package workload

import (
	"log/slog"
	"os"
)

// WorkloadIdentityResolver resolves workload identity for the Grayskull-Workload
// header, usually called once at client startup.
type WorkloadIdentityResolver interface {
	// Resolve returns the workload identity string (e.g., hostname, pod name, etc.)
	Resolve() string
}

// DefaultWorkloadIdentityResolver is the default implementation that returns
// the local hostname. The identity is resolved once in the constructor.
type DefaultWorkloadIdentityResolver struct {
	resolvedIdentity string
}

const unknownHost = "UNKNOWN"

// NewDefaultWorkloadIdentityResolver creates a new resolver that uses the local hostname.
// The hostname is resolved immediately and cached for subsequent Resolve() calls.
func NewDefaultWorkloadIdentityResolver() *DefaultWorkloadIdentityResolver {
	hostname := resolveHostname()
	return &DefaultWorkloadIdentityResolver{
		resolvedIdentity: hostname,
	}
}

// Resolve returns the cached workload identity (hostname).
func (r *DefaultWorkloadIdentityResolver) Resolve() string {
	return r.resolvedIdentity
}

// resolveHostname attempts to get the local hostname, falling back to "UNKNOWN" on error.
func resolveHostname() string {
	hostname, err := os.Hostname()
	if err != nil {
		slog.Warn("Could not resolve local hostname for telemetry", "error", err)
		return unknownHost
	}
	if hostname == "" {
		slog.Warn("Resolved hostname is empty, using default")
		return unknownHost
	}
	return hostname
}
