package hooks

import (
	"testing"

	"github.com/flipkart-incubator/grayskull/clients/go/client-api/hooks"
	"github.com/stretchr/testify/assert"
)

func TestNoOpRefreshHandlerRef(t *testing.T) {
	t.Run("GetSecretRef returns empty string", func(t *testing.T) {
		ref := &NoOpRefreshHandlerRef{}
		assert.Equal(t, "", ref.GetSecretRef(), "GetSecretRef should return empty string")
	})

	t.Run("IsActive always returns false", func(t *testing.T) {
		ref := &NoOpRefreshHandlerRef{}
		assert.False(t, ref.IsActive(), "IsActive should always return false")
	})

	t.Run("Unregister does not panic", func(t *testing.T) {
		ref := &NoOpRefreshHandlerRef{}
		// This should not panic
		assert.NotPanics(t, ref.Unregister, "Unregister should not panic")
	})

	t.Run("Instance is not nil", func(t *testing.T) {
		assert.NotNil(t, GetInstance(), "Instance should not be nil")
	})

	t.Run("Implements RefreshHandlerRef interface", func(t *testing.T) {
		var _ hooks.RefreshHandlerRef = (*NoOpRefreshHandlerRef)(nil)
		// This test will fail at compile time if the interface is not implemented
	})

	t.Run("Multiple Unregister calls are safe", func(t *testing.T) {
		ref := &NoOpRefreshHandlerRef{}
		for i := 0; i < 3; i++ {
			assert.NotPanics(t, ref.Unregister, "Multiple Unregister calls should not panic")
		}
	})

	t.Run("Instance is singleton", func(t *testing.T) {
		ref1 := GetInstance()
		ref2 := GetInstance()
		assert.Same(t, ref1, ref2, "Instance should return the same reference")
	})
}
