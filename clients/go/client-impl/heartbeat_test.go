package client_impl

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/flipkart-incubator/grayskull/clients/go/client-api/models"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/internal/models/heartbeat"
	clientmodels "github.com/flipkart-incubator/grayskull/clients/go/client-impl/models"
	"github.com/prometheus/client_golang/prometheus"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"
	"github.com/stretchr/testify/require"

	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/metrics"
)

func noopHook(context.Context, models.SecretValue) error { return nil }

func entryFor(t *testing.T, c *GrayskullClientImpl, secretName string) heartbeat.HeldSecret {
	t.Helper()
	for _, e := range c.ledger.Snapshot() {
		if e.SecretName == secretName {
			return e
		}
	}
	t.Fatalf("no ledger entry for %s", secretName)
	return heartbeat.HeldSecret{}
}

func TestRegisterRefreshHook_SeedsFromLedgerAndCountsHook(t *testing.T) {
	c := NewGrayskullClientForTesting("http://gs", &MockAuthProvider{}, clientmodels.NewDefaultConfig(), &MockGrayskullHTTPClient{}, nil)
	c.ledger.RecordFetch("proj", "db-password", 7)

	ref, err := c.RegisterRefreshHook(context.Background(), "proj:db-password", noopHook)
	require.NoError(t, err)
	assert.EqualValues(t, 7, c.registry.Get("proj:db-password").LastKnownVersion.Load(), "seeded from the ledger")
	assert.NotNil(t, entryFor(t, c, "db-password").RefreshHookAppliedVersion)
	assert.Equal(t, "proj:db-password", ref.GetSecretRef())
	assert.True(t, ref.IsActive())

	ref.Unregister()
	ref.Unregister() // idempotent: must not drive the count below zero
	assert.False(t, ref.IsActive())
	assert.Nil(t, c.registry.Get("proj:db-password"))
	assert.Nil(t, entryFor(t, c, "db-password").RefreshHookAppliedVersion, "the ledger entry is kept, without a hook")
}

func TestRegisterRefreshHook_HookOnlySecretIsInLedger(t *testing.T) {
	c := NewGrayskullClientForTesting("http://gs", &MockAuthProvider{}, clientmodels.NewDefaultConfig(), &MockGrayskullHTTPClient{}, nil)
	_, err := c.RegisterRefreshHook(context.Background(), "proj:api:key", noopHook) // secret name with a colon
	require.NoError(t, err)

	e := entryFor(t, c, "api:key")
	assert.Equal(t, "proj", e.ProjectID)
	require.NotNil(t, e.RefreshHookAppliedVersion)
	assert.Zero(t, *e.RefreshHookAppliedVersion, "hook registered, not yet applied")
	assert.Zero(t, e.Version)
}

func TestGetSecret_FailureIsReturnedUnchangedAndNotRecorded(t *testing.T) {
	httpClient := &MockGrayskullHTTPClient{}
	httpClient.On("DoGetWithRetry", mock.Anything, mock.Anything, mock.Anything).Return(404, errors.New("not found"))
	c := NewGrayskullClientForTesting("http://gs", &MockAuthProvider{}, clientmodels.NewDefaultConfig(), httpClient,
		metrics.NewPrometheusRecorder(prometheus.NewRegistry()))

	_, err := c.GetSecret(context.Background(), "proj:missing")
	require.Error(t, err)

	assert.Empty(t, c.ledger.Snapshot(), "only successful fetches are recorded")
}

