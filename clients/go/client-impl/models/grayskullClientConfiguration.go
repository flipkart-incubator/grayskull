package models

// GrayskullClientConfiguration holds all the necessary configuration parameters
// required to connect to and interact with the Grayskull service.
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
}

// NewDefaultConfig returns a new GrayskullClientConfiguration with default values.
func NewDefaultConfig() *GrayskullClientConfiguration {
	return &GrayskullClientConfiguration{
		Host:                "",
		ConnectionTimeout:   10000, // 10 seconds
		ReadTimeout:         30000, // 30 seconds
		MaxConnections:      10,
		IdleConnTimeout:     300000, // 5 minutes
		MaxIdleConns:        10,     // same as MaxConnections
		MaxIdleConnsPerHost: 10,     // same as MaxConnections
		MaxRetries:          3,
		MinRetryDelay:       100, // 100ms
		MetricsEnabled:      true,
	}
}
