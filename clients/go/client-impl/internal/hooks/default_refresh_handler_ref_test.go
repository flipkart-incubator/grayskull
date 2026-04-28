package hooks

import (
	"sync"
	"sync/atomic"
	"testing"

	"github.com/stretchr/testify/assert"
)

func TestDefaultRefreshHandlerRef_UnregisterIsIdempotent(t *testing.T) {
	var calls atomic.Int32
	ref := NewDefaultRefreshHandlerRef("acme:db", func() { calls.Add(1) })

	assert.True(t, ref.IsActive())
	assert.Equal(t, "acme:db", ref.GetSecretRef())

	for i := 0; i < 5; i++ {
		ref.Unregister()
	}
	assert.False(t, ref.IsActive())
	assert.Equal(t, int32(1), calls.Load(),
		"onUnregister must run exactly once across multiple Unregister calls")
}

func TestDefaultRefreshHandlerRef_ConcurrentUnregisterOnlyFiresOnce(t *testing.T) {
	var calls atomic.Int32
	ref := NewDefaultRefreshHandlerRef("acme:secret", func() { calls.Add(1) })

	const goroutines = 64
	var wg sync.WaitGroup
	wg.Add(goroutines)
	start := make(chan struct{})
	for i := 0; i < goroutines; i++ {
		go func() {
			defer wg.Done()
			<-start
			ref.Unregister()
		}()
	}
	close(start)
	wg.Wait()

	assert.Equal(t, int32(1), calls.Load(),
		"onUnregister must run exactly once even under contention")
	assert.False(t, ref.IsActive())
}

func TestDefaultRefreshHandlerRef_NilCallbackIsTolerated(t *testing.T) {
	ref := NewDefaultRefreshHandlerRef("acme:secret", nil)
	assert.NotPanics(t, func() { ref.Unregister() })
}
