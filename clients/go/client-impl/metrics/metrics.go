package metrics

import (
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

// NewPrometheusRecorder creates a new Prometheus-based metrics recorder
func NewPrometheusRecorder() MetricsRecorder {
	return &prometheusRecorder{
		requestDuration: promauto.NewHistogramVec(
			prometheus.HistogramOpts{
				Namespace: namespace,
				Subsystem: subsystem,
				Name:      "request_duration_seconds",
				Help:      "Duration of HTTP requests in seconds",
				Buckets:   prometheus.DefBuckets,
			},
			[]string{"name", "status_code"},
		),
		retryCounter: promauto.NewCounterVec(
			prometheus.CounterOpts{
				Namespace: namespace,
				Subsystem: subsystem,
				Name:      "retry_attempts_total",
				Help:      "Total number of retry attempts",
			},
			[]string{"url", "success"},
		),
	}
}

// RecordRequest records an HTTP request with its duration
func (p *prometheusRecorder) RecordRequest(name string, statusCode int, duration time.Duration) {
	p.requestDuration.WithLabelValues(name, string(rune(statusCode))).Observe(duration.Seconds())
}

// RecordRetry records a retry attempt for a request
func (p *prometheusRecorder) RecordRetry(url string, attemptNumber int, success bool) {
	successStr := "false"
	if success {
		successStr = "true"
	}
	p.retryCounter.WithLabelValues(url, successStr).Inc()
}

// GetRecorderName returns the name of this metrics recorder implementation
func (p *prometheusRecorder) GetRecorderName() string {
	return "prometheus"
}

// init registers the metrics with Prometheus
func init() {
	// This ensures the metrics are registered when the package is imported
	_ = NewPrometheusRecorder()
}
