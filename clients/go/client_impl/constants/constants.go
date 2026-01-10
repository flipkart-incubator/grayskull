package constants

// contextKey is a private type used for context keys to prevent collisions between packages
type contextKey string

// These keys are automatically added to the logging context for correlation and tracing.
// Applications can reference these keys in their logging patterns.
const (
	// GrayskullRequestID is the unique request identifier for correlating logs across the request lifecycle.
	// Also sent as the X-Request-Id header to enable end-to-end tracing.
	GrayskullRequestID contextKey = "grayskullRequestId"

	// ProjectID represents the Grayskull project ID being accessed.
	ProjectID contextKey = "projectId"

	// SecretName represents the name of the secret being accessed.
	SecretName contextKey = "secretName"
)
