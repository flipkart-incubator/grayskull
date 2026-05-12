package client_impl

import (
	"testing"
)

// TestGetVersion_ReturnsNonEmpty verifies that GetVersion always returns a non-empty string.
func TestGetVersion_ReturnsNonEmpty(t *testing.T) {
	version := GetVersion()

	if version == "" {
		t.Error("GetVersion() returned empty string, expected non-empty version")
	}
}

// TestGetVersion_ReturnsVersionFromConstant verifies that GetVersion returns the
// version defined in the Version constant.
func TestGetVersion_ReturnsVersionFromConstant(t *testing.T) {
	version := GetVersion()

	// Should match the Version constant (default: "1.0.0" or whatever is set in version.go)
	if version == "" {
		t.Error("GetVersion() returned empty string, expected version from constant")
	}

	// Verify it matches the Version constant
	if version != Version {
		t.Errorf("GetVersion() = %q, want %q", version, Version)
	}
}

// TestGetVersion_Consistency verifies that GetVersion returns the same value on multiple calls.
func TestGetVersion_Consistency(t *testing.T) {
	version1 := GetVersion()
	version2 := GetVersion()
	version3 := GetVersion()

	if version1 != version2 || version2 != version3 {
		t.Errorf("GetVersion() returned inconsistent values: %q, %q, %q", version1, version2, version3)
	}
}

// TestVersion_GlobalVariable verifies the Version variable is accessible.
func TestVersion_GlobalVariable(t *testing.T) {
	if Version == "" {
		t.Error("Version global variable is empty, expected default value 'dev'")
	}
}
