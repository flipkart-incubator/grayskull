"""Helpers shared by the test_parity_* ports of the Java client tests."""

import json
import threading
import time
from collections import deque
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Callable, Deque, Dict, List, Optional, Tuple

from grayskull import (BasicAuthHeaderProvider, GrayskullClientConfiguration, GrayskullClientImpl,
                       WorkloadIdentityResolver)
from grayskull.client_impl.auth.grayskull_auth_header_provider import GrayskullAuthHeaderProvider
from grayskull.client_impl.metrics.metrics_recorder import MetricsRecorder


def wait_until(predicate: Callable[[], bool], timeout: float = 5.0, message: str = "condition") -> None:
    """Poll predicate every 10ms until it is true; fail after timeout seconds."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(0.01)
    raise AssertionError(f"{message} not met within {timeout}s")


class RecordingMetrics(MetricsRecorder):
    """Keeps every observation so tests can assert on names, statuses and retries."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self.requests: List[Tuple[str, int, int]] = []
        self.retries: List[Tuple[str, int, bool]] = []

    def record_request(self, name: str, status_code: int, duration_ms: int) -> None:
        with self._lock:
            self.requests.append((name, status_code, duration_ms))

    def record_retry(self, url: str, attempt_number: int, success: bool) -> None:
        with self._lock:
            self.retries.append((url, attempt_number, success))

    def get_recorder_name(self) -> str:
        return "recording"

    def names(self) -> List[str]:
        with self._lock:
            return [name for name, _, _ in self.requests]

    def statuses(self, name: str) -> List[int]:
        with self._lock:
            return [status for n, status, _ in self.requests if n == name]


class StaticAuth(GrayskullAuthHeaderProvider):
    """Returns a fixed value (which may be invalid) or raises the given exception."""

    def __init__(self, header: Any = "Basic dTpw", error: Optional[BaseException] = None) -> None:
        self.header = header
        self.error = error
        self.calls = 0

    def get_auth_header(self) -> Any:
        self.calls += 1
        if self.error is not None:
            raise self.error
        return self.header


class StaticResolver(WorkloadIdentityResolver):
    """Returns a fixed workload identity and counts how often it was asked."""

    def __init__(self, identity: str) -> None:
        self.identity = identity
        self.calls = 0

    def resolve(self) -> str:
        self.calls += 1
        return self.identity


@dataclass
class Scripted:
    """One canned response. drop=True closes the connection without answering."""

    status: int = 200
    body: Any = None
    headers: Dict[str, str] = field(default_factory=dict)
    delay: float = 0.0
    drop: bool = False


@dataclass
class Seen:
    """One request as it arrived on the wire."""

    method: str
    path: str
    headers: List[Tuple[str, str]]
    body: bytes

    def header(self, name: str) -> Optional[str]:
        values = self.all(name)
        return values[0] if values else None

    def all(self, name: str) -> List[str]:
        return [v for k, v in self.headers if k.lower() == name.lower()]

    def json(self) -> Any:
        return json.loads(self.body.decode("utf-8")) if self.body else None


class ScriptedServer:
    """HTTP/1.1 server that answers each request with the next queued Scripted response.

    With nothing queued it answers 200 with an empty JSON object. A route function, when set,
    decides the response instead of the queue.
    """

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self.queue: Deque[Scripted] = deque()
        self.seen: List[Seen] = []
        self.route: Optional[Callable[[Seen], Scripted]] = None
        outer = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, format: str, *args: Any) -> None:
                return

            def _handle(self) -> None:
                length = int(self.headers.get("Content-Length") or 0)
                body = self.rfile.read(length) if length else b""
                seen = Seen(self.command, self.path, list(self.headers.items()), body)
                with outer._lock:
                    outer.seen.append(seen)
                    route = outer.route
                    reply = route(seen) if route else (outer.queue.popleft() if outer.queue else Scripted(body={}))
                if reply.delay:
                    time.sleep(reply.delay)
                if reply.drop:
                    self.close_connection = True
                    return
                if reply.body is None:
                    raw = b""
                elif isinstance(reply.body, bytes):
                    raw = reply.body
                else:
                    raw = json.dumps(reply.body).encode("utf-8")
                self.send_response(reply.status)
                for name, value in reply.headers.items():
                    self.send_header(name, value)
                self.send_header("Content-Length", str(len(raw)))
                self.end_headers()
                self.wfile.write(raw)

            do_GET = _handle
            do_POST = _handle

        self._server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self._server.daemon_threads = True
        self._thread = threading.Thread(target=self._server.serve_forever, args=(0.05,), daemon=True)
        self._thread.start()
        self.url = f"http://127.0.0.1:{self._server.server_address[1]}"

    def enqueue(self, *responses: Scripted) -> None:
        with self._lock:
            self.queue.extend(responses)

    def requests(self, path_suffix: str = "") -> List[Seen]:
        with self._lock:
            return [s for s in self.seen if s.path.split("?")[0].endswith(path_suffix)]

    def stop(self) -> None:
        self._server.shutdown()
        self._server.server_close()
        self._thread.join(5)


