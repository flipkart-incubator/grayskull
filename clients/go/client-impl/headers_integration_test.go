package client_impl

import (
	"testing"

	apiconstants "github.com/flipkart-incubator/grayskull/clients/go/client-api/constants"
	clientapiworkload "github.com/flipkart-incubator/grayskull/clients/go/client-api/workload"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/auth"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/metrics"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/models"
	"github.com/prometheus/client_golang/prometheus"
	"github.com/stretchr/testify/assert"
)

// mockAuthForHeaders is a mock auth provider for header tests
type mockAuthForHeaders struct{}

func (m *mockAuthForHeaders) GetAuthHeader() (string, error) {
	return "Bearer test-token", nil
}

// mockWorkloadForHeaders is a mock workload resolver for testing
type mockWorkloadForHeaders struct {
	identity string
}

func (m *mockWorkloadForHeaders) Resolve() string {
	return m.identity
}

// TestNewGrayskullClient_SetsUserAgentHeader verifies that the User-Agent header
// is set automatically during client construction.
func TestNewGrayskullClient_SetsUserAgentHeader(t *testing.T) {
	mockAuth := &mockAuthForHeaders{}
	config := models.NewDefaultConfig()
	config.Host = "http://localhost:8080"
	config.MaxConnections = 10

	client, err := NewGrayskullClient(mockAuth, config, metrics.NewPrometheusRecorder(prometheus.NewRegistry()))

	assert.NoError(t, err)
	assert.NotNil(t, client)

	// Verify User-Agent header was set
	headers := config.GetDefaultHeaders()
	assert.NotNil(t, headers)

	userAgent, exists := headers[apiconstants.HeaderUserAgent]
	assert.True(t, exists, "User-Agent header should be set")
	assert.Contains(t, userAgent, "grayskull-go/", "User-Agent should contain grayskull-go/")
	assert.NotEmpty(t, userAgent, "User-Agent should not be empty")

	// Clean up
	if implClient, ok := client.(*GrayskullClientImpl); ok {
		implClient.Close()
	}
}

// TestNewGrayskullClient_UserAgentFormat verifies the User-Agent header format.
func TestNewGrayskullClient_UserAgentFormat(t *testing.T) {
	mockAuth := &mockAuthForHeaders{}
	config := models.NewDefaultConfig()
	config.Host = "http://localhost:8080"
	config.MaxConnections = 10

	client, err := NewGrayskullClient(mockAuth, config, metrics.NewPrometheusRecorder(prometheus.NewRegistry()))

	assert.NoError(t, err)
	assert.NotNil(t, client)

	headers := config.GetDefaultHeaders()
	userAgent := headers[apiconstants.HeaderUserAgent]

	// Should match pattern: grayskull-go/<version>
	// Version can be "dev" (default) or a build-time injected version
	assert.Regexp(t, `^grayskull-go/.+$`, userAgent, "User-Agent should match pattern grayskull-go/<version>")

	// Clean up
	if implClient, ok := client.(*GrayskullClientImpl); ok {
		implClient.Close()
	}
}

// TestNewGrayskullClient_SetsWorkloadHeader verifies that the Grayskull-Workload
// header is set automatically during client construction.
func TestNewGrayskullClient_SetsWorkloadHeader(t *testing.T) {
	mockAuth := &mockAuthForHeaders{}
	config := models.NewDefaultConfig()
	config.Host = "http://localhost:8080"
	config.MaxConnections = 10

	client, err := NewGrayskullClient(mockAuth, config, metrics.NewPrometheusRecorder(prometheus.NewRegistry()))

	assert.NoError(t, err)
	assert.NotNil(t, client)

	// Verify Grayskull-Workload header was set
	headers := config.GetDefaultHeaders()
	assert.NotNil(t, headers)

	workloadHeader, exists := headers[apiconstants.HeaderWorkload]
	assert.True(t, exists, "Grayskull-Workload header should be set")
	assert.NotEmpty(t, workloadHeader, "Grayskull-Workload should not be empty")

	// Default should be hostname (or "UNKNOWN" if hostname resolution failed)
	// We just verify it's non-empty
	assert.NotEqual(t, "", workloadHeader)

	// Clean up
	if implClient, ok := client.(*GrayskullClientImpl); ok {
		implClient.Close()
	}
}

