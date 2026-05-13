package constants

// HTTP header names used by the Grayskull client on the wire.
const (
	// HeaderWorkload is workload identity, resolved at client construction.
	HeaderWorkload = "Grayskull-Workload"

	// HeaderUserAgent is SDK product/version, set at client construction.
	HeaderUserAgent = "User-Agent"
)
