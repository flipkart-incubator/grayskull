# Grayskull Go SDK Comprehensive Test Service

This directory contains a comprehensive test suite for the Grayskull Go SDK, testing all major features including secret retrieval, retry logic, metrics collection, and error handling.

## Components

1. **Mock Server** (`main.go`) - HTTP server that simulates the Grayskull API with retry scenarios
2. **Test Client** (`client/main.go`) - Comprehensive test suite with metrics and retry validation

## Features Tested

### ✅ Basic Secret Retrieval
- Standard secret fetching
- Multiple projects and secrets
- Authentication handling

### ✅ Retry Logic & Resilience
- **Flaky endpoints** - Fails 2 times with 503, succeeds on 3rd attempt
- **Rate limiting** - Returns 429, succeeds after retry
- **Persistent failures** - Always returns 500 to test max retry exhaustion
- **Slow responses** - Tests timeout handling

### ✅ Metrics Collection
- Request duration tracking (Prometheus histograms)
- Retry attempt counting
- Status code tracking
- Exposed at `http://localhost:9090/metrics`

### ✅ Edge Cases & Error Handling
- Non-existent secrets
- Unknown projects
- Invalid secretRef formats
- Empty parameters
- Missing project/secret names

## Mock Secrets

The mock server includes:

### Standard Secrets
| Project ID | Secret Name   | Public Part                      | Private Part              |
|-----------|---------------|----------------------------------|---------------------------|
| project1  | db-password   | postgres://localhost:5432/mydb   | super-secret-password-123 |
| project1  | api-key       | prod-api-key                     | sk_live_abc123xyz789      |
| project2  | jwt-secret    | HS256                            | my-jwt-secret-key-2024    |

### Retry Test Secrets
| Project ID  | Secret Name    | Behavior                                    |
|------------|----------------|---------------------------------------------|
| retry-test | flaky-secret   | Fails twice (503), succeeds on 3rd attempt  |
| retry-test | rate-limited   | Returns 429, succeeds on retry              |
| retry-test | server-error   | Always returns 500 (exhausts retries)       |
| retry-test | slow-response  | Delays 500ms to test timeout handling       |

## Running the Test Service

### Step 1: Start the Mock Server

```bash
cd /Users/shrutika.a/grayskull-oss/grayskull/clients/go/test-service
go run main.go
```

The server starts on `http://localhost:8080` and logs all requests with retry attempt tracking.

### Step 2: Run the Comprehensive Test Client (in a new terminal)

```bash
cd /Users/shrutika.a/grayskull-oss/grayskull/clients/go/test-service/client
go mod tidy
go run main.go
```

The test client will:
1. Start a metrics server on `http://localhost:9090/metrics`
2. Run **Test Suite 1**: Basic secret retrieval (3 tests)
3. Run **Test Suite 2**: Retry logic & resilience (4 tests)
4. Run **Test Suite 3**: Edge cases & error handling (6 tests)
5. Display metrics summary and keep running for metrics inspection

### Step 3: View Metrics (optional)

While the test client is running, view Prometheus metrics:

```bash
curl http://localhost:9090/metrics
```

Or open in browser: `http://localhost:9090/metrics`

## Expected Output

### Test Suite 1: Basic Secret Retrieval
```
✅ Success! (took 5ms)
   Data Version: 1
   Public Part:  postgres://localhost:5432/mydb
   Private Part: super-secret-password-123
```

### Test Suite 2: Retry Logic
```
✅ Success! (took 250ms)  # After retries
   Data Version: 1
   Public Part:  flaky-endpoint
   Private Part: succeeds-after-retries
```

### Test Suite 3: Edge Cases
```
✅ Expected error received (took 3ms)
   Error: invalid secretRef format. Expected 'projectId:secretName', got: invalid-format
```

## Metrics Available

The test client exposes Prometheus metrics:

- `grayskull_http_client_request_duration_seconds` - Request latency histogram
  - Labels: `name`, `status_code`
- `grayskull_http_client_retry_attempts_total` - Retry counter
  - Labels: `url`, `success`

## Server Logs

The mock server provides detailed logs showing:
- Request tracking with Request IDs
- Retry attempt numbers
- Simulated failure/success decisions
- Response status codes

Example:
```
Request received - ProjectID: retry-test, SecretName: flaky-secret, RequestID: abc-123
  Retry test - Attempt #1 for retry-test:flaky-secret
  → Returning 503 (will succeed on attempt 3)
```

## Configuration

The test client uses these SDK configurations:
- **Max Retries**: 3
- **Min Retry Delay**: 100ms
- **Read Timeout**: 5000ms
- **Metrics**: Enabled with custom Prometheus registry

## Customization

### Adding New Secrets

Edit `main.go`:
```go
var secretStore = map[string]map[string]SecretValue{
    "your-project": {
        "your-secret": {
            DataVersion: 1,
            PublicPart:  "public-info",
            PrivatePart: "private-info",
        },
    },
}
```

### Adding New Test Cases

Edit `client/main.go` and add to any test suite:
```go
testCases := []testCase{
    {"your-project:your-secret", "Your test description", false},
}
```

## API Endpoints

### Mock Server (port 8080)
- `GET /v1/projects/{projectID}/secrets/{secretName}/data` - Get secret data
- `GET /health` - Health check

### Metrics Server (port 9090)
- `GET /metrics` - Prometheus metrics endpoint
