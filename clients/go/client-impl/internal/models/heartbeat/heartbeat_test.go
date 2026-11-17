package heartbeat

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// fixture is shared with the Java SDK (clients/testdata/heartbeat), so both SDKs send an identical payload.
var fixture = filepath.Join("..", "..", "..", "..", "..", "testdata", "heartbeat", "client_heartbeat_v1.json")

func TestRequest_SerializesToSharedFixture(t *testing.T) {
	want, err := os.ReadFile(fixture)
	require.NoError(t, err, "shared fixture missing")

	lastErr := "java.lang.IllegalStateException: cannot reload pool"
	got, err := json.Marshal(Request{
		SchemaVersion: SchemaVersion,
		Trigger:       TriggerPeriodic,
		SDK:           SDK{Language: "java", Version: "0.3.0"},
		SentAt:        1790000000000,
		Secrets: []HeldSecret{
			{ProjectID: "p", SecretName: "s", Version: 5, RefreshHook: true, GetCount: 120, ErrorCount: 2,
				FirstAccessAt: 1789990000000, LastAccessAt: 1789999990000, HookAppliedVersion: 5},
			{ProjectID: "p", SecretName: "hook-only", RefreshHook: true, HookFailureCount: 1, LastHookError: &lastErr},
		},
	})
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
