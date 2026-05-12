package hooks

import (
	"sync/atomic"
	"testing"
)

// TestNewDefaultRefreshHandlerRef_IsActive_AndExposesSecretRef verifies
// that a newly constructed DefaultRefreshHandlerRef is active and exposes
// the secretRef provided at construction time.
func TestNewDefaultRefreshHandlerRef_IsActive_AndExposesSecretRef(t *testing.T) {
	ref := NewDefaultRefreshHandlerRef("p:s", func() { /* no-op */ })

	if !ref.IsActive() {
		t.Error("newly created handle should be active")
	}
	if got := ref.GetSecretRef(); got != "p:s" {
		t.Errorf("GetSecretRef() = %q, want %q", got, "p:s")
	}
}

// TestUnregister_IsIdempotent_CallbackInvokedExactlyOnce verifies that
// Unregister can be called multiple times safely (idempotent) and that
// the onUnregister callback runs exactly once across all calls.
func TestUnregister_IsIdempotent_CallbackInvokedExactlyOnce(t *testing.T) {
	var calls atomic.Int32
	ref := NewDefaultRefreshHandlerRef("p:s", func() {
		calls.Add(1)
	})

	// Call unRegister three times
	ref.Unregister()
	ref.Unregister()
	ref.Unregister()

	if ref.IsActive() {
		t.Error("handle should be inactive after Unregister()")
	}

	if got := calls.Load(); got != 1 {
		t.Errorf("onUnregister callback invoked %d times, want 1", got)
	}
}

// TestUnregister_WithNilCallback_DoesNotPanic ensures that when no
// callback is provided (nil), Unregister still works without panicking.
func TestUnregister_WithNilCallback_DoesNotPanic(t *testing.T) {
	ref := NewDefaultRefreshHandlerRef("p:s", nil)
	
	defer func() {
		if r := recover(); r != nil {
			t.Errorf("Unregister() panicked with nil callback: %v", r)
		}
	}()

	ref.Unregister()

	if ref.IsActive() {
		t.Error("handle should be inactive after Unregister()")
	}
}

// TestUnregister_ConcurrentCalls_StillIdempotent verifies thread safety:
// concurrent Unregister() calls should still result in exactly one callback
// invocation and the handle becoming inactive.
func TestUnregister_ConcurrentCalls_StillIdempotent(t *testing.T) {
	var calls atomic.Int32
	ref := NewDefaultRefreshHandlerRef("p:s", func() {
		calls.Add(1)
	})

	const numGoroutines = 10
	done := make(chan struct{})
	
	for i := 0; i < numGoroutines; i++ {
		go func() {
			ref.Unregister()
			done <- struct{}{}
		}()
	}

	// Wait for all goroutines to complete
	for i := 0; i < numGoroutines; i++ {
		<-done
	}

	if ref.IsActive() {
		t.Error("handle should be inactive after concurrent Unregister() calls")
	}

	if got := calls.Load(); got != 1 {
		t.Errorf("onUnregister callback invoked %d times from concurrent calls, want 1", got)
	}
}

// TestGetSecretRef_ReturnsConstructorValue verifies that GetSecretRef
// returns the exact secretRef string passed to the constructor.
func TestGetSecretRef_ReturnsConstructorValue(t *testing.T) {
	testCases := []string{
		"project:secret",
		"team-a:db-password",
		"org:api-key-prod",
		"special:secret:with:colons",
	}

	for _, secretRef := range testCases {
		ref := NewDefaultRefreshHandlerRef(secretRef, nil)
		if got := ref.GetSecretRef(); got != secretRef {
			t.Errorf("GetSecretRef() = %q, want %q", got, secretRef)
		}
	}
}

// TestIsActive_TransitionsFalseOnUnregister verifies the active state
// transitions correctly from true to false on Unregister().
func TestIsActive_TransitionsFalseOnUnregister(t *testing.T) {
	ref := NewDefaultRefreshHandlerRef("p:s", nil)

	if !ref.IsActive() {
		t.Error("newly created handle should be active")
	}

	ref.Unregister()

	if ref.IsActive() {
		t.Error("handle should be inactive after Unregister()")
	}
}
