package models

import (
	"encoding/json"
)

// SecretValue represents a secret value retrieved from Grayskull.
//
// This type provides secure handling of sensitive data by:
// - Using []byte instead of string for the private part to allow zeroing memory
// - Providing a Clear() method to explicitly zero out sensitive data
// - Implementing proper JSON serialization/deserialization
//
// Always call Clear() when done with the secret to minimize the time sensitive
// data remains in memory.
type SecretValue struct {
	// DataVersion represents the version number of the secret data.
	DataVersion int `json:"dataVersion"`

	// PublicPart contains the public part of the secret.
	PublicPart string `json:"publicPart"`

	// PrivatePart contains the private/sensitive part of the secret.
	// This is stored as a byte slice to allow secure zeroing of memory.
	// Use GetPrivatePart() to access the value as a string.
	PrivatePart []byte `json:"-"`

	// privatePartJSON is used internally for JSON serialization
	privatePartJSON string `json:"privatePart,omitempty"`
}

// GetPrivatePart returns the private part of the secret as a string.
// Note that this creates a copy of the data in memory.
func (s *SecretValue) GetPrivatePart() string {
	if s == nil || s.PrivatePart == nil {
		return ""
	}
	return string(s.PrivatePart)
}

// SetPrivatePart sets the private part of the secret.
// This makes a copy of the input string.
func (s *SecretValue) SetPrivatePart(value string) {
	if s == nil {
		return
	}
	// Clear any existing value first
	s.Clear()
	s.PrivatePart = []byte(value)
}

// Clear zeroes out the sensitive data in memory.
// Always call this when the secret is no longer needed.
func (s *SecretValue) Clear() {
	if s == nil {
		return
	}
	for i := range s.PrivatePart {
		s.PrivatePart[i] = 0
	}
	s.PrivatePart = nil
	s.privatePartJSON = ""
}

// MarshalJSON implements json.Marshaler
func (s *SecretValue) MarshalJSON() ([]byte, error) {
	type Alias SecretValue
	return json.Marshal(&struct {
		*Alias
		PrivatePart string `json:"privatePart,omitempty"`
	}{
		Alias:       (*Alias)(s),
		PrivatePart: string(s.PrivatePart),
	})
}

// UnmarshalJSON implements json.Unmarshaler
func (s *SecretValue) UnmarshalJSON(data []byte) error {
	type Alias SecretValue
	aux := &struct {
		*Alias
		PrivatePart string `json:"privatePart,omitempty"`
	}{
		Alias: (*Alias)(s),
	}
	if err := json.Unmarshal(data, &aux); err != nil {
		return err
	}
	s.privatePartJSON = aux.PrivatePart
	s.PrivatePart = []byte(aux.PrivatePart)
	return nil
}

// String returns a redacted string representation of the secret.
// This prevents accidental logging of sensitive data.
func (s *SecretValue) String() string {
	if s == nil {
		return "SecretValue(nil)"
	}
	return "SecretValue[REDACTED]"
}

// EnsureSecretValueImplementsStringer ensures that SecretValue implements fmt.Stringer
var _ interface {
	String() string
} = (*SecretValue)(nil)
