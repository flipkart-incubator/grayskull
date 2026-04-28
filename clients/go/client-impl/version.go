package client_impl

// SDKVersion is the released version of the Grayskull Go SDK. It is exported
// so that callers can assert compatibility, and is also embedded in the
// User-Agent header sent on every request ("grayskull-go/<SDKVersion>").
//
// This constant is the single source of truth and must be bumped together
// with the Java SDK's pom.xml <version> at release time. Keeping it as a
// const (rather than reading a file at runtime) gives us a build-time error
// if the constant is ever referenced incorrectly and avoids any I/O on the
// startup hot path.
const SDKVersion = "0.2.0"
