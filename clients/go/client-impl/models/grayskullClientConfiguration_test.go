package models

import (
	"testing"

	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/constants"
	"github.com/stretchr/testify/assert"
)

// TestNewDefaultConfig_ReturnsValidConfig verifies that NewDefaultConfig
// returns a configuration with reasonable default values.
func TestNewDefaultConfig_ReturnsValidConfig(t *testing.T) {
	config := NewDefaultConfig()

	assert.NotNil(t, config)
	assert.Equal(t, "", config.Host)
	assert.Equal(t, 10000, config.ConnectionTimeout)
	assert.Equal(t, 30000, config.ReadTimeout)
	assert.Equal(t, 10, config.MaxConnections)
	assert.Equal(t, 300000, config.IdleConnTimeout)
	assert.Equal(t, 10, config.MaxIdleConns)
	assert.Equal(t, 10, config.MaxIdleConnsPerHost)
	assert.Equal(t, 3, config.MaxRetries)
	assert.Equal(t, 100, config.MinRetryDelay)
	assert.True(t, config.MetricsEnabled)
	assert.Equal(t, constants.DefaultPollIntervalSeconds, config.PollingIntervalSeconds)
}

// TestAddDefaultHeader_AddsHeader verifies that AddDefaultHeader adds a header
// to the configuration.
func TestAddDefaultHeader_AddsHeader(t *testing.T) {
	config := NewDefaultConfig()

	config.AddDefaultHeader("X-Custom-Header", "custom-value")

	headers := config.GetDefaultHeaders()
	assert.NotNil(t, headers)
	assert.Equal(t, "custom-value", headers["X-Custom-Header"])
}

// TestAddDefaultHeader_MultipleHeaders verifies that multiple headers can be added.
func TestAddDefaultHeader_MultipleHeaders(t *testing.T) {
	config := NewDefaultConfig()

	config.AddDefaultHeader("X-Header-1", "value1")
	config.AddDefaultHeader("X-Header-2", "value2")
	config.AddDefaultHeader("X-Header-3", "value3")

	headers := config.GetDefaultHeaders()
	assert.Equal(t, 3, len(headers))
	assert.Equal(t, "value1", headers["X-Header-1"])
	assert.Equal(t, "value2", headers["X-Header-2"])
	assert.Equal(t, "value3", headers["X-Header-3"])
}

// TestAddDefaultHeader_OverwritesExisting verifies that adding a header with
// the same key overwrites the previous value.
func TestAddDefaultHeader_OverwritesExisting(t *testing.T) {
	config := NewDefaultConfig()

	config.AddDefaultHeader("X-Header", "original")
	config.AddDefaultHeader("X-Header", "updated")

	headers := config.GetDefaultHeaders()
	assert.Equal(t, "updated", headers["X-Header"])
}

// TestAddDefaultHeader_EmptyKey_NoOp verifies that adding a header with an
// empty key is a no-op.
func TestAddDefaultHeader_EmptyKey_NoOp(t *testing.T) {
	config := NewDefaultConfig()

	config.AddDefaultHeader("", "value")

	headers := config.GetDefaultHeaders()
	assert.Nil(t, headers)
}

// TestAddDefaultHeader_EmptyValue_NoOp verifies that adding a header with an
// empty value is a no-op.
func TestAddDefaultHeader_EmptyValue_NoOp(t *testing.T) {
	config := NewDefaultConfig()

	config.AddDefaultHeader("X-Header", "")

	headers := config.GetDefaultHeaders()
	assert.Nil(t, headers)
}

// TestAddDefaultHeader_BothEmpty_NoOp verifies that adding a header with both
// empty key and value is a no-op.
func TestAddDefaultHeader_BothEmpty_NoOp(t *testing.T) {
	config := NewDefaultConfig()

	config.AddDefaultHeader("", "")

	headers := config.GetDefaultHeaders()
	assert.Nil(t, headers)
}

// TestGetDefaultHeaders_ReturnsNilWhenNoHeaders verifies that GetDefaultHeaders
// returns nil when no headers have been added.
func TestGetDefaultHeaders_ReturnsNilWhenNoHeaders(t *testing.T) {
	config := NewDefaultConfig()

	headers := config.GetDefaultHeaders()

	assert.Nil(t, headers)
}

// TestGetDefaultHeaders_ReturnsCopy verifies that GetDefaultHeaders returns a
// copy of the headers map, so external modifications don't affect the config.
func TestGetDefaultHeaders_ReturnsCopy(t *testing.T) {
	config := NewDefaultConfig()

	config.AddDefaultHeader("X-Header", "original")

	headers1 := config.GetDefaultHeaders()
	assert.NotNil(t, headers1)
	assert.Equal(t, "original", headers1["X-Header"])

	// Modify the returned map
	headers1["X-Header"] = "modified"
	headers1["X-New"] = "added"

	// Get headers again and verify original values
	headers2 := config.GetDefaultHeaders()
	assert.Equal(t, "original", headers2["X-Header"], "modifications to returned map should not affect config")
	assert.NotContains(t, headers2, "X-New", "new keys in returned map should not affect config")
}

