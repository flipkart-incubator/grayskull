package workload

// WorkloadIdentityResolver resolves workload identity for the
// Grayskull-Workload header (usually once at startup).
type WorkloadIdentityResolver interface {
	Resolve() string
}
