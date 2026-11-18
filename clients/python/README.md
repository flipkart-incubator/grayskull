# Grayskull Python Client

Python client for Grayskull: read secrets, receive refresh hooks when a secret changes, and send the
client heartbeat that reports which secret versions a workload holds. Standard library only at runtime.

Requires Python 3.9 or later.

## Usage

```python
from grayskull import BasicAuthHeaderProvider, GrayskullClientConfiguration, GrayskullClientImpl

configuration = GrayskullClientConfiguration(host="https://grayskull.example.com")
with GrayskullClientImpl(BasicAuthHeaderProvider("user", "password"), configuration) as client:
    secret = client.get_secret("myproject:db-password")

    def on_update(value):
        print("new version", value.data_version)

    handler_ref = client.register_refresh_hook("myproject:db-password", on_update)
    ...
    handler_ref.unregister()
```

Implement `GrayskullAuthHeaderProvider.get_auth_header()` to supply another credential, such as a rotating
bearer token; it is called on every request.

## Package layout

| Package | Contents |
|---|---|
| `grayskull.client_api` | `GrayskullClient`, `RefreshHandlerRef`, `SecretRefreshHook`, `SecretValue`, `WorkloadIdentityResolver`, `GrayskullHeaders` |
| `grayskull.client_impl` | `GrayskullClientImpl`, `GrayskullHttpClient`, `HookRefreshPoller`, `HeartbeatScheduler`, `UsageLedger` |
| `grayskull.client_impl.auth` | `GrayskullAuthHeaderProvider`, `BasicAuthHeaderProvider` |
| `grayskull.client_impl.models` | `GrayskullClientConfiguration`, request and response models, `GrayskullException`, `RetryableException` |
| `grayskull.client_impl.metrics` | `MetricsRecorder`, `NoopMetricsRecorder` |
| `grayskull.client_impl.workload` | `DefaultWorkloadIdentityResolver` |

## Behaviour

- **Refresh hooks** run on a pool of 5 dispatcher threads, at most one delivery at a time per secret. Delivery is
  at-most-once: a version already held when the hook is registered is not redelivered, and a hook that raises is
  logged without blocking other hooks.
- **Heartbeat** posts `{"secrets": [...]}` to `/v1/client/heartbeat` every 24 hours, ±1/24 jitter, in chunks of 50.
  Each entry has `projectId`, `secretName`, `version` and, while a refresh hook is registered,
  `refreshHookAppliedVersion`: the newest version every registered hook applied, or 0 when there has been no
  delivery since registration. A 404, 405 or 501 response means the server does not support heartbeats yet, and the
  next attempt waits the full interval. Other failures back off from 5 to 60 minutes.
- **Retries** apply to `get_secret` and the batch poll for network errors, 429 and 5xx responses, with exponential
  backoff and jitter. Heartbeats are single attempts.

## Configuration

| Field | Default | Meaning |
|---|---|---|
| `host` | required | Grayskull base URL |
| `connection_timeout` / `read_timeout` | 10000 / 30000 | Request timeouts in milliseconds |
| `max_retries` | 3 | Total attempts for retried requests, 1 to 10 |
| `min_retry_delay` | 100 | Base backoff delay in milliseconds, at least 50 |
| `metrics_enabled` | True | Send observations to the `MetricsRecorder` passed to `GrayskullClientImpl` |
| `polling_interval_seconds` | 60 | Batch refresh interval |
| `heartbeat_enabled` | True | Send the client heartbeat |
| `heartbeat_interval_seconds` | 86400 | Heartbeat interval; 0 means the default |
| `workload_identity_resolver` | `DefaultWorkloadIdentityResolver` (hostname) | Value of the `Grayskull-Workload` header |

`add_default_header(key, value)` adds a header to every request; the SDK's own headers win on conflict.

## Tests

```bash
python -m venv .venv && . .venv/bin/activate
pip install -e '.[test]'
pytest
```

The suite runs against an in-process fake Grayskull server. `tests/test_usage_ledger.py` checks the heartbeat
payload against the shared fixture `clients/testdata/heartbeat/client_heartbeat_v1.json`.