// TestGetDefaultHeaders_EmptyMapAfterInvalidAdds verifies that GetDefaultHeaders
// returns nil even after attempting to add invalid headers.
func TestGetDefaultHeaders_EmptyMapAfterInvalidAdds(t *testing.T) {
	config := NewDefaultConfig()

	config.AddDefaultHeader("", "value")
	config.AddDefaultHeader("key", "")

	headers := config.GetDefaultHeaders()
	assert.Nil(t, headers, "should return nil when no valid headers are added")
}

// TestAddDefaultHeader_InitializesMapOnFirstValidAdd verifies that the internal
// map is initialized on the first valid header addition.
func TestAddDefaultHeader_InitializesMapOnFirstValidAdd(t *testing.T) {
	config := NewDefaultConfig()

	// First, try adding invalid headers (should not initialize map)
	config.AddDefaultHeader("", "value")
	config.AddDefaultHeader("key", "")

	headers1 := config.GetDefaultHeaders()
	assert.Nil(t, headers1)

	// Now add a valid header
	config.AddDefaultHeader("X-Valid", "value")

	headers2 := config.GetDefaultHeaders()
	assert.NotNil(t, headers2)
	assert.Equal(t, 1, len(headers2))
	assert.Equal(t, "value", headers2["X-Valid"])
}

// TestAddDefaultHeader_PreservesExistingHeaders verifies that adding a new
// header doesn't affect previously added headers.
func TestAddDefaultHeader_PreservesExistingHeaders(t *testing.T) {
	config := NewDefaultConfig()

	config.AddDefaultHeader("X-Header-1", "value1")
	config.AddDefaultHeader("X-Header-2", "value2")

	headers := config.GetDefaultHeaders()
	assert.Equal(t, 2, len(headers))
	assert.Equal(t, "value1", headers["X-Header-1"])
	assert.Equal(t, "value2", headers["X-Header-2"])

	// Add a third header
	config.AddDefaultHeader("X-Header-3", "value3")

	headers = config.GetDefaultHeaders()
	assert.Equal(t, 3, len(headers))
	assert.Equal(t, "value1", headers["X-Header-1"], "existing headers should be preserved")
	assert.Equal(t, "value2", headers["X-Header-2"], "existing headers should be preserved")
	assert.Equal(t, "value3", headers["X-Header-3"])
}

// TestAddDefaultHeader_WhitespaceValues verifies behavior with whitespace-only values.
func TestAddDefaultHeader_WhitespaceValues(t *testing.T) {
	config := NewDefaultConfig()

	// Whitespace-only values are technically valid in HTTP headers, so they should be added
	config.AddDefaultHeader("X-Whitespace", "   ")

	headers := config.GetDefaultHeaders()
	assert.NotNil(t, headers)
	assert.Equal(t, "   ", headers["X-Whitespace"])
}

// TestAddDefaultHeader_SpecialCharacters verifies that headers with special
// characters can be added.
func TestAddDefaultHeader_SpecialCharacters(t *testing.T) {
	config := NewDefaultConfig()

	config.AddDefaultHeader("X-Special", "value-with-dashes")
	config.AddDefaultHeader("X-Unicode", "value-with-émojis-😀")

	headers := config.GetDefaultHeaders()
	assert.Equal(t, "value-with-dashes", headers["X-Special"])
	assert.Equal(t, "value-with-émojis-😀", headers["X-Unicode"])
}

// TestAddDefaultHeader_CaseSensitiveKeys verifies that header keys are case-sensitive
// in the internal map (HTTP spec says they're case-insensitive, but our map treats them as-is).
func TestAddDefaultHeader_CaseSensitiveKeys(t *testing.T) {
	config := NewDefaultConfig()

	config.AddDefaultHeader("X-Header", "lowercase-x")
	config.AddDefaultHeader("x-header", "uppercase-X")

	headers := config.GetDefaultHeaders()
	assert.Equal(t, 2, len(headers), "keys with different cases should be treated as different keys")
	assert.Equal(t, "lowercase-x", headers["X-Header"])
	assert.Equal(t, "uppercase-X", headers["x-header"])
}

