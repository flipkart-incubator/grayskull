package constants

import "time"

// contextKey is a private type used for context keys to prevent collisions between packages
type contextKey string

// These keys are automatically added to the logging context for correlation and tracing.
// Applications can reference these keys in their logging patterns.
const (
	// GrayskullRequestID is the unique request identifier for correlating logs across the request lifecycle.
	GrayskullRequestID contextKey = "grayskullRequestId"

	// HTTP header names used by the Grayskull client on the wire.
	HeaderAuthorization = "Authorization"
	HeaderRequestID     = "X-Request-Id"
	HeaderWorkload      = "Grayskull-Workload"
	HeaderUserAgent     = "User-Agent"
)

// Poller / batch-refresh tunables
const (
	// MaxBatchSize is the largest number of secrets sent in one batch-refresh request.
	MaxBatchSize = 50

	// DefaultPollIntervalSeconds is the default fixed delay between consecutive poll cycles.
	DefaultPollIntervalSeconds = 60

	// DispatcherWorkers is the number of goroutines that drain per-secret hook updates.
	DispatcherWorkers = 5

	// ShutdownAwait is the time the poller waits for in-flight work on Close().
	ShutdownAwait = 10 * time.Second
)
