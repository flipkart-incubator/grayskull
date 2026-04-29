// Package workload provides the default WorkloadIdentityResolver implementation
// used by the Grayskull Go SDK. The resolver mirrors
// com.flipkart.grayskull.workload.DefaultWorkloadIdentityResolver from the Java SDK.
package workload

import (
	"log/slog"
	"os"
	"strings"

	apiworkload "github.com/flipkart-incubator/grayskull/clients/go/client-api/workload"
)

// UnknownHost is the fallback identity returned when the local hostname cannot be resolved.
const UnknownHost = "UNKNOWN"

// DefaultWorkloadIdentityResolver resolves the local hostname exactly once at
// construction time and caches the result. This matches the Java default
// resolver: cheap, deterministic, and called only on each call to Resolve.
type DefaultWorkloadIdentityResolver struct {
	resolved string
}

// Compile-time interface satisfaction check.
var _ apiworkload.WorkloadIdentityResolver = (*DefaultWorkloadIdentityResolver)(nil)

// hostnameFn is a package-private seam to support deterministic tests; it
// defaults to os.Hostname.
var hostnameFn = os.Hostname

// NewDefaultWorkloadIdentityResolver constructs a resolver and resolves the
// hostname immediately. If the hostname cannot be determined or is empty,
// UnknownHost is used and the failure is logged at warn level.
func NewDefaultWorkloadIdentityResolver() *DefaultWorkloadIdentityResolver {
	hostname := UnknownHost
	if h, err := hostnameFn(); err != nil {
		slog.Warn("Could not resolve local hostname for telemetry; using fallback.",
			"fallback", UnknownHost, "error", err)
	} else if trimmed := strings.TrimSpace(h); trimmed != "" {
		hostname = trimmed
	}
	return &DefaultWorkloadIdentityResolver{resolved: hostname}
}

// Resolve returns the cached workload identity (the hostname captured at
// construction, or UnknownHost on failure).
func (r *DefaultWorkloadIdentityResolver) Resolve() string {
	return r.resolved
}
