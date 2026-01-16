package metrics

import (
	"sync"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promauto"
)

var (
	once sync.Once
	reg  *prometheus.Registry
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
	once.Do(func() {
		reg = prometheus.NewRegistry()
		prometheus.DefaultRegisterer = reg
		prometheus.DefaultGatherer = reg
	})

	// Create a new registry for metrics
	registry := prometheus.NewRegistry()

	// Register the collector with our registry
	collector := prometheus.NewGoCollector()
	err := registry.Register(collector)
	if err != nil {
		// If registration fails, we'll still return a recorder but it won't collect Go metrics
		// This is better than panicking
	}

	// Create metrics with the registry
	reqDuration := promauto.With(registry).NewHistogramVec(
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
		requestDuration: reqDuration,
		retryCounter:    retryCounter,
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

// init is intentionally left empty to prevent auto-registration
func init() {}
