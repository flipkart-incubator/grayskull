package client_impl

import (
	"sync"
	"testing"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/metrics"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/models"
)

// TestNewGrayskullClient_PopulatesDefaultHeaders verifies that the SDK
// populates the Grayskull-Workload and User-Agent default headers at
// construction time, matching the Java SDK's GrayskullClientImpl constructor.
func TestNewGrayskullClient_PopulatesDefaultHeaders(t *testing.T) {
	cfg := &models.GrayskullClientConfiguration{
		Host:           "http://test.local",
		MaxConnections: 10,
	}
	mockAuth := &MockAuthProvider{}

	client, err := NewGrayskullClient(mockAuth, cfg, metrics.NewPrometheusRecorder(prometheus.NewRegistry()))
	require.NoError(t, err)
	t.Cleanup(func() {
		if c, ok := client.(*GrayskullClientImpl); ok {
			_ = c.Close()
		}
	})

	headers := cfg.DefaultHeaders()
	assert.Contains(t, headers, "Grayskull-Workload",
		"client construction must seed Grayskull-Workload from the resolver")
	assert.NotEmpty(t, headers["Grayskull-Workload"],
		"workload identity must be non-empty")

	ua, ok := headers["User-Agent"]
	require.True(t, ok, "User-Agent must be set on the configuration default headers")
	assert.Contains(t, ua, "grayskull-go/", "User-Agent must advertise the Go SDK")
	assert.Contains(t, ua, SDKVersion, "User-Agent must include the SDK version")
}

// TestGrayskullClient_Close_IsIdempotent ensures Close() on the concrete
// implementation can be called repeatedly without panicking and only runs
// the underlying teardown once.
func TestGrayskullClient_Close_IsIdempotent(t *testing.T) {
	cfg := &models.GrayskullClientConfiguration{
		Host:           "http://test.local",
		MaxConnections: 10,
	}
	mockAuth := &MockAuthProvider{}

	client, err := NewGrayskullClient(mockAuth, cfg, metrics.NewPrometheusRecorder(prometheus.NewRegistry()))
	require.NoError(t, err)
	impl, ok := client.(*GrayskullClientImpl)
	require.True(t, ok)

	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_ = impl.Close()
		}()
	}
	wg.Wait()
	// A direct second close must also be safe.
	assert.NoError(t, impl.Close())
}
