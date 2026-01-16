package models

import (
	"errors"
	"fmt"
	"strings"
)

// GrayskullClientConfiguration holds all the necessary configuration parameters
// required to connect to and interact with the Grayskull service.
type GrayskullClientConfiguration struct {
	// Host is the Grayskull server endpoint URL.
	// This should be the DNS of the Grayskull server (e.g., "https://grayskull.example.com").
	// The client will append appropriate API paths to this host.
	Host string `json:"host"`

	// ConnectionTimeout is the maximum time in milliseconds the client will wait when
	// establishing a connection to the Grayskull server. If the connection cannot be
	// established within this time, a timeout error will be returned.
	// Default: 10000ms (10 seconds)
	ConnectionTimeout int `json:"connectionTimeout"`

	// ReadTimeout is the maximum time in milliseconds the client will wait for data
	// to be received from the Grayskull server after a connection has been established.
	// If no data is received within this time, a timeout error will be returned.
	// Default: 30000ms (30 seconds)
	ReadTimeout int `json:"readTimeout"`

	// MaxConnections controls the connection pool size for the HTTP client.
	// More connections allow for more concurrent requests but consume more resources.
	// Default: 10
	MaxConnections int `json:"maxConnections"`

	// MaxRetries is the maximum number of retry attempts for failed requests.
	// When a request to the Grayskull server fails due to transient errors
	// (e.g., network issues, server temporarily unavailable), the client will
	// retry the request up to this many times before giving up.
	// Default: 3
	MaxRetries int `json:"maxRetries"`

	// MinRetryDelay is the minimum delay in milliseconds between retry attempts.
	// When a request fails and needs to be retried, the client will wait at least
	// this amount of time before attempting the next retry. This helps prevent
	// overwhelming the server with rapid retry attempts.
	// Default: 100ms
	MinRetryDelay int `json:"minRetryDelay"`

	// MetricsEnabled controls whether metrics collection is enabled.
	// When enabled, the client will expose metrics about API calls (success/failure counts,
	// response times, etc.) for monitoring and observability.
	// Default: true
	MetricsEnabled bool `json:"metricsEnabled"`
}

// NewDefaultConfig returns a new GrayskullClientConfiguration with default values.
func NewDefaultConfig() *GrayskullClientConfiguration {
	return &GrayskullClientConfiguration{
		Host:              "",
		ConnectionTimeout: 10000, // 10 seconds
		ReadTimeout:       30000, // 30 seconds
		MaxConnections:    10,
		MaxRetries:        3,
		MinRetryDelay:     100, // 100ms
		MetricsEnabled:    true,
	}
}

// SetHost sets the Grayskull server endpoint URL.
// Returns an error if the provided host is empty or contains only whitespace.
// The method will also ensure the host doesn't end with a trailing slash.
func (c *GrayskullClientConfiguration) SetHost(host string) error {
	host = strings.TrimSpace(host)
	if host == "" {
		return errors.New("host cannot be empty")
	}
	// Remove trailing slash if present
	c.Host = strings.TrimSuffix(host, "/")
	return nil
}

// SetConnectionTimeout sets the connection timeout in milliseconds.
// Returns an error if the timeout is not positive.
func (c *GrayskullClientConfiguration) SetConnectionTimeout(timeout int) error {
	if timeout <= 0 {
		return errors.New("connection timeout must be positive")
	}
	c.ConnectionTimeout = timeout
	return nil
}

// SetReadTimeout sets the read timeout in milliseconds.
// Returns an error if the timeout is not positive.
func (c *GrayskullClientConfiguration) SetReadTimeout(timeout int) error {
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
// Returns an error if maxRetries is less than 1 or greater than 10.
func (c *GrayskullClientConfiguration) SetMaxRetries(maxRetries int) error {
	if maxRetries < 1 {
		return errors.New(fmt.Sprintf("max retries cannot be less than 1, got: %d", maxRetries))
	}
	if maxRetries > 10 {
		return errors.New(fmt.Sprintf("max retries cannot be greater than 10, got: %d", maxRetries))
	}
	c.MaxRetries = maxRetries
	return nil
}

// SetMinRetryDelay sets the minimum delay between retry attempts in milliseconds.
// Returns an error if the delay is less than 50ms.
func (c *GrayskullClientConfiguration) SetMinRetryDelay(delay int) error {
	if delay < 50 {
		return fmt.Errorf("min retry delay must be at least 50ms, got: %d", delay)
	}
	c.MinRetryDelay = delay
	return nil
}

// SetMetricsEnabled always enables metrics collection.
// This method is kept for backward compatibility but will always set MetricsEnabled to true.
func (c *GrayskullClientConfiguration) SetMetricsEnabled() {
	c.MetricsEnabled = true
}
