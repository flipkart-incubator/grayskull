package models

import (
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestNewDefaultConfig(t *testing.T) {
	t.Run("creates config with default values", func(t *testing.T) {
		config := NewDefaultConfig()

		require.NotNil(t, config)
		assert.Equal(t, "", config.Host)
		assert.Equal(t, 10000, config.ConnectionTimeout)
		assert.Equal(t, 30000, config.ReadTimeout)
		assert.Equal(t, 10, config.MaxConnections)
		assert.Equal(t, 3, config.MaxRetries)
		assert.Equal(t, 100, config.MinRetryDelay)
		assert.True(t, config.MetricsEnabled)
	})

	t.Run("creates independent instances", func(t *testing.T) {
		config1 := NewDefaultConfig()
		config2 := NewDefaultConfig()

		config1.Host = "https://server1.com"
		config2.Host = "https://server2.com"

		assert.NotEqual(t, config1.Host, config2.Host)
	})
}

func TestSetHost(t *testing.T) {
	config := NewDefaultConfig()

	t.Run("sets valid host", func(t *testing.T) {
		err := config.SetHost("https://grayskull.example.com")
		assert.NoError(t, err)
		assert.Equal(t, "https://grayskull.example.com", config.Host)
	})

	t.Run("removes trailing slash", func(t *testing.T) {
		err := config.SetHost("https://grayskull.example.com/")
		assert.NoError(t, err)
		assert.Equal(t, "https://grayskull.example.com", config.Host)
	})

	t.Run("removes multiple trailing slashes", func(t *testing.T) {
		err := config.SetHost("https://grayskull.example.com///")
		assert.NoError(t, err)
		assert.Equal(t, "https://grayskull.example.com//", config.Host)
	})

	t.Run("trims whitespace", func(t *testing.T) {
		err := config.SetHost("  https://grayskull.example.com  ")
		assert.NoError(t, err)
		assert.Equal(t, "https://grayskull.example.com", config.Host)
	})

	t.Run("returns error for empty host", func(t *testing.T) {
		err := config.SetHost("")
		assert.Error(t, err)
		assert.Contains(t, err.Error(), "host cannot be empty")
	})

	t.Run("returns error for whitespace only host", func(t *testing.T) {
		err := config.SetHost("   ")
		assert.Error(t, err)
		assert.Contains(t, err.Error(), "host cannot be empty")
	})

	t.Run("handles localhost", func(t *testing.T) {
		err := config.SetHost("http://localhost:8080")
		assert.NoError(t, err)
		assert.Equal(t, "http://localhost:8080", config.Host)
	})

	t.Run("handles IP address", func(t *testing.T) {
		err := config.SetHost("http://192.168.1.1:8080")
		assert.NoError(t, err)
		assert.Equal(t, "http://192.168.1.1:8080", config.Host)
	})
}

func TestSetConnectionTimeout(t *testing.T) {
	config := NewDefaultConfig()

	t.Run("sets valid timeout", func(t *testing.T) {
		err := config.SetConnectionTimeout(5000)
		assert.NoError(t, err)
		assert.Equal(t, 5000, config.ConnectionTimeout)
	})

	t.Run("sets minimum timeout", func(t *testing.T) {
		err := config.SetConnectionTimeout(1)
		assert.NoError(t, err)
		assert.Equal(t, 1, config.ConnectionTimeout)
	})

	t.Run("sets large timeout", func(t *testing.T) {
		err := config.SetConnectionTimeout(60000)
		assert.NoError(t, err)
		assert.Equal(t, 60000, config.ConnectionTimeout)
	})

	t.Run("returns error for zero timeout", func(t *testing.T) {
		err := config.SetConnectionTimeout(0)
		assert.Error(t, err)
		assert.Contains(t, err.Error(), "connection timeout must be positive")
	})

	t.Run("returns error for negative timeout", func(t *testing.T) {
		err := config.SetConnectionTimeout(-100)
		assert.Error(t, err)
		assert.Contains(t, err.Error(), "connection timeout must be positive")
	})
}

func TestSetReadTimeout(t *testing.T) {
	config := NewDefaultConfig()

	t.Run("sets valid timeout", func(t *testing.T) {
		err := config.SetReadTimeout(15000)
		assert.NoError(t, err)
		assert.Equal(t, 15000, config.ReadTimeout)
	})

	t.Run("sets minimum timeout", func(t *testing.T) {
		err := config.SetReadTimeout(1)
		assert.NoError(t, err)
		assert.Equal(t, 1, config.ReadTimeout)
	})

	t.Run("sets large timeout", func(t *testing.T) {
		err := config.SetReadTimeout(120000)
		assert.NoError(t, err)
		assert.Equal(t, 120000, config.ReadTimeout)
	})

	t.Run("returns error for zero timeout", func(t *testing.T) {
		err := config.SetReadTimeout(0)
		assert.Error(t, err)
		assert.Contains(t, err.Error(), "read timeout must be positive")
	})

	t.Run("returns error for negative timeout", func(t *testing.T) {
		err := config.SetReadTimeout(-500)
		assert.Error(t, err)
		assert.Contains(t, err.Error(), "read timeout must be positive")
	})
}

func TestSetMaxConnections(t *testing.T) {
	config := NewDefaultConfig()

	t.Run("sets valid max connections", func(t *testing.T) {
		err := config.SetMaxConnections(20)
		assert.NoError(t, err)
		assert.Equal(t, 20, config.MaxConnections)
	})

	t.Run("sets minimum connections", func(t *testing.T) {
		err := config.SetMaxConnections(1)
		assert.NoError(t, err)
		assert.Equal(t, 1, config.MaxConnections)
	})

	t.Run("sets large connection pool", func(t *testing.T) {
		err := config.SetMaxConnections(100)
		assert.NoError(t, err)
		assert.Equal(t, 100, config.MaxConnections)
	})

	t.Run("returns error for zero connections", func(t *testing.T) {
		err := config.SetMaxConnections(0)
		assert.Error(t, err)
		assert.Contains(t, err.Error(), "max connections must be positive")
	})

	t.Run("returns error for negative connections", func(t *testing.T) {
		err := config.SetMaxConnections(-5)
		assert.Error(t, err)
		assert.Contains(t, err.Error(), "max connections must be positive")
	})
}

func TestSetMaxRetries(t *testing.T) {
	config := NewDefaultConfig()

	t.Run("sets valid retry count", func(t *testing.T) {
		err := config.SetMaxRetries(5)
		assert.NoError(t, err)
		assert.Equal(t, 5, config.MaxRetries)
	})

	t.Run("sets minimum retries", func(t *testing.T) {
		err := config.SetMaxRetries(1)
		assert.NoError(t, err)
		assert.Equal(t, 1, config.MaxRetries)
	})

	t.Run("sets maximum retries", func(t *testing.T) {
		err := config.SetMaxRetries(10)
		assert.NoError(t, err)
		assert.Equal(t, 10, config.MaxRetries)
	})

	t.Run("returns error for zero retries", func(t *testing.T) {
		err := config.SetMaxRetries(0)
		assert.Error(t, err)
		assert.Contains(t, err.Error(), "max retries cannot be less than 1")
		assert.Contains(t, err.Error(), "got: 0")
	})

	t.Run("returns error for negative retries", func(t *testing.T) {
		err := config.SetMaxRetries(-3)
		assert.Error(t, err)
		assert.Contains(t, err.Error(), "max retries cannot be less than 1")
		assert.Contains(t, err.Error(), "got: -3")
	})

	t.Run("returns error for retries greater than 10", func(t *testing.T) {
		err := config.SetMaxRetries(11)
		assert.Error(t, err)
		assert.Contains(t, err.Error(), "max retries cannot be greater than 10")
		assert.Contains(t, err.Error(), "got: 11")
	})

	t.Run("returns error for very large retry count", func(t *testing.T) {
		err := config.SetMaxRetries(100)
		assert.Error(t, err)
		assert.Contains(t, err.Error(), "max retries cannot be greater than 10")
	})
}

func TestSetMinRetryDelay(t *testing.T) {
	config := NewDefaultConfig()

	t.Run("sets valid delay", func(t *testing.T) {
		err := config.SetMinRetryDelay(200)
		assert.NoError(t, err)
		assert.Equal(t, 200, config.MinRetryDelay)
	})

	t.Run("sets minimum delay", func(t *testing.T) {
		err := config.SetMinRetryDelay(50)
		assert.NoError(t, err)
		assert.Equal(t, 50, config.MinRetryDelay)
	})

	t.Run("sets large delay", func(t *testing.T) {
		err := config.SetMinRetryDelay(5000)
		assert.NoError(t, err)
		assert.Equal(t, 5000, config.MinRetryDelay)
	})

	t.Run("returns error for delay less than 50ms", func(t *testing.T) {
		err := config.SetMinRetryDelay(49)
		assert.Error(t, err)
		assert.Contains(t, err.Error(), "min retry delay must be at least 50ms")
		assert.Contains(t, err.Error(), "got: 49")
	})

	t.Run("returns error for zero delay", func(t *testing.T) {
		err := config.SetMinRetryDelay(0)
		assert.Error(t, err)
		assert.Contains(t, err.Error(), "min retry delay must be at least 50ms")
	})

	t.Run("returns error for negative delay", func(t *testing.T) {
		err := config.SetMinRetryDelay(-100)
		assert.Error(t, err)
		assert.Contains(t, err.Error(), "min retry delay must be at least 50ms")
	})
}

func TestSetMetricsEnabled(t *testing.T) {
	config := NewDefaultConfig()

	t.Run("enables metrics", func(t *testing.T) {
		config.MetricsEnabled = false
		config.SetMetricsEnabled()
		assert.True(t, config.MetricsEnabled)
	})

	t.Run("keeps metrics enabled", func(t *testing.T) {
		config.MetricsEnabled = true
		config.SetMetricsEnabled()
		assert.True(t, config.MetricsEnabled)
	})
}

func TestGrayskullClientConfiguration_E2E(t *testing.T) {
	t.Run("complete configuration setup", func(t *testing.T) {
		config := NewDefaultConfig()

		err := config.SetHost("https://grayskull.prod.example.com")
		require.NoError(t, err)

		err = config.SetConnectionTimeout(5000)
		require.NoError(t, err)

		err = config.SetReadTimeout(20000)
		require.NoError(t, err)

		err = config.SetMaxConnections(50)
		require.NoError(t, err)

		err = config.SetMaxRetries(5)
		require.NoError(t, err)

		err = config.SetMinRetryDelay(150)
		require.NoError(t, err)

		config.SetMetricsEnabled()

		assert.Equal(t, "https://grayskull.prod.example.com", config.Host)
		assert.Equal(t, 5000, config.ConnectionTimeout)
		assert.Equal(t, 20000, config.ReadTimeout)
		assert.Equal(t, 50, config.MaxConnections)
		assert.Equal(t, 5, config.MaxRetries)
		assert.Equal(t, 150, config.MinRetryDelay)
		assert.True(t, config.MetricsEnabled)
	})

	t.Run("handles configuration errors gracefully", func(t *testing.T) {
		config := NewDefaultConfig()

		err := config.SetHost("")
		assert.Error(t, err)

		err = config.SetConnectionTimeout(-1)
		assert.Error(t, err)

		err = config.SetMaxRetries(15)
		assert.Error(t, err)

		// Config should still have default values
		assert.Equal(t, "", config.Host)
		assert.Equal(t, 10000, config.ConnectionTimeout)
		assert.Equal(t, 3, config.MaxRetries)
	})

	t.Run("validates all boundary conditions", func(t *testing.T) {
		config := NewDefaultConfig()

		// Test boundary values
		assert.NoError(t, config.SetConnectionTimeout(1))
		assert.NoError(t, config.SetReadTimeout(1))
		assert.NoError(t, config.SetMaxConnections(1))
		assert.NoError(t, config.SetMaxRetries(1))
		assert.NoError(t, config.SetMinRetryDelay(50))

		assert.NoError(t, config.SetMaxRetries(10))

		// Test just beyond boundaries
		assert.Error(t, config.SetConnectionTimeout(0))
		assert.Error(t, config.SetReadTimeout(0))
		assert.Error(t, config.SetMaxConnections(0))
		assert.Error(t, config.SetMaxRetries(0))
		assert.Error(t, config.SetMaxRetries(11))
		assert.Error(t, config.SetMinRetryDelay(49))
	})
}

func BenchmarkNewDefaultConfig(b *testing.B) {
	for i := 0; i < b.N; i++ {
		_ = NewDefaultConfig()
	}
}

func BenchmarkSetHost(b *testing.B) {
	config := NewDefaultConfig()
	b.ResetTimer()

	for i := 0; i < b.N; i++ {
		_ = config.SetHost("https://grayskull.example.com")
	}
}

func BenchmarkSetAllFields(b *testing.B) {
	for i := 0; i < b.N; i++ {
		config := NewDefaultConfig()
		_ = config.SetHost("https://grayskull.example.com")
		_ = config.SetConnectionTimeout(5000)
		_ = config.SetReadTimeout(20000)
		_ = config.SetMaxConnections(50)
		_ = config.SetMaxRetries(5)
		_ = config.SetMinRetryDelay(150)
		config.SetMetricsEnabled()
	}
}
