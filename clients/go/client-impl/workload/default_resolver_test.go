package workload

import (
	"errors"
	"testing"

	"github.com/stretchr/testify/assert"
)

func TestDefaultWorkloadIdentityResolver_UsesHostname(t *testing.T) {
	prev := hostnameFn
	t.Cleanup(func() { hostnameFn = prev })
	hostnameFn = func() (string, error) { return "test-host-01", nil }

	r := NewDefaultWorkloadIdentityResolver()
	assert.Equal(t, "test-host-01", r.Resolve())
}

func TestDefaultWorkloadIdentityResolver_TrimsWhitespace(t *testing.T) {
	prev := hostnameFn
	t.Cleanup(func() { hostnameFn = prev })
	hostnameFn = func() (string, error) { return "  spaced-host  ", nil }

	r := NewDefaultWorkloadIdentityResolver()
	assert.Equal(t, "spaced-host", r.Resolve())
}

func TestDefaultWorkloadIdentityResolver_FallsBackOnError(t *testing.T) {
	prev := hostnameFn
	t.Cleanup(func() { hostnameFn = prev })
	hostnameFn = func() (string, error) { return "", errors.New("boom") }

	r := NewDefaultWorkloadIdentityResolver()
	assert.Equal(t, UnknownHost, r.Resolve())
}

func TestDefaultWorkloadIdentityResolver_FallsBackOnEmpty(t *testing.T) {
	prev := hostnameFn
	t.Cleanup(func() { hostnameFn = prev })
	hostnameFn = func() (string, error) { return "   ", nil }

	r := NewDefaultWorkloadIdentityResolver()
	assert.Equal(t, UnknownHost, r.Resolve())
}

func TestDefaultWorkloadIdentityResolver_ResolveIsStable(t *testing.T) {
	prev := hostnameFn
	t.Cleanup(func() { hostnameFn = prev })
	calls := 0
	hostnameFn = func() (string, error) {
		calls++
		return "host", nil
	}

	r := NewDefaultWorkloadIdentityResolver()
	for i := 0; i < 5; i++ {
		assert.Equal(t, "host", r.Resolve())
	}
	assert.Equal(t, 1, calls, "hostname should be resolved exactly once at construction")
}
