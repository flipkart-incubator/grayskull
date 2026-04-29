package models

import (
	"sync"

	apiworkload "github.com/flipkart-incubator/grayskull/clients/go/client-api/workload"
)

// GrayskullClientConfiguration holds all the necessary configuration parameters
// required to connect to and interact with the Grayskull service.
//
// This struct mirrors com.flipkart.grayskull.models.GrayskullClientConfiguration
// from the Java SDK (with minor Go-idiomatic naming and additional knobs that
// only make sense in Go's net/http transport).
type GrayskullClientConfiguration struct {
	// Host is the Grayskull server endpoint URL.
	// This should be the DNS of the Grayskull server (e.g., "https://grayskull.example.com").
	// The client will append appropriate API paths to this host.
	Host string `json:"host" yaml:"host" validate:"required,url"`

	// ConnectionTimeout is the maximum time in milliseconds the client will wait when
	// establishing a connection to the Grayskull server. If the connection cannot be
	// established within this time, a timeout error will be returned.
	// Default: 10000ms (10 seconds)
	ConnectionTimeout int `json:"connectionTimeout" yaml:"connectionTimeout" validate:"gte=0"`

	// ReadTimeout is the maximum time in milliseconds the client will wait for data
	// to be received from the Grayskull server after a connection has been established.
	// If no data is received within this time, a timeout error will be returned.
	// Default: 30000ms (30 seconds)
	ReadTimeout int `json:"readTimeout" yaml:"readTimeout" validate:"gte=0"`

	// MaxConnections controls the connection pool size for the HTTP client.
	// More connections allow for more concurrent requests but consume more resources.
	// Default: 10
	MaxConnections int `json:"maxConnections" yaml:"maxConnections" validate:"gt=0"`

	// IdleConnTimeout is the maximum time in milliseconds that an idle connection
	// will remain idle before closing itself. Zero means no timeout.
	// Default: 300000ms (5 minutes)
	IdleConnTimeout int `json:"idleConnTimeout" yaml:"idleConnTimeout" validate:"gte=0"`

	// MaxIdleConns controls the maximum number of idle (keep-alive) connections
	// across all hosts. Zero means no limit.
	// Default: same as MaxConnections
	MaxIdleConns int `json:"maxIdleConns" yaml:"maxIdleConns" validate:"gte=0"`

	// MaxIdleConnsPerHost controls the maximum idle (keep-alive) connections
	// to keep per-host. Zero means use DefaultMaxIdleConnsPerHost (2).
	// Default: same as MaxConnections
	MaxIdleConnsPerHost int `json:"maxIdleConnsPerHost" yaml:"maxIdleConnsPerHost" validate:"gte=0"`

	// MaxRetries is the maximum number of retry attempts for failed requests.
	// When a request to the Grayskull server fails due to transient errors
	// (e.g., network issues, server temporarily unavailable), the client will
	// retry the request up to this many times before giving up.
	// Default: 3
	MaxRetries int `json:"maxRetries" yaml:"maxRetries" validate:"gte=0"`

	// MinRetryDelay is the minimum delay in milliseconds between retry attempts.
	// When a request fails and needs to be retried, the client will wait at least
	// this amount of time before attempting the next retry. This helps prevent
	// overwhelming the server with rapid retry attempts.
	// Default: 100ms
	MinRetryDelay int `json:"minRetryDelay" yaml:"minRetryDelay" validate:"gte=0"`

	// MetricsEnabled controls whether metrics collection is enabled.
	// When enabled, the client will expose metrics about API calls (success/failure counts,
	// response times, etc.) for monitoring and observability.
	// Default: true
	MetricsEnabled bool `json:"metricsEnabled" yaml:"metricsEnabled"`

	// PollingIntervalSeconds is the interval, in seconds, between background
	// batch refreshes for registered refresh hooks. The next poll starts that
	// many seconds after the previous poll completes (fixed-delay semantics,
	// matching the Java SDK).
	// Default: 60 (one minute). A value of 0 is treated as "use the default";
	// negative values fail validation.
	PollingIntervalSeconds int `json:"pollingIntervalSeconds" yaml:"pollingIntervalSeconds" validate:"gte=0"`

	// WorkloadIdentityResolver resolves the value advertised in the
	// Grayskull-Workload header. If left nil, the SDK uses its default
	// hostname-based resolver. Mirrors the Java SDK's pluggable resolver.
	WorkloadIdentityResolver apiworkload.WorkloadIdentityResolver `json:"-" yaml:"-"`

	// defaultHeaders are wire-level headers added to every outbound request.
	// The map is populated by the SDK during client construction (workload
	// identity, user agent, etc.). It is exposed via accessor methods that take
	// a copy so concurrent reads at request-build time and writes at startup
	// remain race-free.
	defaultHeaders   map[string]string
	defaultHeadersMu sync.RWMutex
}

// NewDefaultConfig returns a new GrayskullClientConfiguration with default values
// matching the Java SDK's GrayskullClientConfiguration defaults.
func NewDefaultConfig() *GrayskullClientConfiguration {
	return &GrayskullClientConfiguration{
		Host:                   "",
		ConnectionTimeout:      10000,  // 10 seconds
		ReadTimeout:            30000,  // 30 seconds
		MaxConnections:         10,
		IdleConnTimeout:        300000, // 5 minutes
		MaxIdleConns:           10,
		MaxIdleConnsPerHost:    10,
		MaxRetries:             3,
		MinRetryDelay:          100,
		MetricsEnabled:         true,
		PollingIntervalSeconds: 60,
	}
}

// AddDefaultHeader registers a header that will be attached to every outbound
// request from this client. Both name and value must be non-empty; the call is
// silently ignored otherwise (matching Java's behavior).
//
// The SDK uses this internally to set Grayskull-Workload and User-Agent during
// client construction; applications should not need to call it directly.
func (c *GrayskullClientConfiguration) AddDefaultHeader(name, value string) {
	if name == "" || value == "" {
		return
	}
	c.defaultHeadersMu.Lock()
	defer c.defaultHeadersMu.Unlock()
	if c.defaultHeaders == nil {
		c.defaultHeaders = make(map[string]string)
	}
	c.defaultHeaders[name] = value
}

// DefaultHeaders returns a defensive copy of the default headers map. It is
// safe to mutate the returned map; doing so does not affect the configuration.
func (c *GrayskullClientConfiguration) DefaultHeaders() map[string]string {
	c.defaultHeadersMu.RLock()
	defer c.defaultHeadersMu.RUnlock()
	out := make(map[string]string, len(c.defaultHeaders))
	for k, v := range c.defaultHeaders {
		out[k] = v
	}
	return out
}
