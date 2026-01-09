package models

import (
	"errors"
	"strings"
)

// GrayskullClientConfiguration holds all the necessary configuration parameters
// required to connect to and interact with the Grayskull service.
// It is immutable and thread-safe once created.
type GrayskullClientConfiguration struct {
	host               string
	connectionTimeout  int
	readTimeout        int
	maxConnections     int
	maxRetries         int
	minRetryDelay      int
	metricsEnabled     bool
	insecureSkipVerify bool
}

// NewGrayskullClientConfiguration creates a new configuration with default values.
func NewGrayskullClientConfiguration() *GrayskullClientConfiguration {
	return &GrayskullClientConfiguration{
		connectionTimeout:  10000, // 10 seconds
		readTimeout:        30000, // 30 seconds
		maxConnections:     10,
		maxRetries:         3,
		minRetryDelay:      100, // 100ms
		metricsEnabled:     true,
		insecureSkipVerify: false, // Default to secure mode
	}
}

// Host returns the Grayskull server endpoint URL.
func (c *GrayskullClientConfiguration) Host() string {
	return c.host
}

// SetHost sets the Grayskull server endpoint URL.
// Returns an error if host is empty.
func (c *GrayskullClientConfiguration) SetHost(host string) error {
	if strings.TrimSpace(host) == "" {
		return errors.New("host cannot be empty")
	}
	// Remove trailing slash if present
	c.host = strings.TrimSuffix(host, "/")
	return nil
}

// ConnectionTimeout returns the connection timeout in milliseconds.
func (c *GrayskullClientConfiguration) ConnectionTimeout() int {
	return c.connectionTimeout
}

// SetConnectionTimeout sets the connection timeout in milliseconds.
// Returns an error if timeout is not positive.
func (c *GrayskullClientConfiguration) SetConnectionTimeout(timeout int) error {
	if timeout <= 0 {
		return errors.New("connection timeout must be positive")
	}
	c.connectionTimeout = timeout
	return nil
}

// ReadTimeout returns the read timeout in milliseconds.
func (c *GrayskullClientConfiguration) ReadTimeout() int {
	return c.readTimeout
}

// SetReadTimeout sets the read timeout in milliseconds.
// Returns an error if timeout is not positive.
func (c *GrayskullClientConfiguration) SetReadTimeout(timeout int) error {
	if timeout <= 0 {
		return errors.New("read timeout must be positive")
	}
	c.readTimeout = timeout
	return nil
}

// MaxConnections returns the maximum number of concurrent connections.
func (c *GrayskullClientConfiguration) MaxConnections() int {
	return c.maxConnections
}

// SetMaxConnections sets the maximum number of concurrent connections.
// Returns an error if maxConnections is not positive.
func (c *GrayskullClientConfiguration) SetMaxConnections(maxConnections int) error {
	if maxConnections <= 0 {
		return errors.New("max connections must be positive")
	}
	c.maxConnections = maxConnections
	return nil
}

// MaxRetries returns the maximum number of retry attempts.
func (c *GrayskullClientConfiguration) MaxRetries() int {
	return c.maxRetries
}

// SetMaxRetries sets the maximum number of retry attempts.
// Returns an error if maxRetries is not between 1 and 10.
func (c *GrayskullClientConfiguration) SetMaxRetries(maxRetries int) error {
	if maxRetries < 1 {
		return errors.New("max retries cannot be less than 1")
	}
	if maxRetries > 10 {
		return errors.New("max retries cannot be greater than 10")
	}
	c.maxRetries = maxRetries
	return nil
}

// MinRetryDelay returns the minimum delay between retry attempts in milliseconds.
func (c *GrayskullClientConfiguration) MinRetryDelay() int {
	return c.minRetryDelay
}

// SetMinRetryDelay sets the minimum delay between retry attempts in milliseconds.
// Returns an error if delay is less than 50ms.
func (c *GrayskullClientConfiguration) SetMinRetryDelay(delay int) error {
	if delay < 50 {
		return errors.New("min retry delay must be at least 50ms")
	}
	c.minRetryDelay = delay
	return nil
}

// MetricsEnabled returns whether metrics collection is enabled.
func (c *GrayskullClientConfiguration) MetricsEnabled() bool {
	return c.metricsEnabled
}

// SetMetricsEnabled enables or disables metrics collection.
func (c *GrayskullClientConfiguration) SetMetricsEnabled(enabled bool) {
	c.metricsEnabled = enabled
}

// InsecureSkipVerify returns whether to skip TLS certificate verification.
func (c *GrayskullClientConfiguration) InsecureSkipVerify() bool {
	return c.insecureSkipVerify
}

// SetInsecureSkipVerify sets whether to skip TLS certificate verification.
// WARNING: Setting this to true makes the connection insecure and vulnerable to man-in-the-middle attacks.
// Only use this for testing or in trusted environments.
func (c *GrayskullClientConfiguration) SetInsecureSkipVerify(skipVerify bool) {
	c.insecureSkipVerify = skipVerify
}
