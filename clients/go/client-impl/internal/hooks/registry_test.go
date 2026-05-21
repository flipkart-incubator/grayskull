package hooks

import (
	"context"
	"sync"
	"testing"

	"github.com/flipkart-incubator/grayskull/clients/go/client-api/models"
)

func TestNewRegistry_IsEmpty(t *testing.T) {
	r := NewRegistry()

	snapshot := r.Snapshot()
	if len(snapshot) != 0 {
		t.Errorf("Snapshot() = %v, want nil or empty for new registry", snapshot)
	}
}

// First registration creates state and seeds LastKnownVersion.
func TestRegister_CreatesNewState_SeedsInitialVersion(t *testing.T) {
	r := NewRegistry()
	hook := func(_ context.Context, _ models.SecretValue) error { return nil }

	ref := r.Register("proj", "sec", hook, 7)

	if ref == nil {
		t.Fatal("Register should return a non-nil RefreshHandlerRef")
	}
	if ref.GetSecretRef() != "proj:sec" {
		t.Errorf("GetSecretRef() = %q, want %q", ref.GetSecretRef(), "proj:sec")
	}

	state := r.Get("proj:sec")
	if state == nil {
		t.Fatal("Get should return the newly created state")
	}
	if got := state.LastKnownVersion.Load(); got != 7 {
		t.Errorf("LastKnownVersion = %d, want 7 (initial seed)", got)
	}
}

// Subsequent registrations must not overwrite an existing LastKnownVersion.
func TestRegister_SubsequentRegistration_LeavesVersionIntact(t *testing.T) {
	r := NewRegistry()
	hook1 := func(_ context.Context, _ models.SecretValue) error { return nil }
	hook2 := func(_ context.Context, _ models.SecretValue) error { return nil }

	r.Register("proj", "sec", hook1, 8)
	r.Register("proj", "sec", hook2, 99)

	state := r.Get("proj:sec")
	if state == nil {
		t.Fatal("state should exist after registrations")
	}
	if got := state.LastKnownVersion.Load(); got != 8 {
		t.Errorf("LastKnownVersion = %d, want 8 (second registration must not overwrite)", got)
	}
}

func TestRegister_ReturnsUniqueHandles(t *testing.T) {
	r := NewRegistry()
	hook1 := func(_ context.Context, _ models.SecretValue) error { return nil }
	hook2 := func(_ context.Context, _ models.SecretValue) error { return nil }

	ref1 := r.Register("proj", "sec", hook1, 0)
	ref2 := r.Register("proj", "sec", hook2, 0)

	if ref1 == ref2 {
		t.Error("Register should return distinct handles for each registration")
	}
}

// Unregistering one hook keeps the state if others remain.
func TestUnregister_RemovesEntry_KeepsStateIfOtherHooksExist(t *testing.T) {
	r := NewRegistry()
	hook1 := func(_ context.Context, _ models.SecretValue) error { return nil }
	hook2 := func(_ context.Context, _ models.SecretValue) error { return nil }

	ref1 := r.Register("proj", "sec", hook1, 0)
	r.Register("proj", "sec", hook2, 0)

	ref1.Unregister()

	state := r.Get("proj:sec")
	if state == nil {
		t.Error("state should still exist after unregistering one of two hooks")
	}

	hooks := state.SnapshotHooks()
	if len(hooks) != 1 {
		t.Errorf("SnapshotHooks() returned %d hooks, want 1 after removing one of two", len(hooks))
	}
}

// Removing the last hook drops the SecretState.
func TestUnregister_LastHook_RemovesState(t *testing.T) {
	r := NewRegistry()
	hook := func(_ context.Context, _ models.SecretValue) error { return nil }

	ref := r.Register("proj", "sec", hook, 0)
	ref.Unregister()

	state := r.Get("proj:sec")
	if state != nil {
		t.Error("state should be removed after unregistering the last hook")
	}
	if got := len(r.Snapshot()); got != 0 {
		t.Errorf("len(Snapshot()) = %d, want 0 after removing last hook", got)
	}
}

// Repeated Unregister is a no-op.
func TestUnregister_IdempotentAcrossMultipleCalls(t *testing.T) {
	r := NewRegistry()
	hook := func(_ context.Context, _ models.SecretValue) error { return nil }

	ref := r.Register("proj", "sec", hook, 0)
	ref.Unregister()
	ref.Unregister()

	if r.Get("proj:sec") != nil {
		t.Error("state should remain removed after redundant Unregister calls")
	}
}

func TestGet_ReturnsNilForNonexistentSecret(t *testing.T) {
	r := NewRegistry()

	state := r.Get("nonexistent:secret")

	if state != nil {
		t.Errorf("Get() for nonexistent secret = %v, want nil", state)
	}
}

func TestSnapshot_ReturnsAllStates(t *testing.T) {
	r := NewRegistry()
	hook := func(_ context.Context, _ models.SecretValue) error { return nil }

	r.Register("proj1", "sec1", hook, 0)
	r.Register("proj2", "sec2", hook, 0)
	r.Register("proj3", "sec3", hook, 0)

	snapshot := r.Snapshot()

	if len(snapshot) != 3 {
		t.Errorf("Snapshot() returned %d states, want 3", len(snapshot))
	}
}

func TestSnapshot_EmptyRegistry_ReturnsNilOrEmpty(t *testing.T) {
	r := NewRegistry()

	snapshot := r.Snapshot()

	if len(snapshot) != 0 {
		t.Errorf("Snapshot() on empty registry = %v, want nil or empty", snapshot)
	}
}

