package client_impl

// Version is the SDK version. This should be updated with each release.
// This is the source of truth for the SDK version
//
// For special builds (snapshots, CI builds, etc.), this can be overridden at build
// time using -ldflags:
//
//	go build -ldflags "-X github.com/flipkart-incubator/grayskull/clients/go/client-impl.Version=1.0.0-snapshot"
//
// Version format: MAJOR.MINOR.PATCH (semantic versioning)
const Version = "0.3.0"

// GetVersion returns the SDK version string.
func GetVersion() string {
	if Version == "" {
		return "unknown"
	}
	return Version
}
