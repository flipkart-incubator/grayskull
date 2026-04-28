// Package constants holds wire-level constants that are part of the public Grayskull SDK contract.
package constants

// HTTP header names used by the Grayskull client on the wire.
//
// These constants mirror com.flipkart.grayskull.constants.GrayskullHeaders in the Java SDK
// and must remain byte-identical so a single Grayskull server can serve both clients.
const (
	// WorkloadHeader carries the workload identity (resolved at client construction).
	// Use for caller identity; not UserAgentHeader.
	WorkloadHeader = "Grayskull-Workload"

	// UserAgentHeader carries the SDK product/version (e.g. "grayskull-go/0.2.0").
	// Telemetry only; not for identity.
	UserAgentHeader = "User-Agent"

	// AuthorizationHeader is the standard Authorization header.
	AuthorizationHeader = "Authorization"

	// RequestIDHeader is the per-request correlation header.
	RequestIDHeader = "X-Request-Id"
)
