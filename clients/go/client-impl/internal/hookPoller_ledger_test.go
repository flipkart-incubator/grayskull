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

func TestInvokeHookSafe_RecordsOutcomeInLedger(t *testing.T) {
	ledger := &UsageLedger{}
	p := newLedgerPoller(&mockHTTPClient{}, hooks.NewRegistry(), ledger)
	defer p.Close()

	ok := func(context.Context, models.SecretValue) error { return nil }
	fails := func(context.Context, models.SecretValue) error { return errors.New("cannot reload pool") }
	panics := func(context.Context, models.SecretValue) error { panic("nil pool") }

	ledger.RecordHookRegistered("acme", "db")
	p.invokeHookSafe(context.Background(), "acme:db", ok, models.SecretValue{DataVersion: 4})
	p.invokeHookSafe(context.Background(), "acme:db", fails, models.SecretValue{DataVersion: 5})
	p.invokeHookSafe(context.Background(), "acme:db", panics, models.SecretValue{DataVersion: 6})

	e := only(t, ledger)
	require.NotNil(t, e.RefreshHookAppliedVersion)
	assert.Equal(t, 4, *e.RefreshHookAppliedVersion, "failed and panicking runs do not advance it")
}

func TestInvokeHookSafe_MalformedSecretRefIsNotRecorded(t *testing.T) {
	ledger := &UsageLedger{}
	p := newLedgerPoller(&mockHTTPClient{}, hooks.NewRegistry(), ledger)
	defer p.Close()

	p.invokeHookSafe(context.Background(), "nocolon", func(context.Context, models.SecretValue) error { return nil },
		models.SecretValue{DataVersion: 1})
	assert.Empty(t, ledger.Snapshot())
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
	ledger.RecordHookRegistered("acme", "db")
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
