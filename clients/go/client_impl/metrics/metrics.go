package metrics

import (
	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promauto"
)

// Metrics holds all the metrics for the application
type Metrics struct {
	// HTTP request metrics
	HTTPRequestDuration *prometheus.HistogramVec
	HTTPRequestTotal    *prometheus.CounterVec

	// Secret operation metrics
	SecretOperationDuration *prometheus.HistogramVec
	SecretOperationTotal    *prometheus.CounterVec
}

var (
	singleton *Metrics
)

// NewMetrics creates a new instance of Metrics with all the required metrics
func NewMetrics(namespace string) *Metrics {
	if singleton != nil {
		return singleton
	}

	m := &Metrics{
		HTTPRequestDuration: promauto.NewHistogramVec(
			prometheus.HistogramOpts{
				Namespace: namespace,
				Name:      "http_request_duration_seconds",
				Help:      "Duration of HTTP requests in seconds",
				Buckets:   []float64{0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10},
			},
			[]string{"method", "path", "status_code"},
		),
		HTTPRequestTotal: promauto.NewCounterVec(
			prometheus.CounterOpts{
				Namespace: namespace,
				Name:      "http_requests_total",
				Help:      "Total number of HTTP requests",
			},
			[]string{"method", "path", "status_code"},
		),
		SecretOperationDuration: promauto.NewHistogramVec(
			prometheus.HistogramOpts{
				Namespace: namespace,
				Name:      "secret_operation_duration_seconds",
				Help:      "Duration of secret operations in seconds",
				Buckets:   []float64{0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5},
			},
			[]string{"operation", "status"},
		),
		SecretOperationTotal: promauto.NewCounterVec(
			prometheus.CounterOpts{
				Namespace: namespace,
				Name:      "secret_operations_total",
				Help:      "Total number of secret operations",
			},
			[]string{"operation", "status"},
		),
	}

	singleton = m
	return m
}

// GetMetrics returns the singleton instance of Metrics
func GetMetrics() *Metrics {
	if singleton == nil {
		// Default namespace if not initialized
		return NewMetrics("grayskull_client")
	}
	return singleton
}

// ObserveHTTPRequestDuration records the duration of an HTTP request
func (m *Metrics) ObserveHTTPRequestDuration(method, path, statusCode string, duration float64) {
	m.HTTPRequestDuration.WithLabelValues(method, path, statusCode).Observe(duration)
}

// IncHTTPRequestTotal increments the total HTTP requests counter
func (m *Metrics) IncHTTPRequestTotal(method, path, statusCode string) {
	m.HTTPRequestTotal.WithLabelValues(method, path, statusCode).Inc()
}

// ObserveSecretOperationDuration records the duration of a secret operation
func (m *Metrics) ObserveSecretOperationDuration(operation, status string, duration float64) {
	m.SecretOperationDuration.WithLabelValues(operation, status).Observe(duration)
}

// IncSecretOperationTotal increments the total secret operations counter
func (m *Metrics) IncSecretOperationTotal(operation, status string) {
	m.SecretOperationTotal.WithLabelValues(operation, status).Inc()
}