def secret_body(version: int, public: str = "pub", private: Optional[str] = "priv") -> Dict[str, Any]:
    """GET /data response envelope."""
    data: Dict[str, Any] = {"dataVersion": version, "publicPart": public}
    if private is not None:
        data["privatePart"] = private
    return {"data": data, "message": "ok"}


def batch_body(*updated: Tuple[str, str, int, str]) -> Dict[str, Any]:
    """POST /v1/secrets/batch response envelope for (project, name, version, public) tuples."""
    items = [{"projectId": p, "secretName": s, "dataVersion": v, "publicPart": pub} for p, s, v, pub in updated]
    return {"data": {"updatedCount": len(items), "updatedSecrets": items}, "message": "ok"}


class FakeApi:
    """Grayskull routes on top of a ScriptedServer.

    GET /data serves `secrets`; POST /v1/secrets/batch answers from `batch_replies` while any are
    queued, otherwise with every secret newer than the lastKnownVersion sent; the heartbeat answers
    from `heartbeat_replies`, then 204.
    """

    def __init__(self, web: ScriptedServer) -> None:
        self.web = web
        self.secrets: Dict[Tuple[str, str], Tuple[int, str, Optional[str]]] = {}
        self.get_replies: Deque[Scripted] = deque()
        self.batch_replies: Deque[Scripted] = deque()
        self.heartbeat_replies: Deque[Scripted] = deque()
        web.route = self._route

    def set_secret(self, project: str, name: str, version: int, public: str = "pub",
                   private: Optional[str] = "priv") -> None:
        self.secrets[(project, name)] = (version, public, private)

    def _route(self, seen: Seen) -> Scripted:
        from urllib.parse import unquote
        path = seen.path.split("?")[0]
        if seen.method == "GET" and path.endswith("/data"):
            if self.get_replies:
                return self.get_replies.popleft()
            parts = [unquote(p) for p in path.split("/")]
            entry = self.secrets.get((parts[3], parts[5])) if len(parts) == 7 else None
            if entry is None:
                return Scripted(404, {"message": "not found"})
            return Scripted(body=secret_body(*entry))
        if path == "/v1/secrets/batch":
            if self.batch_replies:
                return self.batch_replies.popleft()
            updated = []
            for item in (seen.json() or {}).get("secrets", []):
                entry = self.secrets.get((item["projectId"], item["secretName"]))
                if entry and entry[0] > item["lastKnownVersion"]:
                    updated.append((item["projectId"], item["secretName"], entry[0], entry[1]))
            return Scripted(body=batch_body(*updated))
        if path == "/v1/client/heartbeat":
            return self.heartbeat_replies.popleft() if self.heartbeat_replies else Scripted(204)
        return Scripted(404, {"message": "no route"})

    def batch_requests(self) -> List[Dict[str, Any]]:
        return [s.json() for s in self.web.requests("/v1/secrets/batch")]

    def heartbeats(self) -> List[Dict[str, Any]]:
        return [s.json() for s in self.web.requests("/v1/client/heartbeat")]


def new_client(host: str, auth: Optional[GrayskullAuthHeaderProvider] = None,
               metrics: Optional[MetricsRecorder] = None, **settings: Any) -> GrayskullClientImpl:
    """Client with polling effectively off (tests call poll_once) and heartbeat disabled unless asked."""
    config = dict(host=host, polling_interval_seconds=3600, heartbeat_enabled=False, min_retry_delay=50,
                  workload_identity_resolver=StaticResolver("pod-1"))
    config.update(settings)
    return GrayskullClientImpl(auth or BasicAuthHeaderProvider("u", "p"),
                               GrayskullClientConfiguration(**config), metrics)
