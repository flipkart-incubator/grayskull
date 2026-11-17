package heartbeat

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// fixture is the shared heartbeat payload in clients/testdata/heartbeat.
var fixture = filepath.Join("..", "..", "..", "..", "..", "testdata", "heartbeat", "client_heartbeat_v1.json")

func intPtr(v int) *int { return &v }

func TestRequest_SerializesToSharedFixture(t *testing.T) {
	want, err := os.ReadFile(fixture)
	require.NoError(t, err, "shared fixture missing")

	got, err := json.Marshal(Request{Secrets: []HeldSecret{
		{ProjectID: "p", SecretName: "s", Version: 5, RefreshHookAppliedVersion: intPtr(5)},
		{ProjectID: "p", SecretName: "hook-pending", Version: 3},
		{ProjectID: "p", SecretName: "no-hook", Version: 2},
	}})
	require.NoError(t, err)
	assert.JSONEq(t, string(want), string(got))
}

func TestRequest_FixtureRoundTrips(t *testing.T) {
	want, err := os.ReadFile(fixture)
	require.NoError(t, err)

	var req Request
	require.NoError(t, json.Unmarshal(want, &req))
	got, err := json.Marshal(req)
	require.NoError(t, err)
	assert.JSONEq(t, string(want), string(got), "every fixture field maps to a Request field")
}

func TestHeldSecret_NoHookOmitsField(t *testing.T) {
	got, err := json.Marshal(HeldSecret{ProjectID: "p", SecretName: "s", Version: 1})
	require.NoError(t, err)
	assert.JSONEq(t, `{"projectId":"p","secretName":"s","version":1}`, string(got))
}
