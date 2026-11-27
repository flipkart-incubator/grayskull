# Grayskull Python Client

Python client for Grayskull. It reads secrets, calls your refresh hooks when a secret changes, and sends the
client heartbeat that tells the server which secret versions a workload holds.

The runtime uses the standard library only. Prometheus metrics are optional.

Requires Python 3.10 or later.

## Contents

- [Installation](#installation)
- [Quick start](#quick-start)
- [Configuration](#configuration)
- [Authentication](#authentication)
- [Refresh hooks](#refresh-hooks)
- [Heartbeat](#heartbeat)
- [Identity headers](#identity-headers)
- [Connections, timeouts and retries](#connections-timeouts-and-retries)
- [Error handling](#error-handling)
- [Metrics](#metrics)
- [Logging](#logging)
- [Forking servers (gunicorn, celery)](#forking-servers-gunicorn-celery)
- [Package layout](#package-layout)
- [Tests](#tests)

## Installation

```bash
pip install grayskull-client               # standard library only
pip install 'grayskull-client[prometheus]' # adds Prometheus metrics
```

## Quick start

```python
from grayskull import BasicAuthHeaderProvider, GrayskullClientConfiguration, GrayskullClientImpl

configuration = GrayskullClientConfiguration(host="https://grayskull.example.com")
with GrayskullClientImpl(BasicAuthHeaderProvider("user", "password"), configuration) as client:
    secret = client.get_secret("myproject:db-password")
    print(secret.data_version, secret.public_part)  # secret.private_part holds the secret itself

    def on_update(value):
        print("new version", value.data_version)

    handler_ref = client.register_refresh_hook("myproject:db-password", on_update)
    ...
    handler_ref.unregister()
```

Create one client per process and share it. The client is thread-safe. Call `close()` when you are done, or use
it as a context manager.

Secret references have the form `projectId:secretName`. Both parts are percent-encoded as single path segments
(every character except letters, digits and `_.-~`), so spaces, `?`, `#`, `+`, `:` and non-ASCII characters reach
the server intact. The server's default Spring Security firewall rejects encoded `/` (`%2F`), encoded `%` (`%25`)
and `;` in a path, so names containing `/`, `%` or `;` cannot be read by any of the SDKs.

## Configuration

Every field is checked when it is set, both in the constructor and on later assignment. An invalid value raises
`ValueError`. Integer fields do not accept `bool`, floats, `NaN` or infinity.

| Field | Type | Default | Allowed | Meaning |
|---|---|---|---|---|
| `host` | str | required | `http://` or `https://` URL | Grayskull base URL; a trailing `/` is removed |
| `connection_timeout` | int | 10000 | > 0 | TCP and TLS connect timeout in milliseconds |
| `read_timeout` | int | 30000 | > 0 | Longest wait for the next bytes of a response, in milliseconds |
| `max_connections` | int | 10 | > 0 | Connections in use at once; requests beyond this wait for a free one |
| `max_retries` | int | 3 | 1 to 10 | Total attempts for retried requests |
| `min_retry_delay` | int | 100 | >= 50 | Base backoff delay in milliseconds |
| `metrics_enabled` | bool | True | | Record metrics (see [Metrics](#metrics)) |
| `polling_interval_seconds` | int | 60 | >= 1 | How often to check for new versions of secrets that have hooks |
| `heartbeat_enabled` | bool | True | | Send the client heartbeat |
| `heartbeat_interval_seconds` | int | 86400 | >= 0 | Heartbeat interval; 0 means the 24-hour default |
| `workload_identity_resolver` | `WorkloadIdentityResolver` | hostname | | Value of the `Grayskull-Workload` header |

```python
configuration = GrayskullClientConfiguration(
    host="https://grayskull.example.com",
    connection_timeout=5_000,
    read_timeout=10_000,
    max_retries=5,
    polling_interval_seconds=30,
)
configuration.add_default_header("X-Team", "payments")  # sent on every request
```

`add_default_header(key, value)` adds a header to every request. The SDK's own headers win on conflict.

## Authentication

`BasicAuthHeaderProvider(username, password)` sends HTTP Basic credentials.

For anything else, such as a rotating bearer token, implement `GrayskullAuthHeaderProvider`:

```python
from grayskull import GrayskullAuthHeaderProvider

class BearerTokenProvider(GrayskullAuthHeaderProvider):
    def __init__(self, token_source):
        self._token_source = token_source

    def get_auth_header(self) -> str:
        return f"Bearer {self._token_source.current_token()}"
```

`get_auth_header()` is called on every request, retries included. If it returns `None`, an empty string or only
whitespace, the request is not sent and `GrayskullException` is raised with status 0, without retries. If it
raises, the error is wrapped in `GrayskullException`.

## Refresh hooks

`register_refresh_hook(secret_ref, hook)` calls `hook(value)` with each newer version of the secret. The client
checks for new versions every `polling_interval_seconds` with one `POST /v1/secrets/batch` call per 50 secrets.

- Hooks run on 5 dispatcher threads. For any one secret, only one delivery runs at a time.
- Delivery is at most once. A version the client already holds when you register is not delivered again.
- If a hook raises, the error is logged and the other hooks still run.
- `RefreshHandlerRef.unregister()` removes the hook. It is safe to call more than once.
- `close()` waits up to 10 seconds for running hooks, then logs a warning and returns. The dispatcher threads are
  daemon threads, so a stuck hook does not stop the interpreter from exiting.

## Heartbeat

Every `heartbeat_interval_seconds` (default 24 hours, with ±1/24 jitter) the client posts the secrets it holds to
`POST /v1/client/heartbeat`, in chunks of 50:

```json
{"secrets": [{"projectId": "p", "secretName": "s", "version": 3, "refreshHookAppliedVersion": 3}]}
```

`refreshHookAppliedVersion` is present while a refresh hook is registered. It is the newest version every
registered hook handled, or 0 when nothing has been delivered since registration.

- The first heartbeat goes out after one interval, not at startup.
- Each chunk is sent once, without inline retries.
- If a heartbeat fails, it is retried once after about an hour (±10%). If that also fails, the client waits for
  the next full interval. This matches the Java and Go SDKs.
- A 404, 405 or 501 response means the server does not support heartbeats yet. This is logged once and the client
  tries again after the full interval.

## Identity headers

Every request carries:

| Header | Value |
|---|---|
| `Authorization` | From the `GrayskullAuthHeaderProvider` |
| `Grayskull-Workload` | From `workload_identity_resolver`; by default the hostname, or `UNKNOWN` if it cannot be read |
| `User-Agent` | `grayskull-python/<version>` |
| `X-Request-Id` | A UUID per call. A batch poll cycle uses one id for all its chunks and retries |

Header values must be ISO-8859-1. A value outside it, or one with a line break, raises a non-retryable
`GrayskullException` before anything is sent.

## Connections, timeouts and retries

- Connections are kept alive and reused. A connection idle for 5 minutes is not reused; it is closed the next
  time the pool is used, and all idle connections are closed on `close()`.
- `connection_timeout` covers connecting. `read_timeout` covers each wait for response bytes. Each attempt also has
  an overall limit of `connection_timeout + read_timeout`, so a server that sends one byte at a time cannot keep
  a call open.
- Redirects are not followed. A 3xx response raises `GrayskullException` with that status, so credentials are
  never sent to another host.
- `HTTP_PROXY`, `HTTPS_PROXY` and similar environment variables are ignored, as in the Go SDK.
- `get_secret` and the batch poll retry network errors, truncated or malformed responses, 429 and 5xx, with
  exponential backoff from `min_retry_delay` plus jitter, up to `max_retries` attempts. Other 4xx responses are not
  retried. Heartbeats are not retried inline.

## Error handling

| Exception | When |
|---|---|
| `ValueError` | Invalid configuration, a malformed secret reference, or a `None` hook |
| `GrayskullException` | A request failed. `status_code` is the HTTP status, or 0 when no response was received |
| `RuntimeError` | `register_refresh_hook` after `close()` |

```python
from grayskull import GrayskullException

try:
    secret = client.get_secret("myproject:db-password")
except GrayskullException as exc:
    if exc.status_code == 404:
        ...  # no such secret
    elif exc.status_code == 0:
        ...  # network failure after all retries
    raise
```

`SecretValue` leaves `private_part` out of `repr()`, so logging a value or printing it in a traceback does not show
the secret.

## Metrics

With `metrics_enabled=True` (the default):

- If you pass a `MetricsRecorder` to `GrayskullClientImpl`, it is used.
- Otherwise, if `prometheus_client` is installed, `PrometheusMetricsRecorder` records into the default registry.
- Otherwise nothing is recorded, and the client logs this at INFO when it starts.

With `metrics_enabled=False`, nothing is recorded.

The Prometheus metric names and labels match the Go SDK:

| Metric | Type | Labels |
|---|---|---|
| `grayskull_http_client_request_duration_seconds` | histogram | `name`, `status_code` |
| `grayskull_http_client_retry_attempts_total` | counter | `url`, `success` |

`name` is one of:

| `name` | Recorded for |
|---|---|
| `getSecret.<projectId>:<secretName>` | Each `get_secret` call, once, including its retries |
| `batchGetSecrets` | Each batch poll chunk |
| `heartbeat` | Each heartbeat chunk |
| `hook.execute.<projectId>:<secretName>` | Each hook run; status 200 on success, 500 if it raised |

`status_code` is the HTTP status, or 0 when no response was received. The retry counter goes up once for each call
that needed more than one attempt.

To expose the metrics:

```python
from prometheus_client import start_http_server
start_http_server(9090)
```

To send metrics elsewhere, implement `MetricsRecorder` (`record_request`, `record_retry`, `get_recorder_name`)
and pass it to `GrayskullClientImpl`.

## Logging

The SDK logs through the standard `logging` module under the `grayskull` logger. It adds no handlers.

```python
import logging
logging.getLogger("grayskull").setLevel(logging.INFO)
```

SDK log lines written while a request is in progress, including those from the poller, the heartbeat and the
hook dispatcher, start with `[RequestId:<id>]`, the same id sent in `X-Request-Id`. The id is also set as `record.request_id`
(`-` when there is none). Other loggers do not set this attribute, so give the formatter a default if you use it:

```python
handler = logging.StreamHandler()
handler.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(name)s %(request_id)s %(message)s",
                                       defaults={"request_id": "-"}))
logging.getLogger("grayskull").addHandler(handler)
```

## Forking servers (gunicorn, celery)

Threads do not survive `os.fork()`. If a client is created before the server forks its workers, the client restarts
its poller, heartbeat and dispatcher threads in each child, and the child opens its own connections instead of
reusing the parent's. A client closed before the fork stays closed in the child.

Creating the client after the fork, for example in gunicorn's `post_fork` hook, also works.

## Package layout

| Package | Contents |
|---|---|
| `grayskull.client_api` | `GrayskullClient`, `RefreshHandlerRef`, `SecretRefreshHook`, `SecretValue`, `WorkloadIdentityResolver`, `GrayskullHeaders` |
| `grayskull.client_impl` | `GrayskullClientImpl`, `GrayskullHttpClient`, `HookRefreshPoller`, `HeartbeatScheduler`, `UsageLedger` |
| `grayskull.client_impl.auth` | `GrayskullAuthHeaderProvider`, `BasicAuthHeaderProvider` |
| `grayskull.client_impl.models` | `GrayskullClientConfiguration`, request and response models, `GrayskullException`, `RetryableException` |
| `grayskull.client_impl.metrics` | `MetricsRecorder`, `NoopMetricsRecorder`, `PrometheusMetricsRecorder` |
| `grayskull.client_impl.workload` | `DefaultWorkloadIdentityResolver` |

## Tests

```bash
python -m venv .venv && . .venv/bin/activate
pip install -e '.[test]'
pytest
```

The unit tests run against an in-process fake Grayskull server.

- `tests/test_parity_*.py` port the Java client tests class by class (for example
  `test_parity_grayskull_http_client.py` ports `GrayskullHttpClientTest`). Each file's docstring lists any Java
  test that has no Python equivalent, and why.
- `tests/test_golden_wire.py` checks requests and responses against the shared wire fixtures in
  `clients/testdata` (see `clients/testdata/wire/README.md`).
- `tests/test_review_regressions.py` holds one or more tests for each item of the PR #92 review.

### Integration tests

`it/` runs the client against the real server: MongoDB (as a single-node replica set) and the `simple-app` jar
are started in Docker with Testcontainers. It needs Docker and a built server jar:

```bash
./mvnw -pl simple-app -am package -DskipTests   # from the repository root
pip install -e '.[it]'
pytest it
```

| Variable | Effect |
| --- | --- |
| `GRAYSKULL_SERVER_JAR` | Server jar to run; default is the newest `simple-app/target/simple-app-*.jar` |
| `GRAYSKULL_IT_SERVER_URL` | Use a server that is already running instead of starting containers |
| `GRAYSKULL_IT_REQUIRED=1` | Fail instead of skipping when Docker or the jar is missing (set in CI) |
| `GRAYSKULL_IT_HEARTBEAT_STUB=0` | Send heartbeats to the server; by default a local stub answers them, since the server has no heartbeat endpoint yet |

The suite also checks that the server's responses still carry every field in the shared wire fixtures.

The package version is defined once, in `src/grayskull/version.py`. `pyproject.toml` reads it from there, and it
is sent in the `User-Agent` header.
