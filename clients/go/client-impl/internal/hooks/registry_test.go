package hooks

import (
	"sync"
	"testing"

	"github.com/flipkart-incubator/grayskull/clients/go/client-api/models"
)

// TestNewRegistry_IsEmpty verifies that a newly created registry is empty.
func TestNewRegistry_IsEmpty(t *testing.T) {
	r := NewRegistry()

	snapshot := r.Snapshot()
	if len(snapshot) != 0 {
		t.Errorf("Snapshot() = %v, want nil or empty for new registry", snapshot)
	}
}

// TestRegister_CreatesNewState_SeedsInitialVersion verifies that the first
// registration for a secretRef creates a new SecretState and seeds
// lastKnownVersion from the initialKnownVersion parameter.
func TestRegister_CreatesNewState_SeedsInitialVersion(t *testing.T) {
	r := NewRegistry()
	hook := func(_ models.SecretValue) error { return nil }

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

// TestRegister_SubsequentRegistration_LeavesVersionIntact verifies that
// when a second hook is registered for the same secret, the existing
// lastKnownVersion is preserved (not overwritten by the new initialKnownVersion).
func TestRegister_SubsequentRegistration_LeavesVersionIntact(t *testing.T) {
	r := NewRegistry()
	hook1 := func(_ models.SecretValue) error { return nil }
	hook2 := func(_ models.SecretValue) error { return nil }

	r.Register("proj", "sec", hook1, 8) // seeds lastKnownVersion=8
	r.Register("proj", "sec", hook2, 99) // should NOT update to 99

	state := r.Get("proj:sec")
	if state == nil {
		t.Fatal("state should exist after registrations")
	}
	if got := state.LastKnownVersion.Load(); got != 8 {
		t.Errorf("LastKnownVersion = %d, want 8 (second registration must not overwrite)", got)
	}
}

// TestRegister_ReturnsUniqueHandles verifies that each registration returns
// a distinct RefreshHandlerRef, even for the same secret.
func TestRegister_ReturnsUniqueHandles(t *testing.T) {
	r := NewRegistry()
	hook1 := func(_ models.SecretValue) error { return nil }
	hook2 := func(_ models.SecretValue) error { return nil }

	ref1 := r.Register("proj", "sec", hook1, 0)
	ref2 := r.Register("proj", "sec", hook2, 0)

	if ref1 == ref2 {
		t.Error("Register should return distinct handles for each registration")
	}
}

// TestUnregister_RemovesEntry_KeepsStateIfOtherHooksExist verifies that
// unregistering one hook removes only that hook; the state remains if
// other hooks are still registered.
func TestUnregister_RemovesEntry_KeepsStateIfOtherHooksExist(t *testing.T) {
	r := NewRegistry()
	hook1 := func(_ models.SecretValue) error { return nil }
	hook2 := func(_ models.SecretValue) error { return nil }

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

// TestUnregister_LastHook_RemovesState verifies that when the last hook
// for a secret is unregistered, the entire SecretState is removed from
// the registry.
func TestUnregister_LastHook_RemovesState(t *testing.T) {
	r := NewRegistry()
	hook := func(_ models.SecretValue) error { return nil }

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

// TestUnregister_IdempotentAcrossMultipleCalls verifies that calling
// Unregister multiple times is safe and only removes the hook once.
func TestUnregister_IdempotentAcrossMultipleCalls(t *testing.T) {
	r := NewRegistry()
	hook := func(_ models.SecretValue) error { return nil }

	ref := r.Register("proj", "sec", hook, 0)
	ref.Unregister()
	ref.Unregister() // second call should be no-op

	if r.Get("proj:sec") != nil {
		t.Error("state should remain removed after redundant Unregister calls")
	}
}

// TestGet_ReturnsNilForNonexistentSecret verifies that Get returns nil
// when queried for a secretRef that has never been registered.
func TestGet_ReturnsNilForNonexistentSecret(t *testing.T) {
	r := NewRegistry()

	state := r.Get("nonexistent:secret")

	if state != nil {
		t.Errorf("Get() for nonexistent secret = %v, want nil", state)
	}
}

// TestSnapshot_ReturnsAllStates verifies that Snapshot returns all
// currently registered SecretStates.
func TestSnapshot_ReturnsAllStates(t *testing.T) {
	r := NewRegistry()
	hook := func(_ models.SecretValue) error { return nil }

	r.Register("proj1", "sec1", hook, 0)
	r.Register("proj2", "sec2", hook, 0)
	r.Register("proj3", "sec3", hook, 0)

	snapshot := r.Snapshot()

	if len(snapshot) != 3 {
		t.Errorf("Snapshot() returned %d states, want 3", len(snapshot))
	}
}

// TestSnapshot_EmptyRegistry_ReturnsNilOrEmpty verifies that Snapshot
// returns nil or an empty slice when the registry is empty.
func TestSnapshot_EmptyRegistry_ReturnsNilOrEmpty(t *testing.T) {
	r := NewRegistry()

	snapshot := r.Snapshot()

	if len(snapshot) != 0 {
		t.Errorf("Snapshot() on empty registry = %v, want nil or empty", snapshot)
	}
}

// TestSnapshot_ReflectsRegistrySize verifies that Snapshot length tracks
// number of unique secrets registered.
func TestSnapshot_ReflectsRegistrySize(t *testing.T) {
	r := NewRegistry()
	hook := func(_ models.SecretValue) error { return nil }

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

	// Registering a second hook for sec1 should not increase unique secret count
	r.Register("proj", "sec1", hook, 0)
	if got := len(r.Snapshot()); got != 2 {
		t.Errorf("len(Snapshot()) after adding second hook to same secret = %d, want 2", got)
	}
}

// TestConcurrentRegisterAndUnregister_ThreadSafe verifies that the
// registry is safe for concurrent register and unregister operations.
func TestConcurrentRegisterAndUnregister_ThreadSafe(t *testing.T) {
	r := NewRegistry()
	const numGoroutines = 50

	hook := func(_ models.SecretValue) error { return nil }

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
	// Test passes if no data race is detected (run with -race flag)
}

// TestConcurrentGetAndSnapshot_ThreadSafe verifies that Get and Snapshot
// can be called concurrently while registrations and unregistrations happen.
func TestConcurrentGetAndSnapshot_ThreadSafe(t *testing.T) {
	r := NewRegistry()
	const numGoroutines = 50

	hook := func(_ models.SecretValue) error { return nil }

	var wg sync.WaitGroup
	wg.Add(numGoroutines * 3) // register, get, snapshot

	// Registrations
	for i := 0; i < numGoroutines; i++ {
		go func() {
			defer wg.Done()
			r.Register("proj", "sec", hook, 0)
		}()
	}

	// Concurrent Get calls
	for i := 0; i < numGoroutines; i++ {
		go func() {
			defer wg.Done()
			r.Get("proj:sec")
		}()
	}

	// Concurrent Snapshot calls
	for i := 0; i < numGoroutines; i++ {
		go func() {
			defer wg.Done()
			r.Snapshot()
		}()
	}

	wg.Wait()
	// Test passes if no data race is detected (run with -race flag)
}

// TestRegister_MultipleSecretsInParallel_AllTracked verifies that
// registering hooks for multiple different secrets concurrently works correctly.
func TestRegister_MultipleSecretsInParallel_AllTracked(t *testing.T) {
	r := NewRegistry()
	const numSecrets = 20

	hook := func(_ models.SecretValue) error { return nil }

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

// TestUnregister_NonexistentSecretRef_NoOp verifies that unregistering
// a handle whose secretRef no longer exists in the registry is safe (no-op).
func TestUnregister_NonexistentSecretRef_NoOp(t *testing.T) {
	r := NewRegistry()
	hook := func(_ models.SecretValue) error { return nil }

	ref := r.Register("proj", "sec", hook, 0)
	ref.Unregister()

	// Call Unregister again after state is already removed
	ref.Unregister()

	// Should not panic; registry remains empty
	if got := len(r.Snapshot()); got != 0 {
		t.Errorf("len(Snapshot()) = %d, want 0 after redundant unregister", got)
	}
}

// TestRegister_CorrectSecretRefFormat verifies that the secretRef is
// constructed as "projectID:secretName".
func TestRegister_CorrectSecretRefFormat(t *testing.T) {
	r := NewRegistry()
	hook := func(_ models.SecretValue) error { return nil }

	ref := r.Register("my-project", "my-secret", hook, 0)

	if got := ref.GetSecretRef(); got != "my-project:my-secret" {
		t.Errorf("GetSecretRef() = %q, want %q", got, "my-project:my-secret")
	}

	state := r.Get("my-project:my-secret")
	if state == nil {
		t.Error("state should be retrievable with projectID:secretName key")
	}
}

// TestRegister_WithColonInSecretName verifies that secretNames containing
// colons are handled correctly (the secretRef key is still "projectID:secretName").
func TestRegister_WithColonInSecretName(t *testing.T) {
	r := NewRegistry()
	hook := func(_ models.SecretValue) error { return nil }

	// Secret name with colons
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

// TestSnapshot_DoesNotReturnNilStates verifies that Snapshot never includes
// nil entries (sanity check for registry integrity).
func TestSnapshot_DoesNotReturnNilStates(t *testing.T) {
	r := NewRegistry()
	hook := func(_ models.SecretValue) error { return nil }

	r.Register("proj1", "sec1", hook, 0)
	r.Register("proj2", "sec2", hook, 0)

	snapshot := r.Snapshot()

	for i, state := range snapshot {
		if state == nil {
			t.Errorf("Snapshot()[%d] is nil; all entries should be non-nil", i)
		}
	}
}
