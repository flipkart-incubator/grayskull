package internal

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/flipkart-incubator/grayskull/clients/go/client-api/models"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/internal/hooks"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/internal/models/batch"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/internal/models/response"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/metrics"
	"github.com/prometheus/client_golang/prometheus"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func newLedgerPoller(client GrayskullHTTPClientInterface, registry *hooks.Registry, ledger *UsageLedger) *Poller {
	return NewPoller(PollerConfig{
		BaseURL:         "https://test.example.com",
		HTTPClient:      client,
		Registry:        registry,
		Interval:        60 * time.Second,
		MetricsRecorder: metrics.NewPrometheusRecorder(prometheus.NewRegistry()),
		Ledger:          ledger,
	})
}

func TestPollOnce_RecordsVersionSeenAndHookAppliedInLedger(t *testing.T) {
	client := &mockHTTPClient{}
	client.addPostResponse(200, response.Response[batch.BatchGetSecretsResponse]{
		Data: batch.BatchGetSecretsResponse{UpdatedCount: 1, UpdatedSecrets: []batch.UpdatedSecret{
			{ProjectID: "acme", SecretName: "db", DataVersion: 3, PublicPart: "pub"},
		}},
	}, nil)
	registry := hooks.NewRegistry()
	registry.Register("acme", "db", func(context.Context, models.SecretValue) error { return nil }, 0)
	ledger := &UsageLedger{}
	ledger.SetHookRegistered("acme", "db", true)
	p := newLedgerPoller(client, registry, ledger)
	defer p.Close()
	p.Start()

	p.PollOnce(context.Background())

	require.Eventually(t, func() bool {
		snap := ledger.Snapshot()
		return len(snap) == 1 && snap[0].RefreshHookAppliedVersion != nil && *snap[0].RefreshHookAppliedVersion == 3
	}, 2*time.Second, 10*time.Millisecond)
	e := only(t, ledger)
	assert.Equal(t, 3, e.Version, "version seen by the poller is recorded")
}

func TestPoller_WithoutLedgerStillDelivers(t *testing.T) {
	p := newLedgerPoller(&mockHTTPClient{}, hooks.NewRegistry(), nil)
	defer p.Close()
	assert.NotPanics(t, func() {
		p.invokeHookSafe(context.Background(), "acme:db", func(context.Context, models.SecretValue) error { return nil },
			models.SecretValue{DataVersion: 1})
	})
}

func runDelivery(t *testing.T, ledger *UsageLedger, version int, hookFns ...func(context.Context, models.SecretValue) error) {
	t.Helper()
	registry := hooks.NewRegistry()
	for _, h := range hookFns {
		registry.Register("acme", "db", h, 0)
	}
	ledger.SetHookRegistered("acme", "db", true)
	p := newLedgerPoller(&mockHTTPClient{}, registry, ledger)
	defer p.Close()
	state := registry.Get("acme:db")
	state.SetPending(&models.SecretValue{DataVersion: version})
	p.runHooksFor("acme:db", state, "")
}

func TestRunHooksFor_AppliedOnlyWhenEveryHookSucceeds(t *testing.T) {
	ok := func(context.Context, models.SecretValue) error { return nil }
	fails := func(context.Context, models.SecretValue) error { return errors.New("boom") }
	panics := func(context.Context, models.SecretValue) error { panic("nil pool") }

	all := &UsageLedger{}
	runDelivery(t, all, 5, ok, ok)
	require.NotNil(t, only(t, all).RefreshHookAppliedVersion)
	assert.Equal(t, 5, *only(t, all).RefreshHookAppliedVersion)

	for _, bad := range []func(context.Context, models.SecretValue) error{fails, panics} {
		mixed := &UsageLedger{}
		runDelivery(t, mixed, 5, ok, bad)
		e := only(t, mixed)
		assert.Equal(t, 5, e.Version, "version advances before hooks run")
		assert.Equal(t, 0, applied(t, e), "one failing hook means the version was not applied")
	}
}

func TestRunHooksFor_LaterFailureKeepsLastFullySuccessfulVersion(t *testing.T) {
	ledger := &UsageLedger{}
	failFrom5 := func(_ context.Context, v models.SecretValue) error {
		if v.DataVersion >= 5 {
			return errors.New("boom")
		}
		return nil
	}
	runDelivery(t, ledger, 4, failFrom5)
	runDelivery(t, ledger, 5, failFrom5)
	e := only(t, ledger)
	assert.Equal(t, 5, e.Version)
	require.NotNil(t, e.RefreshHookAppliedVersion)
	assert.Equal(t, 4, *e.RefreshHookAppliedVersion)
}
