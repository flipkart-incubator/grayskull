package metrics

import (
	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promauto"
	"strconv"
)

// Recorder defines the interface for recording metrics
type Recorder interface {
	// RecordRequest records an HTTP request with its duration
	RecordRequest(name string, statusCode int, durationMs int64)
	// RecordRetry records a retry attempt
	RecordRetry(url string, attemptNumber int, success bool)
}

// PrometheusRecorder implements Recorder using Prometheus
type PrometheusRecorder struct {
	// Request duration histogram with standard buckets
	requestDuration *prometheus.HistogramVec
	// Request counter
	requestCount *prometheus.CounterVec
	// Retry counter
	retryCount *prometheus.CounterVec
	// Error counter
	errorCount *prometheus.CounterVec
}

// NewPrometheusRecorder creates a new PrometheusRecorder with metrics matching Java SDK
type PrometheusRecorderConfig struct {
	Namespace string
	Subsystem string
	Labels    map[string]string
}

// NewPrometheusRecorder creates a new PrometheusRecorder with metrics matching Java SDK
func NewPrometheusRecorder(cfg PrometheusRecorderConfig) *PrometheusRecorder {
	if cfg.Namespace == "" {
		cfg.Namespace = "grayskull"
	}
	if cfg.Subsystem == "" {
		cfg.Subsystem = "client"
	}

	constLabels := prometheus.Labels{}
	for k, v := range cfg.Labels {
		constLabels[k] = v
	}

	return &PrometheusRecorder{
		requestDuration: promauto.NewHistogramVec(
			prometheus.HistogramOpts{
				Namespace:   cfg.Namespace,
				Subsystem:   cfg.Subsystem,
				Name:        "request_duration_milliseconds",
				Help:        "Duration of HTTP requests in milliseconds",
				Buckets:     prometheus.ExponentialBuckets(10, 2, 12), // 10ms to ~40s
				ConstLabels: constLabels,
			},
			[]string{"path", "status"}, // Match Java SDK labels
		),
		requestCount: promauto.NewCounterVec(
			prometheus.CounterOpts{
				Namespace:   cfg.Namespace,
				Subsystem:   cfg.Subsystem,
				Name:        "requests_total",
				Help:        "Total number of HTTP requests",
				ConstLabels: constLabels,
			},
			[]string{"path", "status"}, // Match Java SDK labels
		),
		retryCount: promauto.NewCounterVec(
			prometheus.CounterOpts{
				Namespace:   cfg.Namespace,
				Subsystem:   cfg.Subsystem,
				Name:        "retries_total",
				Help:        "Total number of HTTP retries",
				ConstLabels: constLabels,
			},
			[]string{"path", "attempt", "success"}, // Match Java SDK labels
		),
		errorCount: promauto.NewCounterVec(
			prometheus.CounterOpts{
				Namespace:   cfg.Namespace,
				Subsystem:   cfg.Subsystem,
				Name:        "errors_total",
				Help:        "Total number of HTTP errors",
				ConstLabels: constLabels,
			},
			[]string{"path", "status", "error_type"}, // Match Java SDK labels
		),
	}
}

func (p *PrometheusRecorder) RecordRequest(path string, statusCode int, durationMs int64) {
	status := strconv.Itoa(statusCode)

	// Record duration and count
	p.requestDuration.WithLabelValues(path, status).Observe(float64(durationMs))
	p.requestCount.WithLabelValues(path, status).Inc()

	// Record error if status code is 4xx or 5xx
	if statusCode >= 400 {
		errorType := "client_error"
		if statusCode >= 500 {
			errorType = "server_error"
		}
		p.errorCount.WithLabelValues(path, status, errorType).Inc()
	}
}

func (p *PrometheusRecorder) RecordRetry(path string, attemptNumber int, success bool) {
	attempt := strconv.Itoa(attemptNumber)
	successStr := strconv.FormatBool(success)
	p.retryCount.WithLabelValues(path, attempt, successStr).Inc()

	// If retry failed, also record an error
	if !success {
		p.errorCount.WithLabelValues(path, "retry_failed", "retry_error").Inc()
	}
}

// DefaultRecorder is the default metrics recorder with standard configuration
var DefaultRecorder Recorder = NewPrometheusRecorder(PrometheusRecorderConfig{
	Namespace: "grayskull",
	Subsystem: "client",
	Labels: map[string]string{
		"client_type": "go",
		"version":     "1.0.0", // TODO: Replace with actual version
	},
})
