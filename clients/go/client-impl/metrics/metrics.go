package metrics

import (
	"strconv"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promauto"
)

const (
	namespace = "grayskull"
	subsystem = "http_client"
)

type prometheusRecorder struct {
	requestDuration *prometheus.HistogramVec
	retryCounter    *prometheus.CounterVec
}

// NewPrometheusRecorder creates a new Prometheus-based metrics recorder with the given registry.
// If registry is nil, prometheus.DefaultRegisterer will be used.
func NewPrometheusRecorder(registry prometheus.Registerer) MetricsRecorder {
	if registry == nil {
		registry = prometheus.DefaultRegisterer
	}

	requestDuration := promauto.With(registry).NewHistogramVec(
		prometheus.HistogramOpts{
			Namespace: namespace,
			Subsystem: subsystem,
			Name:      "request_duration_seconds",
			Help:      "Duration of HTTP requests in seconds",
			Buckets:   prometheus.DefBuckets,
		},
		[]string{"name", "status_code"},
	)

	retryCounter := promauto.With(registry).NewCounterVec(
		prometheus.CounterOpts{
			Namespace: namespace,
			Subsystem: subsystem,
			Name:      "retry_attempts_total",
			Help:      "Total number of retry attempts",
		},
		[]string{"url", "success"},
	)

	return &prometheusRecorder{
		requestDuration: requestDuration,
		retryCounter:    retryCounter,
	}
}

// RecordRequest records an HTTP request with its duration
func (p *prometheusRecorder) RecordRequest(name string, statusCode int, duration time.Duration) {
	p.requestDuration.WithLabelValues(name, strconv.Itoa(statusCode)).Observe(duration.Seconds())
}

// RecordRetry records a retry attempt for a request
func (p *prometheusRecorder) RecordRetry(url string, attemptNumber int, success bool) {
	successStr := "false"
	if success {
		successStr = "true"
	}
	p.retryCounter.WithLabelValues(url, successStr).Inc()
}
