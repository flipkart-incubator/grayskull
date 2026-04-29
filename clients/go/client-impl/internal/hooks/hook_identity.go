package hooks

import (
	"reflect"

	apihooks "github.com/flipkart-incubator/grayskull/clients/go/client-api/hooks"
)

// indexOfHook returns the index of hook in slice, comparing function values
// by their underlying code pointer (Go disallows direct == comparison of
// function values). Returns -1 if hook is nil or not present.
//
// This is the Go-side analog of Java's List.indexOf(Object) for the
// SecretRefreshHook lambda type.
func indexOfHook(slice []apihooks.SecretRefreshHook, hook apihooks.SecretRefreshHook) int {
	if hook == nil {
		return -1
	}
	target := reflect.ValueOf(hook).Pointer()
	for i, h := range slice {
		if h == nil {
			continue
		}
		if reflect.ValueOf(h).Pointer() == target {
			return i
		}
	}
	return -1
}
