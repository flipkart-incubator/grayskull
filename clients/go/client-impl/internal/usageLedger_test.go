package internal

import (
	"sync"
	"testing"

	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/internal/models/heartbeat"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func only(t *testing.T, l *UsageLedger) heartbeat.HeldSecret {
	t.Helper()
	snap := l.Snapshot()
	require.Len(t, snap, 1)
	return snap[0]
}

func applied(t *testing.T, e heartbeat.HeldSecret) int {
	t.Helper()
	require.NotNil(t, e.RefreshHookAppliedVersion, "expected a registered hook")
	return *e.RefreshHookAppliedVersion
}

func TestUsageLedger_ZeroValueAndEmptySnapshot(t *testing.T) {
	var l UsageLedger
	assert.Empty(t, l.Snapshot())
	assert.Equal(t, 0, l.Version("p", "s"))
	l.RecordFetch("p", "s", 1)
	assert.Equal(t, 1, l.Version("p", "s"))
}

func TestUsageLedger_NilIsANoOp(t *testing.T) {
	var l *UsageLedger
	assert.NotPanics(t, func() {
		l.RecordFetch("p", "s", 1)
		l.RecordVersion("p", "s", 2)
		l.RecordHookRegistered("p", "s")
		l.RecordHookUnregistered("p", "s")
		l.RecordHookApplied("p", "s", 2)
	})
	assert.Nil(t, l.Snapshot())
	assert.Equal(t, 0, l.Version("p", "s"))
}

func TestUsageLedger_FetchedSecretWithoutHookOmitsAppliedVersion(t *testing.T) {
	var l UsageLedger
	l.RecordFetch("p", "s", 3)
	e := only(t, &l)
	assert.Equal(t, heartbeat.HeldSecret{ProjectID: "p", SecretName: "s", Version: 3}, e)
}

func TestUsageLedger_VersionIsNewestHeldFromFetchOrPoller(t *testing.T) {
	var l UsageLedger
	l.RecordFetch("p", "s", 5)
	l.RecordVersion("p", "s", 8)
	l.RecordFetch("p", "s", 6) // a stale read never lowers the held version
	assert.Equal(t, 8, l.Version("p", "s"))
}

func TestUsageLedger_RegisteredHookReportsZeroUntilItSucceeds(t *testing.T) {
	var l UsageLedger
	l.RecordHookRegistered("p", "s")
	e := only(t, &l)
	assert.Zero(t, e.Version)
	assert.Equal(t, 0, applied(t, e))
}

func TestUsageLedger_HookAppliedAdvancesOnlyForward(t *testing.T) {
	var l UsageLedger
	l.RecordHookRegistered("p", "s")
	l.RecordHookApplied("p", "s", 7)
	l.RecordHookApplied("p", "s", 6) // an older delivery never lowers it
	assert.Equal(t, 7, applied(t, only(t, &l)))
}

func TestUsageLedger_HookCountTracksRegisterAndUnregister(t *testing.T) {
	var l UsageLedger
	l.RecordHookRegistered("p", "s")
	l.RecordHookRegistered("p", "s")
	l.RecordHookApplied("p", "s", 4)
	l.RecordHookUnregistered("p", "s")
	assert.Equal(t, 4, applied(t, only(t, &l)), "one hook is still registered")
	l.RecordHookUnregistered("p", "s")
	l.RecordHookUnregistered("p", "s") // never below zero
	assert.Nil(t, only(t, &l).RefreshHookAppliedVersion, "entry is kept after the last hook goes")
	l.RecordHookRegistered("p", "s")
	assert.Equal(t, 4, applied(t, only(t, &l)))
}

func TestUsageLedger_SnapshotIsACopy(t *testing.T) {
	var l UsageLedger
	l.RecordHookRegistered("p", "s")
	l.RecordHookApplied("p", "s", 2)
	snap := l.Snapshot()
	*snap[0].RefreshHookAppliedVersion = 99
	assert.Equal(t, 2, applied(t, only(t, &l)))
}

func TestUsageLedger_SecretsAreKeptApart(t *testing.T) {
	var l UsageLedger
	l.RecordFetch("p", "a", 1)
	l.RecordHookRegistered("p", "b")
	l.RecordFetch("q", "a", 9)
	assert.Len(t, l.Snapshot(), 3)
	assert.Equal(t, 1, l.Version("p", "a"))
	assert.Equal(t, 0, l.Version("p", "b"))
	assert.Equal(t, 9, l.Version("q", "a"))
}

func TestUsageLedger_ConcurrentRecordingKeepsNewestVersion(t *testing.T) {
	var l UsageLedger
	const workers, perWorker = 8, 2_000
	var wg sync.WaitGroup
	for range workers {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for i := range perWorker {
				l.RecordFetch("p", "s", i)
				l.RecordHookApplied("p", "s", i)
				l.Snapshot()
			}
		}()
	}
	wg.Wait()
	l.RecordHookRegistered("p", "s")
	e := only(t, &l)
	assert.Equal(t, perWorker-1, e.Version)
	assert.Equal(t, perWorker-1, applied(t, e))
}
