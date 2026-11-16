from __future__ import annotations

import json
import threading
from typing import Any, Callable, Dict, List, Optional, Tuple

import httpx
import pytest

from grayskull import (
    BasicAuthHeaderProvider,
    GrayskullClientConfig,
    GrayskullClientImpl,
    NoopMetricsRecorder,
    StaticWorkloadIdentityResolver,
)

Handler = Callable[[httpx.Request], httpx.Response]


class FakeServer:
    """Routes requests to per-(method, path-prefix) handlers and records every request."""

    def __init__(self) -> None:
        self.requests: List[httpx.Request] = []
        self._routes: List[Tuple[str, str, Handler]] = []
        self._lock = threading.Lock()

    def on(self, method: str, prefix: str, handler: Handler) -> None:
        self._routes.append((method, prefix, handler))

    def __call__(self, request: httpx.Request) -> httpx.Response:
        with self._lock:
            self.requests.append(request)
        for method, prefix, handler in self._routes:
            if request.method == method and request.url.raw_path.decode().startswith(prefix):
                return handler(request)
        return httpx.Response(404, text="no route")

    def matching(self, method: str, prefix: str) -> List[httpx.Request]:
        with self._lock:
            return [r for r in self.requests if r.method == method and r.url.raw_path.decode().startswith(prefix)]


def json_response(status: int, data: Any = None, **headers: str) -> httpx.Response:
    return httpx.Response(status, json={"data": data, "message": "ok"}, headers=headers)


def body(request: httpx.Request) -> Dict[str, Any]:
    return json.loads(request.content)


class RecordingMetrics(NoopMetricsRecorder):
    def __init__(self) -> None:
        self.requests: List[Tuple[str, int]] = []
        self.retries: List[Tuple[str, int, bool]] = []

    def record_request(self, name: str, status_code: int, duration_seconds: float) -> None:
        self.requests.append((name, status_code))

    def record_retry(self, url: str, attempts: int, success: bool) -> None:
        self.retries.append((url, attempts, success))


@pytest.fixture
def server() -> FakeServer:
    return FakeServer()


@pytest.fixture
def make_client(server: FakeServer):
    created: List[GrayskullClientImpl] = []

    def factory(metrics: Optional[RecordingMetrics] = None, **overrides: Any) -> GrayskullClientImpl:
        settings: Dict[str, Any] = dict(
            host="https://grayskull.test",
            min_retry_delay=0.0,
            heartbeat_enabled=False,
            metrics_enabled=False,
            workload_identity_resolver=StaticWorkloadIdentityResolver("test-workload"),
        )
        settings.update(overrides)
        client = GrayskullClientImpl(
            BasicAuthHeaderProvider("user", "pass"),
            GrayskullClientConfig(**settings),
            metrics_recorder=metrics,
            http_transport=httpx.MockTransport(server),
        )
        created.append(client)
        return client

    yield factory
    for client in created:
        client.close()
