# Grayskull Java Client SDK

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](../../LICENSE)

Java client library for interacting with the Grayskull secret management service.

## Table of Contents

- [Features](#features)
- [Integration](#integration)
- [Quick Start](#quick-start)
- [Public APIs](#public-apis)
  - [GrayskullClient](#grayskullclient)
- [Refresh hooks](#refresh-hooks)
- [Configuration](#configuration)
  - [Client identity headers](#client-identity-headers)
- [Authentication](#authentication)
- [Metrics](#metrics)
- [Logging & Observability](#logging--observability)
- [Error Handling](#error-handling)
- [Compatibility Matrix](#compatibility-matrix)
- [Building from Source](#building-from-source)
- [License](#license)

## Features

✅ **Simple API** - Clean, intuitive interface for retrieving secrets  
✅ **Refresh hooks** - Background batch polling and callbacks when secret versions change  
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
        <version>0.2.0</version>
    </dependency>

    <!-- Optional: Micrometer for advanced metrics -->
    <dependency>
        <groupId>io.micrometer</groupId>
        <artifactId>micrometer-core</artifactId>
        <version>1.12.7</version>
        <optional>true</optional>
    </dependency>
</dependencies>
```

### Gradle

```gradle
dependencies {
    implementation 'com.flipkart.grayskull:client-impl:0.2.0'
    
    // Optional: Micrometer for advanced metrics
    implementation 'io.micrometer:micrometer-core:1.12.7'
}
```

## Quick Start

```java
import com.flipkart.grayskull.GrayskullClient;
import com.flipkart.grayskull.GrayskullClientImpl;
import com.flipkart.grayskull.auth.BasicAuthHeaderProvider;
import com.flipkart.grayskull.hooks.RefreshHandlerRef;
import com.flipkart.grayskull.models.GrayskullClientConfiguration;
import com.flipkart.grayskull.models.SecretValue;

public class Example {

    /**
     * Called by the SDK when the secret’s data version advances on the server.
     * Use a named method (or {@code this::yourMethod}) when the refresh logic does not fit in a small lambda.
     */
    private static void updateHikariCreds(SecretValue updated) throws Exception {
        // Example: map public part to username, private part to password — keep work fast or hand off async.
        // hikariConfig.setUsername(updated.getPublicPart());
        // hikariConfig.setPassword(updated.getPrivatePart());
        System.out.println("Rotated DB creds, version " + updated.getDataVersion());
    }

    public static void main(String[] args) throws Exception {
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

        // 4. Optional: refresh hook — pass a method reference or a lambda (see [Refresh hooks](#refresh-hooks))
        RefreshHandlerRef handle = grayskullClient.registerRefreshHook(
                "my-project:secret-1",
                Example::updateHikariCreds);

        handle.unRegister();
        grayskullClient.close();
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

Registers a callback that runs when the monitored secret’s **data version** advances on the server. Behaviour, threading, and limits are described in **[Refresh hooks](#refresh-hooks)**.

**Parameters:**
- `secretRef` - Secret reference to monitor (`"projectId:secretName"`, same format as `getSecret`)
- `hook` - `SecretRefreshHook` invoked with the new `SecretValue` (may throw; failures are logged and metered)

**Returns:**
- `RefreshHandlerRef` - Live handle (`getSecretRef()`, `isActive()`, idempotent `unRegister()`)

**Example (lambda or method reference):**
```java
RefreshHandlerRef handle = client.registerRefreshHook(
    "my-project:api-key",
    MyService::onApiKeyRotated);

// or: (updated) -> updateCache(updated)

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

## Refresh hooks

The implementation polls the server’s **`POST /v1/secrets/batch`** endpoint on a fixed schedule, sends each registered secret together with the client’s **last known data version**, and invokes your hooks only when the server reports a newer version. Updates are **coalesced** (if several rotations happen between polls, you typically receive the latest value once per delivery cycle).

### How to use

1. Create `GrayskullClientImpl` (or any `GrayskullClient`) and keep it open while you care about updates.
2. Call `registerRefreshHook(secretRef, hook)` for each secret you want to watch. The same `secretRef` string can have **multiple** hooks; each registration returns its own `RefreshHandlerRef`.
3. Call `unRegister()` on the handle when you no longer need that callback (safe to call twice).
4. Call `close()` on the client when shutting down; this stops the poller and the hook **dispatcher** thread pool.

**Passing the hook:** `SecretRefreshHook` is a `@FunctionalInterface` — you can pass a **lambda** (`(v) -> { ... }`), a **method reference** (`Example::updateHikariCreds` or `this::onSecretRotated` inside an instance), or any object that implements `void onUpdate(SecretValue secret) throws Exception`.

**Multiple hooks on the same `secretRef`:** callbacks run in **registration order** (first registered runs first, then the next, for each delivered update).

```java
import com.flipkart.grayskull.GrayskullClient;
import com.flipkart.grayskull.GrayskullClientImpl;
import com.flipkart.grayskull.hooks.RefreshHandlerRef;
import com.flipkart.grayskull.models.GrayskullClientConfiguration;
import com.flipkart.grayskull.models.SecretValue;

GrayskullClientConfiguration config = new GrayskullClientConfiguration();
config.setHost("https://grayskull.example.com");
// Optional: how often to poll when hooks are registered (seconds, must be > 0)
config.setPollingIntervalSeconds(60);

try (GrayskullClient client = new GrayskullClientImpl(authProvider, config)) {
    RefreshHandlerRef ref = client.registerRefreshHook(
            "my-project:database-password",
            (SecretValue latest) -> { /* or: YourClass::onDatabasePassword */ });

    // ... application runs ...

    ref.unRegister();
}
```

### Threading and performance

- **Poller:** a single scheduled thread runs batch polls. The **first** poll fires **`pollingIntervalSeconds` after the client is constructed**; each subsequent run starts **`pollingIntervalSeconds` after the previous poll finished** (`scheduleWithFixedDelay` semantics). When no hooks are registered the poll returns immediately as a no-op. Callers that need an immediate materialized value at startup should call `getSecret()` explicitly — this also keeps the `getSecret.*` and `hook.execute.*` metrics meaningfully separate.
- **Hooks:** callbacks run on a **small shared** worker pool (several threads for all secrets). Keep hook bodies **short**; offload heavy work to your own executor if needed. Slow hooks delay other secrets sharing the same pool.
- **Hook errors:** uncaught exceptions from a hook are logged and recorded in metrics; other hooks for the same secret still run.

### Batch size (50 secrets per request)

The server accepts at most **50** secrets per batch call. If you register more than 50 distinct `secretRef` values, the client **automatically splits** them into multiple batch requests within the same poll cycle.

### Version tracking and `getSecret`

You **do not** need to call `getSecret` before `registerRefreshHook`. For each registered secret the poller starts with **`lastKnownVersion` 0** and sends that in batch requests until a delivery updates it. The server returns a row whenever its version is **greater** than the last known value you sent, so the **first successful poll** after registration may invoke your hooks with the **current** secret (any `dataVersion > 0`)—that is expected and gives you an initial materialized value without a separate `getSecret` call.

You **do not** need to call `getSecret` before `registerRefreshHook`. The poller starts with `lastKnownVersion = 0` for each registered secret and sends that in batch requests until a delivery updates it. The server returns a row whenever its version is **greater** than the last known value you sent, so the **first successful poll** after registration will invoke your hook with the current secret — giving you an initial materialized value without a separate `getSecret` call.

Calling `getSecret` is still useful when you need to read the secret value **synchronously** at startup before the first poll fires.

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
| `metricsEnabled` | `boolean` | `true` | true/false | Enable/disable metrics collection |
| `pollingIntervalSeconds` | `int` | `60` | > 0 (set via `setPollingIntervalSeconds`) | Seconds **between** completed batch polls (also used as the initial delay before the first poll; see [Refresh hooks](#refresh-hooks)) |

### Client identity headers

At construction, `GrayskullClientImpl` pins two headers on every outbound request:

- **`Grayskull-Workload`** — canonical workload identity (default: local hostname from `DefaultWorkloadIdentityResolver`). Override with `GrayskullClientConfiguration#setWorkloadIdentityResolver` before creating the client. This is the authoritative source of caller identity for all server-side consumers.
- **`User-Agent`** — `grayskull-java/<version>`, where `<version>` comes from the Maven-filtered `grayskull-client.properties` on the classpath (falls back to `unknown` if missing or unfiltered). Intended for SDK telemetry only; its format is not a stable contract and must not be parsed for caller identity — use `Grayskull-Workload` instead.

If you call `addDefaultHeader` for the same names before constructing the client, the SDK values above replace yours (single value per name on the wire). `Authorization` and `X-Request-Id` are also applied after configured default headers so the SDK always supplies the active auth token and per-call request id from MDC.

### Client identity headers

At construction, `GrayskullClientImpl` pins two headers on every outbound request:

- **`Grayskull-Workload`** — canonical workload identity (default: local hostname from `DefaultWorkloadIdentityResolver`). Override with `GrayskullClientConfiguration#setWorkloadIdentityResolver` before creating the client. This is the authoritative source of caller identity for all server-side consumers.
- **`User-Agent`** — `grayskull-java/<version>`, where `<version>` comes from the Maven-filtered `grayskull-client.properties` on the classpath (falls back to `unknown` if missing or unfiltered). Intended for SDK telemetry only; its format is not a stable contract and must not be parsed for caller identity — use `Grayskull-Workload` instead.

If you call `addDefaultHeader` for the same names before constructing the client, the SDK values above replace yours (single value per name on the wire). `Authorization` and `X-Request-Id` are also applied after configured default headers so the SDK always supplies the active auth token and per-call request id from MDC.

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

### Emitted method names

The `{method}` token in the metric formats below takes one of:

- **`getSecret.{secretRef}`** — one sample per `getSecret` call (latency and HTTP status).
- **`batchGetSecrets`** — one sample per background poll cycle (latency and overall status). Not labelled by `secretRef` because a single cycle covers many secrets.
- **`hook.execute.{secretRef}`** — one sample per refresh-hook invocation (latency and `200` for success / `500` for failure).

> **Cardinality note:** `secretRef` is embedded in the metric name for `getSecret.*` and `hook.execute.*`. Backends like Prometheus do not handle unbounded label cardinality well, so keep the number of distinct registered `secretRef` values bounded (typically tens to low hundreds per process).

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
config.setMetricsEnabled(false);  // No metrics overhead
```

## Logging & Observability

The SDK uses SLF4J for logging and leverages **MDC (Mapped Diagnostic Context)** for rich contextual logging without cluttering your code.

### Automatic Context Propagation

The client automatically adds context to MDC for all operations:

| MDC Key | Description | Example Value |
|---------|-------------|---------------|
| `GrayskullRequestId` | Unique identifier for each request | `"abc-123-def-456"` |
| `projectId` | Grayskull project ID | `"my-project"` |
| `secretName` | Name of the secret being accessed | `"database-password"` |

### Distributed Tracing

The SDK automatically includes the `X-Request-Id` header in all HTTP requests to the Grayskull server. This enables end-to-end request correlation:

```
Client Log:  [GrayskullRequestId:abc-123] Fetching secret
HTTP Header: X-Request-Id: abc-123
Server Log:  [RequestId:abc-123] Processing secret request
```

This makes it easy to trace a single request through your entire system, from client to server and back.


### Configuring Your Logging Pattern

To take advantage of MDC context, update your logging configuration (Logback, Log4j2, etc.).

#### Logback Example

Add to your `logback.xml` or `logback-spring.xml`:

```xml
<configuration>
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>
                %d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} [GrayskullRequestId:%X{GrayskullRequestId}] [ProjectId:%X{projectId}] [SecretName:%X{secretName}] - %msg%n
            </pattern>
        </encoder>
    </appender>
    
    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
    </root>
    
    <!-- Enable debug logging for Grayskull -->
    <logger name="com.flipkart.grayskull" level="DEBUG"/>
</configuration>
```

💡 **Reference Configuration:** A complete example configuration is included in the SDK JAR at `logback-example.xml`. You can:
- View it in the [source code](client-impl/src/main/resources/logback-example.xml)
- Extract it from the JAR: `jar xf client-impl-*.jar logback-example.xml`
- Copy the pattern above directly into your logging configuration

### MDC Cleanup

The SDK automatically cleans up its MDC keys (`GrayskullRequestId`, `projectId`, `secretName`) after each operation, ensuring no memory leaks or cross-thread contamination. Your application's existing MDC context (trace IDs, user IDs, etc.) remains untouched.

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
| `client-api` | 0.2.0 |
| `client-impl` | 0.2.0 |

### Dependency Requirements

| Dependency | Version | Required | Bundled |
|------------|---------|----------|---------|
| **Java Runtime** | 8+ | ✅ Yes | - | 
| **OkHttp** | 4.12.0 | ✅ Yes | ✅ |
| **SLF4J API** | 2.0.16 | ✅ Yes | ✅ | 
| **Jackson Databind** | 2.15.3 | ✅ Yes | ✅ |
| **Micrometer Core** | 1.12.7+ | ❌ No | ❌ |

> **Note:** If you need Java 8 compatibility, use Micrometer 1.12.x (last version supporting Java 8). Micrometer 1.13+ requires Java 17.

### Test Dependencies (Development Only)

| Dependency | Version | Purpose |
|------------|---------|---------|
| **JUnit Jupiter** | 5.10.1 | Test framework |
| **Mockito** | 4.11.0 | Mocking framework (4.x for Java 8) |
| **Logback** | 1.3.14 | Test logging |
| **MockWebServer** | 4.12.0 | HTTP mocking |


## Building from Source

### Prerequisites

- Java 8 or higher
- Maven 3.6+

### Build Steps

```bash
# Clone the repository
git clone https://github.com/flipkart/grayskull-oss.git
cd grayskull-oss/clients/java

# Build all modules (client-api + client-impl)
mvn clean install
cd ..

```


## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](../../LICENSE) file for details.

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

