# Grayskull Java Client SDK

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](../LICENSE)

Java client library for interacting with the Grayskull secret management service.

## Table of Contents

- [Features](#features)
- [Integration](#integration)
- [Quick Start](#quick-start)
- [Public APIs](#public-apis)
  - [GrayskullClient](#grayskullclient)
- [Configuration](#configuration)
- [Authentication](#authentication)
- [Metrics](#metrics)
- [Error Handling](#error-handling)
- [Compatibility Matrix](#compatibility-matrix)
- [Building from Source](#building-from-source)
- [License](#license)

## Features

✅ **Simple API** - Clean, intuitive interface for retrieving secrets  
✅ **Automatic Retries** - Exponential backoff with jitter for transient failures  
✅ **Flexible Metrics** - Micrometer or JMX, automatically detected  
✅ **Thread-Safe** - Concurrent request handling with connection pooling  
✅ **Pluggable Auth** - Custom authentication provider support  
✅ **Java 8+ Compatible** - Works with Java 8 through Java 21  

## Integration

### Maven

```xml
<dependencies>
    <!-- Client Implementation (required) -->
    <dependency>
        <groupId>com.flipkart.grayskull</groupId>
        <artifactId>client-impl</artifactId>
        <version>0.0.1-SNAPSHOT</version>
    </dependency>

    <!-- Optional: Micrometer for advanced metrics -->
    <dependency>
        <groupId>io.micrometer</groupId>
        <artifactId>micrometer-core</artifactId>
        <version>1.11.0</version>
        <optional>true</optional>
    </dependency>
</dependencies>
```

### Gradle

```gradle
dependencies {
    implementation 'com.flipkart.grayskull:client-impl:0.0.1-SNAPSHOT'
    
    // Optional: Micrometer for advanced metrics
    implementation 'io.micrometer:micrometer-core:1.11.0'
}
```

## Quick Start

```java
import com.flipkart.grayskull.GrayskullClient;
import com.flipkart.grayskull.GrayskullClientImpl;
import com.flipkart.grayskull.auth.BasicAuthHeaderProvider;
import com.flipkart.grayskull.models.GrayskullClientConfiguration;
import com.flipkart.grayskull.models.SecretValue;

public class Example {
    public static void main(String[] args) {
        // 1. Configure the client
        GrayskullClientConfiguration config = new GrayskullClientConfiguration();
        config.setHost("https://grayskull.example.com");
        
        // 2. Create authentication provider
        BasicAuthHeaderProvider authProvider = 
            new BasicAuthHeaderProvider("username", "password");
        
        // 3. Initialize and use the client
        GrayskullClient grayskullClient = new GrayskullClientImpl(authProvider, config);
        SecretValue secret = grayskullClient.getSecret("my-project:secret-1");
        System.out.println(secret.getPublicPart());


        HikariConfig hikariConfig;
        HikariDataSource dataSource = new HikariDataSource(hikariConfig);
        RefreshHandlerRef handle = grayskullClient.registerRefreshHook("my-project:secret-1", (error, secret) -> {
            if (error != null) {
                System.err.println("Failed to refresh secret: " + error.getMessage());
                return;
            }
            System.out.println(secret.getPublicPart());
            hikariConfig.setUsername(secret.getPublicPart());
            hikariConfig.setPassword(secret.getPrivatePart());
        });

        handle.unRegister();

    }
}
```

## Public APIs

### GrayskullClient

The main interface for interacting with the Grayskull service. Implements `AutoCloseable` for resource management.

```java
public interface GrayskullClient extends AutoCloseable {
    SecretValue getSecret(String secretRef);
    RefreshHandlerRef registerRefreshHook(String secretRef, SecretRefreshHook hook);
}
```

#### `getSecret(String secretRef)`

Retrieves a secret from the Grayskull server.

**Parameters:**
- `secretRef` - Secret reference in format `"projectId:secretName"` (e.g., `"my-project:database-password"`)

**Returns:**
- `SecretValue` - Object containing the secret's public part, private part, and version

**Throws:**
- `IllegalArgumentException` - If secretRef is null, empty, or has invalid format
- `GrayskullException` - If retrieval fails or retries are exhausted

**Example:**
```java
// Simple retrieval
SecretValue secret = client.getSecret("prod-app:api-key");

// Access secret components
String apiUrl = secret.getPublicPart();      // e.g., "https://api.example.com"
String apiKey = secret.getPrivatePart();     // e.g., "sk-abc123..."
int version = secret.getDataVersion();       // e.g., 5
```

#### `registerRefreshHook(String secretRef, SecretRefreshHook hook)`

Registers a callback to be invoked when a secret is updated.

**Parameters:**
- `secretRef` - Secret reference to monitor
- `hook` - Callback function to execute on updates

**Returns:**
- `RefreshHandlerRef` - Handle for managing the hook lifecycle

**Note:** ⚠️ This is a placeholder implementation. Hooks can be registered but won't be invoked until server sent events support is added in a future release. Including this code now ensures forward compatibility.

**Example:**
```java
RefreshHandlerRef handle = client.registerRefreshHook(
    "my-project:api-key",
    (error, updatedSecret) -> {
        if (error != null) {
            logger.error("Failed to refresh secret", error);
            // Handle error: maybe keep using old credentials, send alert, etc.
            return;
        }
        
        System.out.println("Secret updated! New version: " + updatedSecret.getDataVersion());
        updateCache(updatedSecret);
    }
);

// Later: unregister the hook
handle.unRegister();
```

#### `close()`

Releases all resources (HTTP connections, thread pools, etc.). Automatically called when using try-with-resources.

```java
// Manual close
client.close();

// Or use try-with-resources
try (GrayskullClient client = new GrayskullClientImpl(auth, config)) {
    // Use client
} // Automatically closed
```

## Configuration

### GrayskullClientConfiguration

All configuration properties with their defaults and constraints.

| Property | Type | Default | Range/Format | Description |
|----------|------|---------|--------------|-------------|
| `host` | `String` | *required* | URL | Grayskull server endpoint (e.g., `"https://grayskull.example.com"`) |
| `connectionTimeout` | `int` | `10000` | > 0 ms | Max time to establish connection |
| `readTimeout` | `int` | `30000` | > 0 ms | Max time to wait for response data |
| `maxConnections` | `int` | `10` | > 0 | Connection pool size |
| `maxRetries` | `int` | `3` | 1-10 | Number of retry attempts for transient failures |
| `minRetryDelay` | `int` | `100` | ≥ 50 ms | Base delay between retries (exponential backoff) |
| `enableMetrics` | `boolean` | `true` | true/false | Enable/disable metrics collection |

## Authentication

The client uses pluggable authentication via the `GrayskullAuthHeaderProvider` interface.

### Built-in: Basic Authentication

```java
BasicAuthHeaderProvider auth = new BasicAuthHeaderProvider("username", "password");
```

Generates HTTP Basic Auth headers: `Basic base64(username:password)`

### Custom Authentication Provider

Implement `GrayskullAuthHeaderProvider` for custom auth schemes (JWT, API keys, etc.):

**Note:** ⚠️ Implementation supporting OAuth will be provided in future releases


**Thread Safety:** Implementations **must** be thread-safe as `getAuthHeader()` is called concurrently.



## Metrics

The SDK provides comprehensive observability with automatic metric library detection:
- **Micrometer** (if on classpath) - Advanced metrics with percentiles
- **JMX** (fallback) - Basic metrics, zero dependencies

### Micrometer Metrics

When `micrometer-core` is on the classpath, the client exposes rich metrics to the global registry.

#### Metric Format

```
grayskull_client_{method}_{secretRef}{status="{statusCode}"}
```

**Examples:**
- `grayskull_client_getSecret_prod-app:db-password{status="200"}`
- `grayskull_client_getSecret_prod-app:db-password{status="500"}`

#### Available Statistics

- **Count** - Number of requests
- **Total Time** - Sum of all request durations
- **Max** - Longest request duration
- **Percentiles** - P50, P95, P99, P999 response times

#### Integration Example

```java
// JMX
JmxMeterRegistry jmxRegistry = new JmxMeterRegistry(JmxConfig.DEFAULT, Clock.SYSTEM);
Metrics.addRegistry(jmxRegistry);

// Now Grayskull client metrics are available via JMX
```

### JMX Metrics

Fallback when Micrometer is unavailable. No additional dependencies required.

#### MBean Format

```
Grayskull:type=HttpClientMetrics,name="{method}.{status}.{secretRef}"
Grayskull:type=HttpClientMetrics,name="{method}.{secretRef}"
```

Two MBeans per secret:
1. **Status-specific** - Per HTTP status code metrics
2. **Overall** - Aggregated across all statuses

**Examples:**
- `Grayskull:type=HttpClientMetrics,name="getSecret.200.prod-app:db-password"`
- `Grayskull:type=HttpClientMetrics,name="getSecret.prod-app:db-password"`

#### Exposed Attributes

| Attribute | Type | Description |
|-----------|------|-------------|
| `Count` | `long` | Total number of requests |
| `TotalDurationMs` | `long` | Sum of all request durations (ms) |
| `AverageDurationMs` | `long` | Average request duration (ms) |
| `MaxDurationMs` | `long` | Maximum request duration (ms) |
| `MinDurationMs` | `long` | Minimum request duration (ms) |

### Disabling Metrics

```java
config.setEnableMetrics(false);  // No metrics overhead
```

## Error Handling

### Exception Hierarchy

```
RuntimeException
└── GrayskullException
    └── RetryableException (internal)
```

### GrayskullException

The main exception type thrown by the client.


### Retry Behavior

The client automatically retries transient failures using exponential backoff with jitter.

**Non-Retryable Conditions:**
- HTTP 4xx errors (except 408 Request Timeout and 429 Too Many Requests)
- Invalid configuration
- Invalid secret reference format

## Compatibility Matrix

### SDK Versions

| Artifact | Version        | 
|----------|----------------|
| `client-api` | 0.0.1-SNAPSHOT | 
| `client-impl` | 0.0.1-SNAPSHOT | 

### Dependency Requirements

| Dependency | Version | Required | Bundled |
|------------|---------|----------|---------|
| **Java Runtime** | 8+ | ✅ Yes | - | 
| **OkHttp** | 4.9.3 | ✅ Yes | ✅ |
| **SLF4J API** | 1.7.36 | ✅ Yes | ✅ | 
| **Jackson Databind** | 2.15.3 | ✅ Yes | ✅ |
| **Micrometer Core** | 1.11.0+ | ❌ No | ❌ |


## Building from Source

### Prerequisites

- Java 8 or higher
- Maven 3.6+

### Build Steps

```bash
# Clone the repository
git clone https://github.com/flipkart/grayskull-oss.git
cd grayskull-oss

# Build client-api
cd client-api
mvn clean install
cd ..

# Build client-impl
cd client
mvn clean install
cd ..
```


## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](../LICENSE) file for details.

```
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

For issues, questions, or contributions, please visit the [GitHub repository](https://github.com/flipkart/grayskull-oss).