// TestGetDefaultHeaders_ConsecutiveCalls_ReturnIndependentCopies verifies that
// each call to GetDefaultHeaders returns an independent copy.
func TestGetDefaultHeaders_ConsecutiveCalls_ReturnIndependentCopies(t *testing.T) {
	config := NewDefaultConfig()

	config.AddDefaultHeader("X-Header", "value")

	headers1 := config.GetDefaultHeaders()
	headers2 := config.GetDefaultHeaders()

	// Modify headers1
	headers1["X-Header"] = "modified"
	headers1["X-New"] = "added"

	// headers2 should still have original values
	assert.Equal(t, "value", headers2["X-Header"])
	assert.NotContains(t, headers2, "X-New")
}

// TestNewDefaultConfig_PollingIntervalSeconds verifies the default polling interval.
func TestNewDefaultConfig_PollingIntervalSeconds(t *testing.T) {
	config := NewDefaultConfig()

	assert.Equal(t, constants.DefaultPollIntervalSeconds, config.PollingIntervalSeconds)
	assert.Equal(t, 60, config.PollingIntervalSeconds, "default should be 60 seconds")
}

// TestConfiguration_AllFieldsAccessible verifies that all configuration fields
// can be set and retrieved.
func TestConfiguration_AllFieldsAccessible(t *testing.T) {
	config := &GrayskullClientConfiguration{
		Host:                   "https://example.com",
		ConnectionTimeout:      5000,
		ReadTimeout:            10000,
		MaxConnections:         20,
		IdleConnTimeout:        60000,
		MaxIdleConns:           15,
		MaxIdleConnsPerHost:    10,
		MaxRetries:             5,
		MinRetryDelay:          50,
		MetricsEnabled:         false,
		PollingIntervalSeconds: 120,
	}

	assert.Equal(t, "https://example.com", config.Host)
	assert.Equal(t, 5000, config.ConnectionTimeout)
	assert.Equal(t, 10000, config.ReadTimeout)
	assert.Equal(t, 20, config.MaxConnections)
	assert.Equal(t, 60000, config.IdleConnTimeout)
	assert.Equal(t, 15, config.MaxIdleConns)
	assert.Equal(t, 10, config.MaxIdleConnsPerHost)
	assert.Equal(t, 5, config.MaxRetries)
	assert.Equal(t, 50, config.MinRetryDelay)
	assert.False(t, config.MetricsEnabled)
	assert.Equal(t, 120, config.PollingIntervalSeconds)
}

// TestGetWorkloadIdentityResolver_DefaultResolver verifies that GetWorkloadIdentityResolver
// returns a default resolver when none is set.
func TestGetWorkloadIdentityResolver_DefaultResolver(t *testing.T) {
	config := &GrayskullClientConfiguration{}

	resolver := config.GetWorkloadIdentityResolver()

	assert.NotNil(t, resolver)
	identity := resolver.Resolve()
	assert.NotEmpty(t, identity, "default resolver should return non-empty identity")
}

// TestSetWorkloadIdentityResolver_CustomResolver verifies that a custom resolver can be set.
func TestSetWorkloadIdentityResolver_CustomResolver(t *testing.T) {
	config := NewDefaultConfig()

	// Create custom resolver
	customResolver := &mockWorkloadResolver{identity: "custom-workload-id"}
	config.SetWorkloadIdentityResolver(customResolver)

	resolver := config.GetWorkloadIdentityResolver()
	assert.Equal(t, "custom-workload-id", resolver.Resolve())
}

// TestSetWorkloadIdentityResolver_NilIsNoOp verifies that setting nil resolver is a no-op.
func TestSetWorkloadIdentityResolver_NilIsNoOp(t *testing.T) {
	config := NewDefaultConfig()

	// Set custom resolver first
	customResolver := &mockWorkloadResolver{identity: "custom"}
	config.SetWorkloadIdentityResolver(customResolver)

	// Try to set nil (should be no-op)
	config.SetWorkloadIdentityResolver(nil)

	// Should still have the custom resolver
	resolver := config.GetWorkloadIdentityResolver()
	assert.Equal(t, "custom", resolver.Resolve())
}

// TestNewDefaultConfig_HasDefaultWorkloadResolver verifies that NewDefaultConfig
// initializes with a default workload resolver.
func TestNewDefaultConfig_HasDefaultWorkloadResolver(t *testing.T) {
	config := NewDefaultConfig()

	assert.NotNil(t, config.WorkloadIdentityResolver)
	resolver := config.GetWorkloadIdentityResolver()
	assert.NotNil(t, resolver)

	identity := resolver.Resolve()
	assert.NotEmpty(t, identity, "default resolver should return non-empty identity")
}

// mockWorkloadResolver is a mock implementation for testing
type mockWorkloadResolver struct {
	identity string
}

func (m *mockWorkloadResolver) Resolve() string {
	return m.identity
}
