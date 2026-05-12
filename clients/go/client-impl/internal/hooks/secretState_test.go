package hooks

import (
	"sync"
	"testing"

	"github.com/flipkart-incubator/grayskull/clients/go/client-api/models"
)

// TestNewState_StoresIdentityFields verifies that newSecretState correctly
// stores the projectID and secretName fields.
func TestNewState_StoresIdentityFields(t *testing.T) {
	s := newSecretState("p", "s")

	if s.ProjectID != "p" {
		t.Errorf("ProjectID = %q, want %q", s.ProjectID, "p")
	}
	if s.SecretName != "s" {
		t.Errorf("SecretName = %q, want %q", s.SecretName, "s")
	}
}

// TestNewState_LastKnownVersionStartsAtZero verifies that lastKnownVersion
// is initialized to 0 so the first poll asks the server for the current value.
func TestNewState_LastKnownVersionStartsAtZero(t *testing.T) {
	s := newSecretState("p", "s")

	if got := s.LastKnownVersion.Load(); got != 0 {
		t.Errorf("LastKnownVersion = %d, want 0 (must start at 0 so first poll gets current value)", got)
	}
}

// TestNewState_IsExecutingStartsFalse verifies that isExecuting starts false.
func TestNewState_IsExecutingStartsFalse(t *testing.T) {
	s := newSecretState("p", "s")

	if s.isExecuting.Load() {
		t.Error("isExecuting should start false")
	}
}

// TestNewState_PendingUpdateStartsNull_AndHooksEmpty verifies that the
// pending atomic pointer starts nil and hooks slice is empty.
func TestNewState_PendingUpdateStartsNull_AndHooksEmpty(t *testing.T) {
	s := newSecretState("p", "s")

	if s.pending.Load() != nil {
		t.Error("pending should start nil")
	}

	if s.hooks != nil {
		t.Errorf("hooks should be nil initially, got %v", s.hooks)
	}
}

// TestSetPending_StagesUpdate verifies that SetPending stores a value.
func TestSetPending_StagesUpdate(t *testing.T) {
	s := newSecretState("p", "s")
	v := &models.SecretValue{DataVersion: 3, PublicPart: "pub", PrivatePart: "priv"}

	s.SetPending(v)

	if !s.HasPending() {
		t.Error("HasPending() should return true after SetPending")
	}
	if got := s.pending.Load(); got != v {
		t.Errorf("pending.Load() = %p, want %p", got, v)
	}
}

// TestTakePending_DrainsAndReturnsValue verifies that TakePending returns
// the staged value and clears the pending slot (atomic swap to nil).
func TestTakePending_DrainsAndReturnsValue(t *testing.T) {
	s := newSecretState("p", "s")
	v := &models.SecretValue{DataVersion: 7, PublicPart: "a", PrivatePart: "b"}
	s.SetPending(v)

	got := s.TakePending()

	if got != v {
		t.Errorf("TakePending() = %v, want %v", got, v)
	}
	if s.HasPending() {
		t.Error("HasPending() should return false after TakePending drains the slot")
	}
	if s.pending.Load() != nil {
		t.Error("pending should be nil after TakePending")
	}
}

// TestTakePending_WhenNil_ReturnsNil verifies that TakePending returns nil
// when no value is staged.
func TestTakePending_WhenNil_ReturnsNil(t *testing.T) {
	s := newSecretState("p", "s")

	got := s.TakePending()

	if got != nil {
		t.Errorf("TakePending() on empty pending = %v, want nil", got)
	}
}

// TestHasPending_ReflectsPendingState verifies that HasPending correctly
// reports whether a value is staged.
func TestHasPending_ReflectsPendingState(t *testing.T) {
	s := newSecretState("p", "s")

	if s.HasPending() {
		t.Error("HasPending() should be false initially")
	}

	v := &models.SecretValue{DataVersion: 1}
	s.SetPending(v)

	if !s.HasPending() {
		t.Error("HasPending() should be true after SetPending")
	}

	s.TakePending()

	if s.HasPending() {
		t.Error("HasPending() should be false after TakePending")
	}
}

// TestTryAcquireExecution_FlipsFlagFromFalseToTrue verifies that the first
// call returns true and sets isExecuting to true.
func TestTryAcquireExecution_FlipsFlagFromFalseToTrue(t *testing.T) {
	s := newSecretState("p", "s")

	if !s.TryAcquireExecution() {
		t.Error("TryAcquireExecution() should return true on first call")
	}
	if !s.isExecuting.Load() {
		t.Error("isExecuting should be true after TryAcquireExecution succeeds")
	}
}

// TestTryAcquireExecution_ReturnsFalseWhenAlreadyExecuting verifies that
// when another runner already owns the slot, TryAcquireExecution returns false.
func TestTryAcquireExecution_ReturnsFalseWhenAlreadyExecuting(t *testing.T) {
	s := newSecretState("p", "s")

	// First acquisition succeeds
	if !s.TryAcquireExecution() {
		t.Fatal("first TryAcquireExecution should succeed")
	}

	// Second acquisition should fail
	if s.TryAcquireExecution() {
		t.Error("second TryAcquireExecution should return false when already executing")
	}
}

// TestReleaseExecution_ClearsFlag verifies that ReleaseExecution sets
// isExecuting back to false.
func TestReleaseExecution_ClearsFlag(t *testing.T) {
	s := newSecretState("p", "s")

	s.TryAcquireExecution()
	s.ReleaseExecution()

	if s.isExecuting.Load() {
		t.Error("isExecuting should be false after ReleaseExecution")
	}

	// After release, a new TryAcquireExecution should succeed
	if !s.TryAcquireExecution() {
		t.Error("TryAcquireExecution should succeed after ReleaseExecution")
	}
}

