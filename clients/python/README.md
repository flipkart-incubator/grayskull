# Grayskull Python Client SDK

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](../../LICENSE)

The Python client for the Grayskull secret management service. Its behaviour matches the
[Go](../go/README.md) and [Java](../java/README.md) SDKs: the wire protocol, retry rules,
hook semantics, heartbeat schedule and metric names are all the same.

## Features

- **Simple API**: `get_secret`, `register_refresh_hook`, `close`, and context-manager support.
- **Refresh hooks**: a background poller batches 50 secrets per request. Updates coalesce to the newest version, delivery is at-most-once and never re-entrant for a given secret, and a 5-worker pool keeps a slow hook from blocking other secrets.
- **Heartbeat**: reports each held secret's version and whether it has a hook. It runs daily with jitter and backs off on failure.
- **Retries**: exponential backoff with jitter on network errors, 429 and 5xx. `Retry-After` is honoured.
- **Connection pooling**: uses `httpx`, with separate connect and read timeouts and keep-alive limits.
- **Workload identity**: sends the `Grayskull-Workload` header. The resolver is pluggable.
- **Pluggable auth**: any object with `get_auth_header() -> str`. Basic auth is built in.
- **Prometheus metrics**: optional extra. Metric names match Go. Labels are low-cardinality, and several clients can share a process safely.
- **Request correlation**: every call carries an `X-Request-Id`. A `contextvars` scope exposes it to your logs, and hooks run inside it.
- **Fork-safe**: pre-fork servers such as gunicorn work. Child processes get a fresh connection pool and restarted background threads.
- **Safe by default**: `repr(SecretValue)` masks the private part, and configuration is immutable and validated.
- **Typed**: ships `py.typed`. The public contracts in `grayskull.api` are `Protocol`s.

## Installation

```bash
pip install grayskull-client                # core
pip install "grayskull-client[prometheus]"  # + Prometheus metrics
```

Requires Python 3.9+.

## Quick start

```python
from grayskull import BasicAuthHeaderProvider, GrayskullClientConfig, GrayskullClientImpl

config = GrayskullClientConfig(host="https://grayskull.example.com")

with GrayskullClientImpl(BasicAuthHeaderProvider("user", "pass"), config) as client:
    secret = client.get_secret("my-project:db-password")
    print(secret.data_version, secret.public_part)   # the private part is in secret.private_part
```

Create **one client per process** and share it. It is thread-safe.

## Public API

```python
class GrayskullClient(Protocol):
    def get_secret(self, secret_ref: str) -> SecretValue: ...
    def register_refresh_hook(self, secret_ref: str, hook: Callable[[SecretValue], None]) -> RefreshHandlerRef: ...
    def close(self) -> None: ...
```

### `get_secret(secret_ref)`

`secret_ref` has the form `"projectId:secretName"`. The string is split on the **first** colon only, so secret names may contain colons.
Returns a frozen `SecretValue(data_version, public_part, private_part)`.

### `register_refresh_hook(secret_ref, hook)`

```python
def on_rotate(secret: SecretValue) -> None:
    pool.reconfigure(password=secret.private_part)

handle = client.register_refresh_hook("my-project:db-password", on_rotate)
...
handle.unregister()          # idempotent; handle.is_active -> False
```

- Hooks run on SDK worker threads. An exception is logged and does not affect other hooks.
- If you called `get_secret` first, its version seeds the hook, so the version you already hold is not delivered again.
- Polling runs every `polling_interval` seconds (default 60).

## Configuration

`GrayskullClientConfig` is an immutable dataclass validated on construction. Durations are in **seconds**.
Use `config.replace(read_timeout=5)` to derive a variant.

| Field | Default | Description |
|-------|---------|-------------|
| `host` | required | Grayskull base URL (`http://` or `https://`) |
| `connect_timeout` | `10.0` | Connect timeout |
| `read_timeout` | `30.0` | Read timeout |
| `max_connections` | `10` | Pool size |
| `max_keepalive_connections` | `10` | Idle connections kept |
| `keepalive_expiry` | `300.0` | Idle connection lifetime |
| `max_retries` | `3` | Total attempts for transient failures (1–10) |
| `min_retry_delay` | `0.1` | Base backoff |
| `max_retry_delay` | `60.0` | Backoff cap |
| `metrics_enabled` | `True` | Prometheus metrics (when the extra is installed) |
| `polling_interval` | `60.0` | Refresh-hook polling period |
| `heartbeat_enabled` | `True` | Heartbeat on/off |
| `heartbeat_interval` | `86400.0` | Heartbeat period (± 1/24 jitter) |
| `workload_identity_resolver` | hostname | Source of the `Grayskull-Workload` header |
| `default_headers` | `{}` | Extra headers. `Authorization` and `X-Request-Id` are always set by the SDK |

## Authentication

Any object with a thread-safe `get_auth_header() -> str` works:

```python
class BearerProvider:
    def __init__(self, token_source): self._source = token_source
    def get_auth_header(self) -> str: return f"Bearer {self._source.token()}"
```

`get_auth_header` is called for every request. Token-based providers should cache tokens and refresh them before they expire.

## Metrics

Install `grayskull-client[prometheus]`. Metrics go to the default registry; pass
`metrics_recorder=PrometheusMetricsRecorder(registry)` to use another one.

| Metric | Type | Labels |
|--------|------|--------|
| `grayskull_http_client_request_duration_seconds` | histogram (Go buckets) | `name`, `status_code` |
| `grayskull_http_client_retry_attempts_total` | counter | `url`, `success` |

The `name` label takes these values:
- `get_secret`, `batchGetSecrets`, `heartbeat` and `hook.execute`, which are operations.
- Route templates such as `/v1/projects/{projectId}/secrets/{secretName}/data`, one per HTTP attempt.

Concrete secret names never become label values.

Pass any object implementing `MetricsRecorder` (`record_request`, `record_retry`) to use another backend.

## Logging and request correlation

The SDK logs under the `grayskull` logger hierarchy and installs a `NullHandler`, so it is silent unless you configure logging.

```python
import logging
from grayskull import RequestIdLogFilter, request_scope

handler = logging.StreamHandler()
handler.addFilter(RequestIdLogFilter())
handler.setFormatter(logging.Formatter("%(asctime)s [%(grayskullRequestId)s] %(name)s %(message)s"))
logging.getLogger().addHandler(handler)

with request_scope("my-trace-id"):        # propagate your own id as X-Request-Id
    client.get_secret("p:s")
```

## Error handling

```python
from grayskull import GrayskullError, InvalidArgumentError

try:
    client.get_secret("p:s")
except InvalidArgumentError:      # bad input (also a ValueError)
    ...
except GrayskullError as e:       # everything else; e.status_code is the HTTP status, or 0
    ...
```

The SDK retries network errors, HTTP 429 and 5xx. It does not retry other 4xx responses or validation errors.

## Development

```bash
python -m venv .venv && . .venv/bin/activate
pip install -e ".[test]"
pytest
```

## License

Apache License 2.0. See [LICENSE](../../LICENSE).
