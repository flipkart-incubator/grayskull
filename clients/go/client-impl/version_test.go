package client_impl

import (
	"testing"
)

// TestGetVersion_MatchesConstant: GetVersion returns Version, or "unknown"
// when Version is empty.
func TestGetVersion_MatchesConstant(t *testing.T) {
	t.Run("returns constant when set", func(t *testing.T) {
		if Version == "" {
			t.Fatal("Version constant should not be empty by default")
		}
		if got := GetVersion(); got != Version {
			t.Errorf("GetVersion() = %q, want %q", got, Version)
		}
	})

	t.Run("returns unknown when Version is empty", func(t *testing.T) {
		original := Version
		t.Cleanup(func() { Version = original })
		Version = ""
		if got := GetVersion(); got != "unknown" {
			t.Errorf("GetVersion() = %q, want %q", got, "unknown")
		}
	})

	t.Run("is consistent across calls", func(t *testing.T) {
		if a, b := GetVersion(), GetVersion(); a != b {
			t.Errorf("GetVersion() returned inconsistent values: %q vs %q", a, b)
		}
	})
}

// TestVersion_LooksLikeSemVer: loose sanity check; Version contains a digit
// or '.'.
func TestVersion_LooksLikeSemVer(t *testing.T) {
	if Version == "" {
		t.Skip("Version overridden to empty elsewhere")
	}
	for _, c := range Version {
		if (c >= '0' && c <= '9') || c == '.' {
			return
		}
	}
	t.Errorf("Version %q does not look like a valid version string", Version)
}
