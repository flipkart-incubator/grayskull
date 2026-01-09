package constants

// MDCKeys contains constants for MDC (Mapped Diagnostic Context) keys used throughout the Grayskull client.
//
// These keys are automatically added to the logging context for correlation and tracing.
// Applications can reference these keys in their logging patterns.
const (
	// GrayskullRequestID is the unique request identifier for correlating logs across the request lifecycle.
	// Also sent as the X-Request-Id header to enable end-to-end tracing.
	GrayskullRequestID = "grayskullRequestId"

	// ProjectID represents the Grayskull project ID being accessed.
	ProjectID = "projectId"

	// SecretName represents the name of the secret being accessed.
	SecretName = "secretName"
)