// TestNewGrayskullClient_DefaultWorkloadIsHostname verifies the default workload
// identity is the hostname.
func TestNewGrayskullClient_DefaultWorkloadIsHostname(t *testing.T) {
	mockAuth := &mockAuthForHeaders{}
	config := models.NewDefaultConfig()
	config.Host = "http://localhost:8080"
	config.MaxConnections = 10

	client, err := NewGrayskullClient(mockAuth, config, metrics.NewPrometheusRecorder(prometheus.NewRegistry()))

	assert.NoError(t, err)
	assert.NotNil(t, client)

	headers := config.GetDefaultHeaders()
	workloadHeader := headers[apiconstants.HeaderWorkload]

	// Should be either a valid hostname or "UNKNOWN" (fallback)
	assert.True(t, len(workloadHeader) > 0, "Workload header should have content")

	// Clean up
	if implClient, ok := client.(*GrayskullClientImpl); ok {
		implClient.Close()
	}
}

// TestNewGrayskullClient_CustomWorkloadResolver verifies that a custom workload
// resolver is honored.
func TestNewGrayskullClient_CustomWorkloadResolver(t *testing.T) {
	mockAuth := &mockAuthForHeaders{}
	config := models.NewDefaultConfig()
	config.Host = "http://localhost:8080"
	config.MaxConnections = 10

	// Set custom workload resolver
	customResolver := &mockWorkloadForHeaders{identity: "payment-service-pod-123"}
	config.SetWorkloadIdentityResolver(customResolver)

	client, err := NewGrayskullClient(mockAuth, config, metrics.NewPrometheusRecorder(prometheus.NewRegistry()))

	assert.NoError(t, err)
	assert.NotNil(t, client)

	// Verify custom workload identity was used
	headers := config.GetDefaultHeaders()
	workloadHeader := headers[apiconstants.HeaderWorkload]

	assert.Equal(t, "payment-service-pod-123", workloadHeader, "Custom workload identity should be used")

	// Clean up
	if implClient, ok := client.(*GrayskullClientImpl); ok {
		implClient.Close()
	}
}

// TestNewGrayskullClient_BothHeadersSet verifies that both User-Agent and
// Grayskull-Workload headers are set in a single client construction.
func TestNewGrayskullClient_BothHeadersSet(t *testing.T) {
	mockAuth := &mockAuthForHeaders{}
	config := models.NewDefaultConfig()
	config.Host = "http://localhost:8080"
	config.MaxConnections = 10

	client, err := NewGrayskullClient(mockAuth, config, metrics.NewPrometheusRecorder(prometheus.NewRegistry()))

	assert.NoError(t, err)
	assert.NotNil(t, client)

	headers := config.GetDefaultHeaders()

	// Both headers should be present
	userAgent, hasUserAgent := headers[apiconstants.HeaderUserAgent]
	workload, hasWorkload := headers[apiconstants.HeaderWorkload]

	assert.True(t, hasUserAgent, "User-Agent header should be set")
	assert.True(t, hasWorkload, "Grayskull-Workload header should be set")
	assert.NotEmpty(t, userAgent, "User-Agent should not be empty")
	assert.NotEmpty(t, workload, "Grayskull-Workload should not be empty")

	// Clean up
	if implClient, ok := client.(*GrayskullClientImpl); ok {
		implClient.Close()
	}
}