// Snapshot length tracks unique secrets, not hook count.
func TestSnapshot_ReflectsRegistrySize(t *testing.T) {
	r := NewRegistry()
	hook := func(_ context.Context, _ models.SecretValue) error { return nil }

	if got := len(r.Snapshot()); got != 0 {
		t.Errorf("initial len(Snapshot()) = %d, want 0", got)
	}

	r.Register("proj", "sec1", hook, 0)
	if got := len(r.Snapshot()); got != 1 {
		t.Errorf("len(Snapshot()) after 1 registration = %d, want 1", got)
	}

	r.Register("proj", "sec2", hook, 0)
	if got := len(r.Snapshot()); got != 2 {
		t.Errorf("len(Snapshot()) after 2 registrations = %d, want 2", got)
	}

	r.Register("proj", "sec1", hook, 0)
	if got := len(r.Snapshot()); got != 2 {
		t.Errorf("len(Snapshot()) after adding second hook to same secret = %d, want 2", got)
	}
}

// Race check; run with -race.
func TestConcurrentRegisterAndUnregister_ThreadSafe(t *testing.T) {
	r := NewRegistry()
	const numGoroutines = 50

	hook := func(_ context.Context, _ models.SecretValue) error { return nil }

	var wg sync.WaitGroup
	wg.Add(numGoroutines)
	for i := 0; i < numGoroutines; i++ {
		go func() {
			defer wg.Done()
			ref := r.Register("proj", "sec", hook, 0)
			ref.Unregister()
		}()
	}

	wg.Wait()
}

// Race check; run with -race.
func TestConcurrentGetAndSnapshot_ThreadSafe(t *testing.T) {
	r := NewRegistry()
	const numGoroutines = 50

	hook := func(_ context.Context, _ models.SecretValue) error { return nil }

	var wg sync.WaitGroup
	wg.Add(numGoroutines * 3)

	for i := 0; i < numGoroutines; i++ {
		go func() {
			defer wg.Done()
			r.Register("proj", "sec", hook, 0)
		}()
	}
	for i := 0; i < numGoroutines; i++ {
		go func() {
			defer wg.Done()
			r.Get("proj:sec")
		}()
	}
	for i := 0; i < numGoroutines; i++ {
		go func() {
			defer wg.Done()
			r.Snapshot()
		}()
	}

	wg.Wait()
}

func TestRegister_MultipleSecretsInParallel_AllTracked(t *testing.T) {
	r := NewRegistry()
	const numSecrets = 20

	hook := func(_ context.Context, _ models.SecretValue) error { return nil }

	var wg sync.WaitGroup
	wg.Add(numSecrets)

	for i := 0; i < numSecrets; i++ {
		secretNum := i
		go func() {
			defer wg.Done()
			projectID := "proj"
			secretName := string(rune('a' + secretNum))
			r.Register(projectID, secretName, hook, 0)
		}()
	}

	wg.Wait()

	if got := len(r.Snapshot()); got != numSecrets {
		t.Errorf("len(Snapshot()) = %d, want %d after parallel registrations", got, numSecrets)
	}

	snapshot := r.Snapshot()
	if len(snapshot) != numSecrets {
		t.Errorf("Snapshot() returned %d states, want %d", len(snapshot), numSecrets)
	}
}

// Unregister on an already-removed entry is safe.
func TestUnregister_NonexistentSecretRef_NoOp(t *testing.T) {
	r := NewRegistry()
	hook := func(_ context.Context, _ models.SecretValue) error { return nil }

	ref := r.Register("proj", "sec", hook, 0)
	ref.Unregister()
	ref.Unregister()

	if got := len(r.Snapshot()); got != 0 {
		t.Errorf("len(Snapshot()) = %d, want 0 after redundant unregister", got)
	}
}

// secretRef key is "projectID:secretName".
func TestRegister_CorrectSecretRefFormat(t *testing.T) {
	r := NewRegistry()
	hook := func(_ context.Context, _ models.SecretValue) error { return nil }

	ref := r.Register("my-project", "my-secret", hook, 0)

	if got := ref.GetSecretRef(); got != "my-project:my-secret" {
		t.Errorf("GetSecretRef() = %q, want %q", got, "my-project:my-secret")
	}

	state := r.Get("my-project:my-secret")
	if state == nil {
		t.Error("state should be retrievable with projectID:secretName key")
	}
}

// Secret names with colons are preserved in the key as-is.
func TestRegister_WithColonInSecretName(t *testing.T) {
	r := NewRegistry()
	hook := func(_ context.Context, _ models.SecretValue) error { return nil }

	ref := r.Register("proj", "secret:with:colons", hook, 0)

	expectedRef := "proj:secret:with:colons"
	if got := ref.GetSecretRef(); got != expectedRef {
		t.Errorf("GetSecretRef() = %q, want %q", got, expectedRef)
	}

	state := r.Get(expectedRef)
	if state == nil {
		t.Errorf("state should be retrievable with key %q", expectedRef)
	}
}

func TestSnapshot_DoesNotReturnNilStates(t *testing.T) {
	r := NewRegistry()
	hook := func(_ context.Context, _ models.SecretValue) error { return nil }

	r.Register("proj1", "sec1", hook, 0)
	r.Register("proj2", "sec2", hook, 0)

	snapshot := r.Snapshot()

	for i, state := range snapshot {
		if state == nil {
			t.Errorf("Snapshot()[%d] is nil; all entries should be non-nil", i)
		}
	}
}