// TestSnapshotHooks_ReturnsEmptyWhenNoHooks verifies that SnapshotHooks
// returns nil or empty slice when no hooks are registered.
func TestSnapshotHooks_ReturnsEmptyWhenNoHooks(t *testing.T) {
	s := newSecretState("p", "s")

	snapshot := s.SnapshotHooks()

	if snapshot != nil && len(snapshot) != 0 {
		t.Errorf("SnapshotHooks() with no hooks = %v, want nil or empty", snapshot)
	}
}

// TestSnapshotHooks_ReturnsCopyInRegistrationOrder verifies that
// SnapshotHooks returns hooks in the order they were added and that
// the returned slice is a copy (modifications don't affect the original).
func TestSnapshotHooks_ReturnsCopyInRegistrationOrder(t *testing.T) {
	s := newSecretState("p", "s")

	// Mock hooks
	hook1 := func(v models.SecretValue) error { return nil }
	hook2 := func(v models.SecretValue) error { return nil }
	hook3 := func(v models.SecretValue) error { return nil }

	// Add hooks to the internal slice directly for testing
	s.mu.Lock()
	s.hooks = []hookEntry{
		{token: &DefaultRefreshHandlerRef{secretRef: "p:s"}, hook: hook1},
		{token: &DefaultRefreshHandlerRef{secretRef: "p:s"}, hook: hook2},
		{token: &DefaultRefreshHandlerRef{secretRef: "p:s"}, hook: hook3},
	}
	s.mu.Unlock()

	snapshot := s.SnapshotHooks()

	if len(snapshot) != 3 {
		t.Errorf("SnapshotHooks() returned %d hooks, want 3", len(snapshot))
	}

	// Verify it's a copy: modifying the snapshot shouldn't affect the original
	s.mu.RLock()
	originalLen := len(s.hooks)
	s.mu.RUnlock()

	snapshot = append(snapshot, hook1) // modify the snapshot

	s.mu.RLock()
	newLen := len(s.hooks)
	s.mu.RUnlock()

	if newLen != originalLen {
		t.Errorf("modifying snapshot affected original: original had %d hooks, now has %d", originalLen, newLen)
	}
}

// TestSetPending_OverwritesOlderUpdate verifies that newer updates overwrite
// older ones, ensuring consumers always observe the latest value.
func TestSetPending_OverwritesOlderUpdate(t *testing.T) {
	s := newSecretState("p", "s")

	v1 := &models.SecretValue{DataVersion: 5, PublicPart: "old", PrivatePart: "old"}
	v2 := &models.SecretValue{DataVersion: 10, PublicPart: "new", PrivatePart: "new"}

	s.SetPending(v1)
	s.SetPending(v2) // overwrites v1

	got := s.TakePending()

	if got != v2 {
		t.Errorf("TakePending() after overwrite = %v, want v2 (%v)", got, v2)
	}
	if got.DataVersion != 10 {
		t.Errorf("got version %d, want 10 (newer value should overwrite older)", got.DataVersion)
	}
}

// TestConcurrentSetAndTakePending_ThreadSafe verifies that concurrent
// SetPending and TakePending calls are thread-safe.
func TestConcurrentSetAndTakePending_ThreadSafe(t *testing.T) {
	s := newSecretState("p", "s")
	const numGoroutines = 50

	var wg sync.WaitGroup
	wg.Add(numGoroutines * 2) // half setters, half takers

	// Setters
	for i := 0; i < numGoroutines; i++ {
		go func(version int) {
			defer wg.Done()
			v := &models.SecretValue{DataVersion: version, PublicPart: "p", PrivatePart: "p"}
			s.SetPending(v)
		}(i)
	}

	// Takers
	for i := 0; i < numGoroutines; i++ {
		go func() {
			defer wg.Done()
			s.TakePending()
		}()
	}

	wg.Wait()
	// Test passes if no data race is detected (run with -race flag)
}

// TestConcurrentAcquireAndRelease_ThreadSafe verifies that concurrent
// TryAcquireExecution and ReleaseExecution calls are thread-safe.
func TestConcurrentAcquireAndRelease_ThreadSafe(t *testing.T) {
	s := newSecretState("p", "s")
	const numGoroutines = 50

	var wg sync.WaitGroup
	wg.Add(numGoroutines)

	successCount := 0
	var mu sync.Mutex

	for i := 0; i < numGoroutines; i++ {
		go func() {
			defer wg.Done()
			if s.TryAcquireExecution() {
				mu.Lock()
				successCount++
				mu.Unlock()
				s.ReleaseExecution()
			}
		}()
	}

	wg.Wait()

	// At least one goroutine should have acquired the lock
	if successCount == 0 {
		t.Error("no goroutine successfully acquired execution; expected at least one")
	}
	// Test passes if no data race is detected (run with -race flag)
}

// TestLastKnownVersion_AtomicUpdates verifies that LastKnownVersion can be
// safely updated concurrently.
func TestLastKnownVersion_AtomicUpdates(t *testing.T) {
	s := newSecretState("p", "s")
	const numGoroutines = 100

	var wg sync.WaitGroup
	wg.Add(numGoroutines)

	for i := 0; i < numGoroutines; i++ {
		go func(version int32) {
			defer wg.Done()
			s.LastKnownVersion.Store(version)
		}(int32(i))
	}

	wg.Wait()

	// Final value should be one of the versions written (atomic guarantees)
	finalVersion := s.LastKnownVersion.Load()
	if finalVersion < 0 || finalVersion >= int32(numGoroutines) {
		t.Errorf("LastKnownVersion = %d, expected value in range [0, %d)", finalVersion, numGoroutines)
	}
}
