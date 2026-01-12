package models

import (
	"errors"
	"fmt"
	"strings"
	"time"
)

// GrayskullClientConfiguration holds all configuration parameters required to connect
// to and interact with the Grayskull service.
// MetricsConfig holds configuration for metrics collection and exposure
type MetricsConfig struct {
	// Enabled controls whether metrics collection is enabled.
	// Default: true
	Enabled bool

	// Server contains configuration for the metrics HTTP server.
	// If nil, no HTTP server will be started.
	Server *MetricsServerConfig
}

// MetricsServerConfig holds configuration for the metrics HTTP server
type MetricsServerConfig struct {
	// Address is the address where the metrics server will listen.
	// Default: ":9090"
	Address string
}

type GrayskullClientConfiguration struct {
	// Host is the Grayskull server endpoint URL (e.g., "https://grayskull.example.com").
	// The client will append appropriate API paths to this host.
	Host string

	// ConnectionTimeout is the maximum duration for establishing a connection to the Grayskull server.
	// Default: 10 seconds
	ConnectionTimeout time.Duration

	// ReadTimeout is the maximum duration for reading the entire response.
	// Default: 30 seconds
	ReadTimeout time.Duration

	// MaxConnections controls the maximum number of concurrent connections.
	// Default: 10
	MaxConnections int

	// MaxRetries is the maximum number of retry attempts for failed requests.
	// Must be between 1 and 10.
	// Default: 3
	MaxRetries int

	// MinRetryDelay is the minimum delay between retry attempts.
	// Must be at least 50ms.
	// Default: 100ms
	MinRetryDelay time.Duration

	// Metrics configuration for the client
	Metrics MetricsConfig
}

// NewGrayskullClientConfiguration creates a new configuration with default values.
func NewGrayskullClientConfiguration() *GrayskullClientConfiguration {
	return &GrayskullClientConfiguration{
		ConnectionTimeout: 10 * time.Second,
		ReadTimeout:       30 * time.Second,
		MaxConnections:    10,
		MaxRetries:        3,
		MinRetryDelay:     100 * time.Millisecond,
		Metrics: MetricsConfig{
			Enabled: true,
			Server:  nil, // No metrics server by default
		},
	}
}

// SetHost sets the Grayskull server endpoint URL.
// Returns an error if host is empty.
func (c *GrayskullClientConfiguration) SetHost(host string) error {
	if strings.TrimSpace(host) == "" {
		return errors.New("host cannot be empty")
	}
	// Remove trailing slash if present
	c.Host = strings.TrimSuffix(host, "/")
	return nil
}

// SetConnectionTimeout sets the connection timeout.
// Returns an error if the timeout is not positive.
func (c *GrayskullClientConfiguration) SetConnectionTimeout(timeout time.Duration) error {
	if timeout <= 0 {
		return errors.New("connection timeout must be positive")
	}
	c.ConnectionTimeout = timeout
	return nil
}

// SetReadTimeout sets the read timeout.
// Returns an error if the timeout is not positive.
func (c *GrayskullClientConfiguration) SetReadTimeout(timeout time.Duration) error {
	if timeout <= 0 {
		return errors.New("read timeout must be positive")
	}
	c.ReadTimeout = timeout
	return nil
}

// SetMaxConnections sets the maximum number of concurrent connections.
// Returns an error if maxConnections is not positive.
func (c *GrayskullClientConfiguration) SetMaxConnections(maxConnections int) error {
	if maxConnections <= 0 {
		return errors.New("max connections must be positive")
	}
	c.MaxConnections = maxConnections
	return nil
}

// SetMaxRetries sets the maximum number of retry attempts.
// Returns an error if maxRetries is not between 1 and 10.
func (c *GrayskullClientConfiguration) SetMaxRetries(maxRetries int) error {
	if maxRetries < 1 || maxRetries > 10 {
		return fmt.Errorf("max retries must be between 1 and 10, got %d", maxRetries)
	}
	c.MaxRetries = maxRetries
	return nil
}

// SetMinRetryDelay sets the minimum delay between retry attempts.
// Returns an error if the delay is less than 50ms.
func (c *GrayskullClientConfiguration) SetMinRetryDelay(delay time.Duration) error {
	if delay < 50*time.Millisecond {
		return errors.New("min retry delay must be at least 50ms")
	}
	c.MinRetryDelay = delay
	return nil
}

// SetMetricsEnabled enables or disables metrics collection.
func (c *GrayskullClientConfiguration) SetMetricsEnabled(enabled bool) {
	c.Metrics.Enabled = enabled
}

// EnableMetricsServer enables the metrics HTTP server with the specified address.
// If address is empty, it defaults to ":9090".
func (c *GrayskullClientConfiguration) EnableMetricsServer(address string) {
	if address == "" {
		address = ":9090"
	}
	c.Metrics.Server = &MetricsServerConfig{
		Address: address,
	}
}

// DisableMetricsServer disables the metrics HTTP server.
func (c *GrayskullClientConfiguration) DisableMetricsServer() {
	c.Metrics.Server = nil
}

// IsMetricsServerEnabled returns true if the metrics server is enabled.
func (c *GrayskullClientConfiguration) IsMetricsServerEnabled() bool {
	return c.Metrics.Server != nil
}

// GetMetricsServerAddress returns the metrics server address if enabled, or an empty string.
func (c *GrayskullClientConfiguration) GetMetricsServerAddress() string {
	if c.Metrics.Server == nil {
		return ""
	}
	return c.Metrics.Server.Address
}
