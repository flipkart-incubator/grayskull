package metrics

import (
	"log/slog"
	"sync"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promauto"
)

var (
	once            sync.Once
	reg             *prometheus.Registry
	requestDuration *prometheus.HistogramVec
	retryCounter    *prometheus.CounterVec
)

const (
	namespace = "grayskull"
	subsystem = "http_client"
)

type prometheusRecorder struct {
	requestDuration *prometheus.HistogramVec
	retryCounter    *prometheus.CounterVec
}

// initFunc is the initialization function that can be overridden for testing
var initFunc = initializeMetrics

// initializeMetrics initializes the Prometheus registry and metrics
func initializeMetrics() {
	// Create registry if not already set (allows testing with pre-configured registry)
	if reg == nil {
		reg = prometheus.NewRegistry()
	}
	prometheus.DefaultRegisterer = reg
	prometheus.DefaultGatherer = reg

	// Register Go collector once with the package-level registry
	if err := reg.Register(prometheus.NewGoCollector()); err != nil {
		slog.Warn("Failed to register Prometheus Go collector", "error", err)
	}

	// Create metrics once with the package-level registry
	requestDuration = promauto.With(reg).NewHistogramVec(
		prometheus.HistogramOpts{
			Namespace: namespace,
			Subsystem: subsystem,
			Name:      "request_duration_seconds",
			Help:      "Duration of HTTP requests in seconds",
			Buckets:   prometheus.DefBuckets,
		},
		[]string{"name", "status_code"},
	)

	retryCounter = promauto.With(reg).NewCounterVec(
		prometheus.CounterOpts{
			Namespace: namespace,
			Subsystem: subsystem,
			Name:      "retry_attempts_total",
			Help:      "Total number of retry attempts",
		},
		[]string{"url", "success"},
	)
}

// NewPrometheusRecorder creates a new Prometheus-based metrics recorder
func NewPrometheusRecorder() MetricsRecorder {
	once.Do(initFunc)

	return &prometheusRecorder{
		requestDuration: requestDuration,
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
