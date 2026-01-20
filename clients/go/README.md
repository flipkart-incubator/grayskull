# Grayskull Go Client SDK

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](../../LICENSE)

Go client library for interacting with the Grayskull secret management service.

## Table of Contents

- [Features](#features)
- [Integration](#integration)
- [Quick Start](#quick-start)
- [Public APIs](#public-apis)
  - [Client Interface](#client-interface)
- [Configuration](#configuration)
- [Authentication](#authentication)
- [Metrics](#metrics)
- [Logging & Observability](#logging--observability)
- [Error Handling](#error-handling)
- [Compatibility Matrix](#compatibility-matrix)
- [Building from Source](#building-from-source)
- [License](#license)

## Features

✅ **Simple API** - Clean, intuitive interface for retrieving secrets  
✅ **Automatic Retries** - Exponential backoff with jitter for transient failures  
✅ **Prometheus Metrics** - Built-in metrics collection and export  
✅ **Thread-Safe** - Concurrent request handling with connection pooling  
✅ **Pluggable Auth** - Custom authentication provider support  
✅ **Context Support** - Full context.Context integration for cancellation and timeouts  

## Integration

```bash
go get github.com/flipkart-incubator/grayskull
```

## Quick Start

```go
package main

import (
    "context"
    "fmt"
    "log"

    "github.com/flipkart-incubator/grayskull/client-impl"
    "github.com/flipkart-incubator/grayskull/client-impl/auth"
    "github.com/flipkart-incubator/grayskull/client-impl/models"
)

func main() {
    // 1. Configure the client
    config := models.NewDefaultConfig()
    config.SetHost("https://grayskull.example.com")
    
    // 2. Create authentication provider
    authProvider := auth.NewBasicAuthHeaderProvider("username", "password")
    
    // 3. Initialize and use the client
    client, err := client_impl.NewGrayskullClient(authProvider, config)
    if err != nil {
        log.Fatalf("Failed to create client: %v", err)
    }
    defer client.Close()
    
    // 4. Retrieve a secret
    ctx := context.Background()
    secret, err := client.GetSecret(ctx, "my-project:secret-1")
    if err != nil {
        log.Fatalf("Failed to get secret: %v", err)
    }
    
    fmt.Printf("Secret: %s\n", secret.PublicPart)
}
```

## Public APIs

### Client Interface

The main interface for interacting with the Grayskull service.

```go
type Client interface {
    GetSecret(ctx context.Context, secretRef string) (*models.SecretValue, error)
    RegisterRefreshHook(ctx context.Context, secretRef string, hook hooks.SecretRefreshHook) (hooks.RefreshHandlerRef, error)
    Close() error
}
```

#### `GetSecret(ctx context.Context, secretRef string)`

Retrieves a secret from the Grayskull server.

**Parameters:**
- `ctx` - Context for request cancellation, timeout, and tracing
- `secretRef` - Secret reference in format `"projectId:secretName"` (e.g., `"my-project:database-password"`)

**Returns:**
- `*models.SecretValue` - Object containing the secret's public part, private part, and version
- `error` - Error if retrieval fails or retries are exhausted

**Example:**
```go
// Simple retrieval
ctx := context.Background()
secret, err := client.GetSecret(ctx, "prod-app:api-key")
if err != nil {
    log.Fatalf("Failed to get secret: %v", err)
}

// Access secret components
publicPart := secret.PublicPart    // e.g., "username" or public data
privatePart := secret.PrivatePart  // e.g., "password" or sensitive data
version := secret.DataVersion      // e.g., 5
```

**With Timeout:**
```go
import (
    "context"
    "time"
)

ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
defer cancel()

secret, err := client.GetSecret(ctx, "my-project:api-key")
if err != nil {
    if ctx.Err() == context.DeadlineExceeded {
        log.Fatal("Request timed out")
    }
    log.Fatalf("Error: %v", err)
}
```

#### `RegisterRefreshHook(ctx context.Context, secretRef string, hook SecretRefreshHook)`

Registers a callback to be invoked when a secret is updated.

**Parameters:**
- `ctx` - Context for the operation
- `secretRef` - Secret reference to monitor
- `hook` - Callback function to execute on updates

**Returns:**
- `RefreshHandlerRef` - Handle for managing the hook lifecycle
- `error` - Error if registration fails

**Note:** ⚠️ This is a placeholder implementation. Hooks can be registered but won't be invoked until server-sent events support is added in a future release. Including this code now ensures forward compatibility.

**Example:**
```go
handle, err := client.RegisterRefreshHook(
    context.Background(),
    "my-project:api-key",
    func(updatedSecret models.SecretValue) error {
        fmt.Printf("Secret updated! New version: %d\n", updatedSecret.DataVersion)
        return updateCache(updatedSecret)
    },
)
if err != nil {
    log.Fatalf("Failed to register hook: %v", err)
}

// Later: unregister the hook
handle.UnRegister()
```

#### `Close()`

Releases all resources (HTTP connections, goroutines, etc.).

```go
// Manual close
client.Close()

// Or use defer
client, err := client_impl.NewGrayskullClient(auth, config)
if err != nil {
    log.Fatal(err)
}
defer client.Close()
```

## Configuration

### GrayskullClientConfiguration

All configuration properties with their defaults and constraints.

| Property | Type | Default | Range/Format | Description |
|----------|------|---------|--------------|-------------|
| `Host` | `string` | *required* | URL | Grayskull server endpoint (e.g., `"https://grayskull.example.com"`) |
| `ConnectionTimeout` | `int` | `5000` | > 0 ms | Max time to establish connection |
| `ReadTimeout` | `int` | `10000` | > 0 ms | Max time to wait for response data |
| `MaxConnections` | `int` | `100` | > 0 | Connection pool size |
| `MaxRetries` | `int` | `3` | 1-10 | Number of retry attempts for transient failures |
| `MinRetryDelay` | `int` | `100` | ≥ 50 ms | Base delay between retries (exponential backoff) |
| `MetricsEnabled` | `bool` | `true` | true/false | Enable/disable metrics collection |

**Example:**
```go
config := models.NewDefaultConfig()
config.SetHost("https://grayskull.example.com")
config.SetConnectionTimeout(5000)
config.SetReadTimeout(10000)
config.SetMaxRetries(3)
config.SetMinRetryDelay(100)
config.SetMaxConnections(100)
config.SetMetricsEnabled(true)
```

## Authentication

The client uses pluggable authentication via the `GrayskullAuthHeaderProvider` interface.

### Built-in: Basic Authentication

```go
authProvider := auth.NewBasicAuthHeaderProvider("username", "password")
```

Generates HTTP Basic Auth headers: `Basic base64(username:password)`

### Custom Authentication Provider

Implement `GrayskullAuthHeaderProvider` for custom auth schemes (JWT, API keys, etc.):

```go
type CustomAuthProvider struct {
    token string
}

func (c *CustomAuthProvider) GetAuthHeader() (string, error) {
    return "Bearer " + c.token, nil
}
```

**Note:** ⚠️ Implementation supporting OAuth will be provided in future releases

**Thread Safety:** Implementations **must** be thread-safe as `GetAuthHeader()` is called concurrently.

## Metrics

The SDK provides comprehensive observability with Prometheus metrics.

### Prometheus Metrics

When metrics are enabled, the client exposes metrics to the Prometheus default registry.

#### Metric Format

```text
grayskull_client_request_duration_seconds
grayskull_client_request_total
grayskull_client_retry_total
```

#### Available Metrics

- **Request Duration** - Histogram of request durations with percentiles (P50, P95, P99)
- **Request Total** - Counter of total requests by status code
- **Retry Total** - Counter of retry attempts

#### Integration Example

```go
import (
    "github.com/prometheus/client_golang/prometheus/promhttp"
    "net/http"
)

// Expose metrics endpoint
http.Handle("/metrics", promhttp.Handler())
go http.ListenAndServe(":9090", nil)

// Now Grayskull client metrics are available at http://localhost:9090/metrics
```

### Disabling Metrics

```go
config.SetMetricsEnabled(false)  // No metrics overhead
```

## Logging & Observability

The SDK uses Go's standard `log/slog` for structured logging.

### Automatic Context Propagation

The client automatically adds context to logs for all operations:

| Context Key | Description | Example Value |
|-------------|-------------|---------------|
| `grayskullRequestId` | Unique identifier for each request | `"abc-123-def-456"` |
| `projectId` | Grayskull project ID | `"my-project"` |
| `secretName` | Name of the secret being accessed | `"database-password"` |

### Distributed Tracing

The SDK automatically includes the `X-Request-Id` header in all HTTP requests to the Grayskull server. This enables end-to-end request correlation:

```text
Client Log:  [grayskullRequestId:abc-123] Fetching secret
HTTP Header: X-Request-Id: abc-123
Server Log:  [RequestId:abc-123] Processing secret request
```

This makes it easy to trace a single request through your entire system, from client to server and back.

## Error Handling

### Exception Hierarchy

```text
error (Go interface)
└── BaseError
    ├── GrayskullError
    └── RetryableError (internal)
```

All errors embed `BaseError` which provides common functionality like status codes, messages, and error chaining.

### GrayskullError

The main error type returned by the client.

```go
import (
    "errors"
    "github.com/flipkart-incubator/grayskull/client-impl/models/exceptions"
)

secret, err := client.GetSecret(ctx, "project:secret")
if err != nil {
    var grayskullErr *exceptions.GrayskullError
    if errors.As(err, &grayskullErr) {
        fmt.Printf("Grayskull error (status %d): %v\n", grayskullErr.StatusCode, grayskullErr)
        return
    }
    log.Fatalf("Unexpected error: %v", err)
}
```

### Retry Behavior

The client automatically retries transient failures using exponential backoff with jitter.

**Retryable Conditions:**
- HTTP 5xx errors
- HTTP 408 Request Timeout
- HTTP 429 Too Many Requests
- Network errors

**Non-Retryable Conditions:**
- HTTP 4xx errors (except 408 and 429)
- Invalid configuration
- Invalid secret reference format

## Compatibility Matrix

### SDK Version

| Module | Version |
|--------|----------|
| `client-api` | 0.0.1-SNAPSHOT |
| `client-impl` | 0.0.1-SNAPSHOT |

### Dependency Requirements

| Dependency | Version | Required |
|------------|---------|----------|
| **Go Runtime** | 1.18+ | ✅ Yes |
| **Prometheus Client** | Latest | ✅ Yes |
| **Google UUID** | Latest | ✅ Yes |

## Building from Source

### Prerequisites

- Go 1.18 or higher

### Build Steps

```bash
# Clone the repository
git clone https://github.com/flipkart-incubator/grayskull.git
cd grayskull/clients/go

# Build the module
go build ./...

# Run tests
go test ./... -v

# Run tests with coverage
go test ./... -coverprofile=coverage.out
go tool cover -html=coverage.out
```

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](../../LICENSE) file for details.

```text
Copyright 2025 Flipkart

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

---

## Support

For issues, questions, or contributions, please visit the [GitHub repository](https://github.com/flipkart-incubator/grayskull).
