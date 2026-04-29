package hooks

import (
	"sync"
	"sync/atomic"
	"testing"

	"github.com/stretchr/testify/assert"

	apihooks "github.com/flipkart-incubator/grayskull/clients/go/client-api/hooks"
	apimodels "github.com/flipkart-incubator/grayskull/clients/go/client-api/models"
)

func TestSecretState_AddRemoveHook(t *testing.T) {
	state := NewSecretState("acme", "db")
	hookA := apihooks.SecretRefreshHook(func(s apimodels.SecretValue) error { return nil })
	hookB := apihooks.SecretRefreshHook(func(s apimodels.SecretValue) error { return nil })

	state.AddHook(hookA)
	state.AddHook(hookB)
	assert.Equal(t, 2, state.HookCount())
	assert.Len(t, state.Hooks(), 2)

	assert.True(t, state.RemoveHook(hookA))
	assert.Equal(t, 1, state.HookCount())

	// Removing the same hook again is a no-op (returns false).
	assert.False(t, state.RemoveHook(hookA))
	assert.True(t, state.RemoveHook(hookB))
	assert.Equal(t, 0, state.HookCount())
	assert.True(t, state.IsEmpty())
}

func TestSecretState_HooksSnapshotIsStableDuringRemoval(t *testing.T) {
	// Mirrors Java's CopyOnWriteArrayList iterate-while-mutating safety.
	state := NewSecretState("acme", "concurrent-mod")
	hookA := apihooks.SecretRefreshHook(func(s apimodels.SecretValue) error { return nil })
	hookB := apihooks.SecretRefreshHook(func(s apimodels.SecretValue) error { return nil })

	state.AddHook(hookA)
	state.AddHook(hookB)

	snapshot := state.Hooks()
	state.RemoveHook(hookA)

	assert.Len(t, snapshot, 2,
		"snapshot taken before removal must remain stable (copy-on-write)")
}

func TestSecretState_PendingUpdateLatestWins(t *testing.T) {
	state := NewSecretState("acme", "fast")

	v1 := &apimodels.SecretValue{DataVersion: 1}
	v2 := &apimodels.SecretValue{DataVersion: 2}
	state.PendingUpdate.Store(v1)
	state.PendingUpdate.Store(v2)

	got := state.PendingUpdate.Load()
	assert.NotNil(t, got)
	assert.Equal(t, 2, got.DataVersion,
		"newer value must overwrite older (latest-wins coalescing)")
}

func TestSecretState_ConcurrentAddRemove_DoesNotRace(t *testing.T) {
	// This test is meaningful under -race; it should never report a race.
	state := NewSecretState("acme", "race")
	const goroutines = 32

	var wg sync.WaitGroup
	wg.Add(goroutines)
	var added atomic.Int32
	for i := 0; i < goroutines; i++ {
		go func() {
			defer wg.Done()
			h := apihooks.SecretRefreshHook(func(s apimodels.SecretValue) error { return nil })
			state.AddHook(h)
			added.Add(1)
			// Iterate the snapshot from another goroutine simultaneously.
			for _, hook := range state.Hooks() {
				_ = hook
			}
			state.RemoveHook(h)
		}()
	}
	wg.Wait()

	assert.Equal(t, int32(goroutines), added.Load())
	assert.True(t, state.IsEmpty())
}
