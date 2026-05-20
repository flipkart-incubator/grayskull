package hooks

import (
	"context"
	"sync"
	"testing"

	"github.com/flipkart-incubator/grayskull/clients/go/client-api/models"
)

func TestNewState_StoresIdentityFields(t *testing.T) {
	s := newSecretState("p", "s")

	if s.ProjectID != "p" {
		t.Errorf("ProjectID = %q, want %q", s.ProjectID, "p")
	}
	if s.SecretName != "s" {
		t.Errorf("SecretName = %q, want %q", s.SecretName, "s")
	}
}

// LastKnownVersion must start at 0 so the first poll fetches current.
func TestNewState_LastKnownVersionStartsAtZero(t *testing.T) {
	s := newSecretState("p", "s")

	if got := s.LastKnownVersion.Load(); got != 0 {
		t.Errorf("LastKnownVersion = %d, want 0", got)
	}
}

func TestNewState_IsExecutingStartsFalse(t *testing.T) {
	s := newSecretState("p", "s")

	if s.isExecuting.Load() {
		t.Error("isExecuting should start false")
	}
}

func TestNewState_PendingUpdateStartsNull_AndHooksEmpty(t *testing.T) {
	s := newSecretState("p", "s")

	if s.pending.Load() != nil {
		t.Error("pending should start nil")
	}

	if s.hooks != nil {
		t.Errorf("hooks should be nil initially, got %v", s.hooks)
	}
}

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

// Nil is TakePending's "empty" sentinel; SetPending(nil) must be a no-op so
// it can't silently clear a queued update.
func TestSetPending_NilIsNoOp(t *testing.T) {
	t.Run("nil on empty state stays empty", func(t *testing.T) {
		s := newSecretState("p", "s")
		s.SetPending(nil)
		if s.HasPending() {
			t.Error("HasPending() should still be false after SetPending(nil)")
		}
	})

	t.Run("nil does not clear an existing staged value", func(t *testing.T) {
		s := newSecretState("p", "s")
		v := &models.SecretValue{DataVersion: 11, PublicPart: "p", PrivatePart: "q"}
		s.SetPending(v)

		s.SetPending(nil)

		if !s.HasPending() {
			t.Fatal("HasPending() should still be true after SetPending(nil)")
		}
		if got := s.TakePending(); got != v {
			t.Errorf("TakePending() = %v, want %v (nil must not have overwritten)", got, v)
		}
	})
}

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

func TestTakePending_WhenNil_ReturnsNil(t *testing.T) {
	s := newSecretState("p", "s")

	got := s.TakePending()

	if got != nil {
		t.Errorf("TakePending() on empty pending = %v, want nil", got)
	}
}

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

func TestTryAcquireExecution_FlipsFlagFromFalseToTrue(t *testing.T) {
	s := newSecretState("p", "s")

	if !s.TryAcquireExecution() {
		t.Error("TryAcquireExecution() should return true on first call")
	}
	if !s.isExecuting.Load() {
		t.Error("isExecuting should be true after TryAcquireExecution succeeds")
	}
}

func TestTryAcquireExecution_ReturnsFalseWhenAlreadyExecuting(t *testing.T) {
	s := newSecretState("p", "s")

	if !s.TryAcquireExecution() {
		t.Fatal("first TryAcquireExecution should succeed")
	}
	if s.TryAcquireExecution() {
		t.Error("second TryAcquireExecution should return false when already executing")
	}
}

func TestReleaseExecution_ClearsFlag(t *testing.T) {
	s := newSecretState("p", "s")

	s.TryAcquireExecution()
	s.ReleaseExecution()

	if s.isExecuting.Load() {
		t.Error("isExecuting should be false after ReleaseExecution")
	}
	if !s.TryAcquireExecution() {
		t.Error("TryAcquireExecution should succeed after ReleaseExecution")
	}
}

func TestSnapshotHooks_ReturnsEmptyWhenNoHooks(t *testing.T) {
	s := newSecretState("p", "s")

	snapshot := s.SnapshotHooks()

	if snapshot != nil && len(snapshot) != 0 {
		t.Errorf("SnapshotHooks() with no hooks = %v, want nil or empty", snapshot)
	}
}

// SnapshotHooks preserves registration order and returns a copy.
func TestSnapshotHooks_ReturnsCopyInRegistrationOrder(t *testing.T) {
	s := newSecretState("p", "s")

	hook1 := func(_ context.Context, _ models.SecretValue) error { return nil }
	hook2 := func(_ context.Context, _ models.SecretValue) error { return nil }
	hook3 := func(_ context.Context, _ models.SecretValue) error { return nil }

	s.mu.Lock()
	s.hooks = []hookEntry{
		{handlerRef: &DefaultRefreshHandlerRef{secretRef: "p:s"}, hook: hook1},
		{handlerRef: &DefaultRefreshHandlerRef{secretRef: "p:s"}, hook: hook2},
		{handlerRef: &DefaultRefreshHandlerRef{secretRef: "p:s"}, hook: hook3},
	}
	s.mu.Unlock()

	snapshot := s.SnapshotHooks()

	if len(snapshot) != 3 {
		t.Errorf("SnapshotHooks() returned %d hooks, want 3", len(snapshot))
	}

	s.mu.RLock()
	originalLen := len(s.hooks)
	s.mu.RUnlock()

	snapshot = append(snapshot, hook1)

	s.mu.RLock()
	newLen := len(s.hooks)
	s.mu.RUnlock()

	if newLen != originalLen {
		t.Errorf("snapshot append leaked into original: %d -> %d", originalLen, newLen)
	}
}

// Newer SetPending overwrites older (coalescing).
func TestSetPending_OverwritesOlderUpdate(t *testing.T) {
	s := newSecretState("p", "s")

	v1 := &models.SecretValue{DataVersion: 5, PublicPart: "old", PrivatePart: "old"}
	v2 := &models.SecretValue{DataVersion: 10, PublicPart: "new", PrivatePart: "new"}

	s.SetPending(v1)
	s.SetPending(v2)

	got := s.TakePending()

	if got != v2 {
		t.Errorf("TakePending() after overwrite = %v, want v2", got)
	}
	if got.DataVersion != 10 {
		t.Errorf("got version %d, want 10", got.DataVersion)
	}
}

// Race check; run with -race.
func TestConcurrentSetAndTakePending_ThreadSafe(t *testing.T) {
	s := newSecretState("p", "s")
	const numGoroutines = 50

	var wg sync.WaitGroup
	wg.Add(numGoroutines * 2)

	for i := 0; i < numGoroutines; i++ {
		go func(version int) {
			defer wg.Done()
			v := &models.SecretValue{DataVersion: version, PublicPart: "p", PrivatePart: "p"}
			s.SetPending(v)
		}(i)
	}
	for i := 0; i < numGoroutines; i++ {
		go func() {
			defer wg.Done()
			s.TakePending()
		}()
	}
	wg.Wait()
}

// Race check; run with -race.
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

	if successCount == 0 {
		t.Error("no goroutine acquired execution; expected at least one")
	}
}

// Race check; run with -race.
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

	finalVersion := s.LastKnownVersion.Load()
	if finalVersion < 0 || finalVersion >= int32(numGoroutines) {
		t.Errorf("LastKnownVersion = %d, want in [0, %d)", finalVersion, numGoroutines)
	}
}
