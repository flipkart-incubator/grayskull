// Package workload defines the contract for resolving the caller's workload identity,
// used to populate the Grayskull-Workload header on every request.
package workload

// WorkloadIdentityResolver resolves the workload identity advertised via the
// Grayskull-Workload header. Implementations are typically invoked once at
// client construction; resolution is therefore expected to be cheap.
//
// This mirrors com.flipkart.grayskull.workload.WorkloadIdentityResolver from
// the Java SDK.
type WorkloadIdentityResolver interface {
	// Resolve returns the workload identity string (for example, the local hostname).
	// Implementations must never return an empty string; return a sentinel like
	// "UNKNOWN" if the underlying source is unavailable.
	Resolve() string
}
