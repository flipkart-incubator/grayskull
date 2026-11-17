package internal

import (
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/internal/models/heartbeat"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func ledgerAt(millis *int64) *UsageLedger {
	return &UsageLedger{now: func() time.Time { return time.UnixMilli(*millis) }}
}

func only(t *testing.T, l *UsageLedger) heartbeat.HeldSecret {
	t.Helper()
	snap := l.Snapshot()
	require.Len(t, snap, 1)
	return snap[0]
}

func TestUsageLedger_ZeroValueAndEmptySnapshot(t *testing.T) {
	var l UsageLedger
	assert.Empty(t, l.Snapshot())
	assert.Equal(t, 0, l.Version("p", "s"))
	l.RecordGet("p", "s", 1, true)
	assert.Equal(t, 1, l.Version("p", "s"))
}

func TestUsageLedger_NilIsANoOp(t *testing.T) {
	var l *UsageLedger
	assert.NotPanics(t, func() {
		l.RecordGet("p", "s", 1, true)
		l.RecordVersion("p", "s", 2)
		l.RecordHookRegistered("p", "s")
		l.RecordHookUnregistered("p", "s")
		l.RecordHookApplied("p", "s", 2)
		l.RecordHookFailed("p", "s", "boom")
	})
	assert.Nil(t, l.Snapshot())
	assert.Equal(t, 0, l.Version("p", "s"))
}

func TestUsageLedger_RecordGetCountsSuccessAndFailureWithTimestamps(t *testing.T) {
	now := int64(1_000)
	l := ledgerAt(&now)
	l.RecordGet("p", "s", 3, true)
	now = 2_000
	l.RecordGet("p", "s", 0, false)
	now = 3_000
	l.RecordGet("p", "s", 4, true)

	e := only(t, l)
	assert.Equal(t, "p", e.ProjectID)
	assert.Equal(t, "s", e.SecretName)
	assert.EqualValues(t, 2, e.GetCount)
	assert.EqualValues(t, 1, e.ErrorCount)
	assert.EqualValues(t, 1_000, e.FirstAccessAt)
	assert.EqualValues(t, 3_000, e.LastAccessAt)
	assert.Equal(t, 4, e.Version)
	assert.False(t, e.RefreshHook)
}

func TestUsageLedger_FailedGetDoesNotChangeVersion(t *testing.T) {
	var l UsageLedger
	l.RecordGet("p", "s", 0, false)
	assert.Equal(t, 0, l.Version("p", "s"))
	assert.EqualValues(t, 1, only(t, &l).ErrorCount)
}

func TestUsageLedger_VersionIsNewestHeldFromFetchOrPoller(t *testing.T) {
	var l UsageLedger
	l.RecordGet("p", "s", 5, true)
	l.RecordVersion("p", "s", 8)
	l.RecordGet("p", "s", 6, true) // a stale read never lowers the held version
	assert.Equal(t, 8, l.Version("p", "s"))
}

func TestUsageLedger_HookOnlyEntryIsReported(t *testing.T) {
	var l UsageLedger
	l.RecordHookRegistered("p", "s")
	e := only(t, &l)
	assert.True(t, e.RefreshHook)
	assert.Zero(t, e.GetCount)
	assert.Zero(t, e.FirstAccessAt)
}

func TestUsageLedger_HookCountTracksRegisterAndUnregister(t *testing.T) {
	var l UsageLedger
	l.RecordHookRegistered("p", "s")
	l.RecordHookRegistered("p", "s")
	l.RecordHookUnregistered("p", "s")
	assert.True(t, only(t, &l).RefreshHook)
	l.RecordHookUnregistered("p", "s")
	l.RecordHookUnregistered("p", "s") // never below zero
	assert.False(t, only(t, &l).RefreshHook, "entry is kept after the last hook goes")
	l.RecordHookRegistered("p", "s")
	assert.True(t, only(t, &l).RefreshHook)
}

func TestUsageLedger_HookOutcome(t *testing.T) {
	var l UsageLedger
	l.RecordHookApplied("p", "s", 7)
	l.RecordHookFailed("p", "s", "cannot reload pool")
	l.RecordHookApplied("p", "s", 6) // an older delivery never lowers it

	e := only(t, &l)
	assert.Equal(t, 7, e.HookAppliedVersion)
	assert.EqualValues(t, 1, e.HookFailureCount)
	require.NotNil(t, e.LastHookError)
	assert.Equal(t, "cannot reload pool", *e.LastHookError)
}

func TestUsageLedger_HookErrorIsTruncated(t *testing.T) {
	var l UsageLedger
	l.RecordHookFailed("p", "s", strings.Repeat("x", 1_000))
	assert.Len(t, *only(t, &l).LastHookError, maxHookErrorLength)
}

func TestUsageLedger_SnapshotIsACopy(t *testing.T) {
	var l UsageLedger
	l.RecordHookFailed("p", "s", "first")
	snap := l.Snapshot()
	*snap[0].LastHookError = "mutated"
	assert.Equal(t, "first", *only(t, &l).LastHookError)
}

func TestUsageLedger_SecretsAreKeptApart(t *testing.T) {
	var l UsageLedger
	l.RecordGet("p", "a", 1, true)
	l.RecordHookRegistered("p", "b")
	l.RecordGet("q", "a", 9, true)
	assert.Len(t, l.Snapshot(), 3)
	assert.Equal(t, 1, l.Version("p", "a"))
	assert.Equal(t, 0, l.Version("p", "b"))
	assert.Equal(t, 9, l.Version("q", "a"))
}

func TestUsageLedger_ConcurrentRecordingLosesNoCounts(t *testing.T) {
	var l UsageLedger
	const workers, perWorker = 8, 2_000
	var wg sync.WaitGroup
	for w := range workers {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for i := range perWorker {
				l.RecordGet("p", "s", i, w%2 == 0)
				l.Snapshot()
			}
		}()
	}
	wg.Wait()
	e := only(t, &l)
	assert.EqualValues(t, workers/2*perWorker, e.GetCount)
	assert.EqualValues(t, workers/2*perWorker, e.ErrorCount)
	assert.Equal(t, perWorker-1, e.Version)
}
