package workload

import (
	"testing"
)

// TestNewDefaultWorkloadIdentityResolver_ResolvesHostname verifies that the
// default resolver successfully resolves and caches the hostname.
func TestNewDefaultWorkloadIdentityResolver_ResolvesHostname(t *testing.T) {
	resolver := NewDefaultWorkloadIdentityResolver()

	identity := resolver.Resolve()

	if identity == "" {
		t.Error("Resolve() returned empty string, expected non-empty hostname or UNKNOWN")
	}

	// Verify it's cached (second call returns same value)
	identity2 := resolver.Resolve()
	if identity != identity2 {
		t.Errorf("Resolve() returned different values: %q vs %q, expected cached value", identity, identity2)
	}
}

// TestResolve_ReturnsNonEmpty verifies that Resolve never returns empty string.
func TestResolve_ReturnsNonEmpty(t *testing.T) {
	resolver := NewDefaultWorkloadIdentityResolver()

	identity := resolver.Resolve()

	if identity == "" {
		t.Error("Resolve() should never return empty string")
	}
}

// TestResolve_ConsistentCalls verifies that multiple Resolve calls return the same value.
func TestResolve_ConsistentCalls(t *testing.T) {
	resolver := NewDefaultWorkloadIdentityResolver()

	identity1 := resolver.Resolve()
	identity2 := resolver.Resolve()
	identity3 := resolver.Resolve()

	if identity1 != identity2 || identity2 != identity3 {
		t.Errorf("Resolve() returned inconsistent values: %q, %q, %q", identity1, identity2, identity3)
	}
}

// TestResolveHostname_NeverReturnsEmpty verifies internal hostname resolution.
func TestResolveHostname_NeverReturnsEmpty(t *testing.T) {
	hostname := resolveHostname()

	if hostname == "" {
		t.Error("resolveHostname() returned empty string, expected hostname or UNKNOWN")
	}
}

// TestDefaultWorkloadIdentityResolver_ImplementsInterface verifies the resolver
// implements the WorkloadIdentityResolver interface.
func TestDefaultWorkloadIdentityResolver_ImplementsInterface(t *testing.T) {
	var _ WorkloadIdentityResolver = (*DefaultWorkloadIdentityResolver)(nil)
}