func TestGetSecret_MalformedRefCreatesNoEntry(t *testing.T) {
	c := NewGrayskullClientForTesting("http://gs", &MockAuthProvider{}, clientmodels.NewDefaultConfig(), &MockGrayskullHTTPClient{},
		metrics.NewPrometheusRecorder(prometheus.NewRegistry()))
	for _, ref := range []string{"", "nocolon", "proj:", ":name"} {
		_, err := c.GetSecret(context.Background(), ref)
		require.Error(t, err, ref)
	}
	assert.Empty(t, c.ledger.Snapshot())
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

type staticAuth struct{}

func (staticAuth) GetAuthHeader() (string, error) { return "Bearer test-token", nil }

// heartbeatServer serves secret data and records POST /v1/client/heartbeat bodies.
type heartbeatServer struct {
	mu     sync.Mutex
	bodies []heartbeat.Request
	status int
}

func (s *heartbeatServer) handler(w http.ResponseWriter, r *http.Request) {
	switch {
	case r.Method == http.MethodGet && strings.HasSuffix(r.URL.Path, "/missing/data"):
		w.WriteHeader(http.StatusNotFound)
	case r.Method == http.MethodGet && strings.HasSuffix(r.URL.Path, "/data"):
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"data":{"dataVersion":7,"publicPart":"pub","privatePart":"priv"},"message":"ok"}`)
	case r.Method == http.MethodPost && r.URL.Path == "/v1/client/heartbeat":
		var req heartbeat.Request
		_ = json.NewDecoder(r.Body).Decode(&req)
		s.mu.Lock()
		s.bodies = append(s.bodies, req)
		status := s.status
		s.mu.Unlock()
		if status == 0 {
			status = http.StatusNoContent
		}
		w.WriteHeader(status)
	case r.Method == http.MethodPost && r.URL.Path == "/v1/secrets/batch":
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"data":{"updatedCount":0,"updatedSecrets":[]}}`)
	default:
		w.WriteHeader(http.StatusNotFound)
	}
}

func (s *heartbeatServer) received() []heartbeat.Request {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]heartbeat.Request(nil), s.bodies...)
}

func newHeartbeatTestClient(t *testing.T, srv *heartbeatServer) *GrayskullClientImpl {
	t.Helper()
	ts := httptest.NewServer(http.HandlerFunc(srv.handler))
	t.Cleanup(ts.Close)
	config := clientmodels.NewDefaultConfig()
	config.Host = ts.URL
	config.MaxRetries = 1
	config.HeartbeatIntervalSeconds = 1
	client, err := NewGrayskullClient(staticAuth{}, config, metrics.NewPrometheusRecorder(prometheus.NewRegistry()))
	require.NoError(t, err)
	t.Cleanup(func() { _ = client.Close() })
	return client.(*GrayskullClientImpl)
}

func TestHeartbeat_EndToEnd_ReportsFetchedHookedAndFailedSecrets(t *testing.T) {
	srv := &heartbeatServer{}
	c := newHeartbeatTestClient(t, srv)

	_, err := c.GetSecret(context.Background(), "proj:db-password")
	require.NoError(t, err)
	_, err = c.RegisterRefreshHook(context.Background(), "proj:db-password", noopHook)
	require.NoError(t, err)
	_, err = c.RegisterRefreshHook(context.Background(), "proj:hook-only", noopHook)
	require.NoError(t, err)
	_, err = c.GetSecret(context.Background(), "proj:missing")
	require.Error(t, err)

	require.Eventually(t, func() bool { return len(srv.received()) > 0 }, 5*time.Second, 20*time.Millisecond)
	body := srv.received()[0]

	bySecret := map[string]heartbeat.HeldSecret{}
	for _, s := range body.Secrets {
		bySecret[s.SecretName] = s
	}
	require.Len(t, bySecret, 2, "a failed fetch adds no entry")
	zero := 0
	assert.Equal(t, heartbeat.HeldSecret{ProjectID: "proj", SecretName: "db-password", Version: 7,
		RefreshHookAppliedVersion: &zero}, bySecret["db-password"])
	assert.Equal(t, heartbeat.HeldSecret{ProjectID: "proj", SecretName: "hook-only",
		RefreshHookAppliedVersion: &zero}, bySecret["hook-only"])
}

func TestHeartbeat_EndToEnd_UnsupportedServerNeverReachesCaller(t *testing.T) {
	srv := &heartbeatServer{status: http.StatusNotFound}
	c := newHeartbeatTestClient(t, srv)

	_, err := c.GetSecret(context.Background(), "proj:db-password")
	require.NoError(t, err)
	require.Eventually(t, func() bool { return len(srv.received()) > 0 }, 5*time.Second, 20*time.Millisecond)

	v, err := c.GetSecret(context.Background(), "proj:db-password")
	require.NoError(t, err)
	assert.Equal(t, 7, v.DataVersion)
}
