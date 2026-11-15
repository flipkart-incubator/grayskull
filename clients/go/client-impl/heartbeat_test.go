package client_impl

import (
	"context"
	"testing"

	"github.com/flipkart-incubator/grayskull/clients/go/client-api/models"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/internal/models/heartbeat"
	clientmodels "github.com/flipkart-incubator/grayskull/clients/go/client-impl/models"
	"github.com/prometheus/client_golang/prometheus"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/metrics"
)

func noopHook(context.Context, models.SecretValue) error { return nil }

func TestHeldSecrets_ReportsNewestVersionAndHook(t *testing.T) {
	c := NewGrayskullClientForTesting("http://gs", &MockAuthProvider{}, clientmodels.NewDefaultConfig(), &MockGrayskullHTTPClient{}, nil)
	c.lastSeenVersions.Store("proj:db-password", 7)
	c.lastSeenVersions.Store("proj:api:key", 3) // secret name with a colon
	_, err := c.RegisterRefreshHook(context.Background(), "proj:db-password", noopHook)
	require.NoError(t, err)
	c.registry.Get("proj:db-password").LastKnownVersion.Store(8) // delivered to the hook after the fetch

	assert.ElementsMatch(t, []heartbeat.HeldSecret{
		{ProjectID: "proj", SecretName: "db-password", Version: 8, RefreshHook: true},
		{ProjectID: "proj", SecretName: "api:key", Version: 3, RefreshHook: false},
	}, c.heldSecrets())
}

func TestNewGrayskullClient_HeartbeatFollowsConfig(t *testing.T) {
	for _, enabled := range []bool{true, false} {
		config := clientmodels.NewDefaultConfig()
		config.Host = "http://localhost:1"
		config.HeartbeatEnabled = enabled

		client, err := NewGrayskullClient(&MockAuthProvider{}, config, metrics.NewPrometheusRecorder(prometheus.NewRegistry()))
		require.NoError(t, err)
		assert.Equal(t, enabled, client.(*GrayskullClientImpl).heartbeat != nil)
		assert.NoError(t, client.Close())
	}
}
