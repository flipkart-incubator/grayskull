package metrics

import "time"

type MetricsRecorder interface {
	// RecordRequest records an HTTP request with its duration
	RecordRequest(name string, statusCode int, duration time.Duration)

	// RecordRetry records a retry attempt for a request
	RecordRetry(url string, attemptNumber int, success bool)

	// GetRecorderName returns the name of this metrics recorder implementation
	GetRecorderName() string
}