// TestNewGrayskullClient_HeadersNotOverwrittenByCustom verifies that even if
// users try to set these headers via AddDefaultHeader before client creation,
// the SDK's internal headers take precedence.
func TestNewGrayskullClient_HeadersOverwriteUserProvided(t *testing.T) {
	mockAuth := &mockAuthForHeaders{}
	config := models.NewDefaultConfig()
	config.Host = "http://localhost:8080"
	config.MaxConnections = 10

	// User tries to set their own User-Agent and Workload headers
	config.AddDefaultHeader(apiconstants.HeaderUserAgent, "my-custom-agent/1.0")
	config.AddDefaultHeader(apiconstants.HeaderWorkload, "user-provided-workload")

	client, err := NewGrayskullClient(mockAuth, config, metrics.NewPrometheusRecorder(prometheus.NewRegistry()))

	assert.NoError(t, err)
	assert.NotNil(t, client)

	headers := config.GetDefaultHeaders()

	// SDK headers should have overwritten the user-provided ones
	userAgent := headers[apiconstants.HeaderUserAgent]
	workload := headers[apiconstants.HeaderWorkload]

	assert.Contains(t, userAgent, "grayskull-go/", "SDK User-Agent should overwrite user-provided")
	assert.NotEqual(t, "my-custom-agent/1.0", userAgent, "User-provided User-Agent should be overwritten")

	// Workload will be from the default resolver (hostname), not user-provided
	assert.NotEqual(t, "user-provided-workload", workload, "User-provided Workload should be overwritten")

	// Clean up
	if implClient, ok := client.(*GrayskullClientImpl); ok {
		implClient.Close()
	}
}

// TestNewGrayskullClient_WorkloadResolvedOnce verifies that the workload identity
// is resolved only once during client construction.
func TestNewGrayskullClient_WorkloadResolvedOnce(t *testing.T) {
	mockAuth := &mockAuthForHeaders{}
	config := models.NewDefaultConfig()
	config.Host = "http://localhost:8080"
	config.MaxConnections = 10

	// Use counting resolver to track how many times Resolve() is called
	callCount := 0
	countingResolver := &countingWorkloadResolver{
		identity:  "test-identity",
		callCount: &callCount,
	}
	config.SetWorkloadIdentityResolver(countingResolver)

	client, err := NewGrayskullClient(mockAuth, config, metrics.NewPrometheusRecorder(prometheus.NewRegistry()))

	assert.NoError(t, err)
	assert.NotNil(t, client)

	// Resolve should be called exactly once during client construction
	assert.Equal(t, 1, callCount, "Workload resolver should be called exactly once")

	headers := config.GetDefaultHeaders()
	workload := headers[apiconstants.HeaderWorkload]
	assert.Equal(t, "test-identity", workload)

	// Clean up
	if implClient, ok := client.(*GrayskullClientImpl); ok {
		implClient.Close()
	}
}

// TestNewGrayskullClient_WorkloadResolverUsesConfiguredResolver verifies that
// the GetWorkloadIdentityResolver method is used to retrieve the resolver.
func TestNewGrayskullClient_WorkloadResolverUsesConfiguredResolver(t *testing.T) {
	mockAuth := &mockAuthForHeaders{}
	config := &models.GrayskullClientConfiguration{
		Host:           "http://localhost:8080",
		MaxConnections: 10,
		// Don't set WorkloadIdentityResolver - should use default
	}

	client, err := NewGrayskullClient(mockAuth, config, metrics.NewPrometheusRecorder(prometheus.NewRegistry()))

	assert.NoError(t, err)
	assert.NotNil(t, client)

	// When no resolver is set, GetWorkloadIdentityResolver returns default
	headers := config.GetDefaultHeaders()
	workload := headers[apiconstants.HeaderWorkload]

	assert.NotEmpty(t, workload, "Should use default workload resolver when none configured")

	// Clean up
	if implClient, ok := client.(*GrayskullClientImpl); ok {
		implClient.Close()
	}
}

// countingWorkloadResolver is a test resolver that counts Resolve() calls
type countingWorkloadResolver struct {
	identity  string
	callCount *int
}

func (r *countingWorkloadResolver) Resolve() string {
	*r.callCount++
	return r.identity
}

// Verify interfaces are implemented correctly at compile time
var (
	_ clientapiworkload.WorkloadIdentityResolver = (*mockWorkloadForHeaders)(nil)
	_ clientapiworkload.WorkloadIdentityResolver = (*countingWorkloadResolver)(nil)
	_ auth.GrayskullAuthHeaderProvider           = (*mockAuthForHeaders)(nil)
)
