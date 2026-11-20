"""In-process fake of the Grayskull server endpoints the SDK calls."""

import json
import threading
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Dict, List, Optional, Tuple
from urllib.parse import unquote


@dataclass
class Recorded:
    method: str
    path: str
    headers: Dict[str, str]
    body: Optional[Dict[str, Any]]


@dataclass
class FakeGrayskullServer:
    """Serves secrets, the batch refresh endpoint and the heartbeat, and records every request."""

    secrets: Dict[Tuple[str, str], Tuple[int, str]] = field(default_factory=dict)
    heartbeat_status: int = 204
    heartbeat_statuses: List[int] = field(default_factory=list)  # consumed per request before heartbeat_status
    heartbeat_delay: float = 0.0
    fail_gets_remaining: int = 0
    fail_status: int = 503
    get_status: Dict[Tuple[str, str], int] = field(default_factory=dict)
    raw_get_body: Optional[bytes] = None
    requests: List[Recorded] = field(default_factory=list)
    _lock: threading.Lock = field(default_factory=threading.Lock)

    def set_secret(self, project: str, name: str, version: int, value: str) -> None:
        """Create or update a secret version on the server."""
        with self._lock:
            self.secrets[(project, name)] = (version, value)

    def recorded(self, path_suffix: str) -> List[Recorded]:
        """Return the recorded requests whose path ends with path_suffix."""
        with self._lock:
            return [r for r in self.requests if r.path.endswith(path_suffix)]

    def start(self, host: str = "127.0.0.1") -> "RunningServer":
        """Start serving on a free port of host (use 0.0.0.0 for containers)."""
        fake = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, format: str, *args: Any) -> None:  # silence the default stderr log
                return

            def _body(self) -> Optional[Dict[str, Any]]:
                length = int(self.headers.get("Content-Length") or 0)
                if length == 0:
                    return None
                return json.loads(self.rfile.read(length).decode("utf-8"))

            def _send(self, status: int, payload: Any = None) -> None:
                raw = b"" if payload is None else json.dumps(payload).encode("utf-8")
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(raw)))
                self.end_headers()
                self.wfile.write(raw)

            def _record(self, body: Optional[Dict[str, Any]]) -> None:
                with fake._lock:
                    fake.requests.append(Recorded(self.command, self.path, dict(self.headers), body))

            def do_GET(self) -> None:  # noqa: N802
                self._record(None)
                parts = [unquote(p) for p in self.path.split("/")]
                # /v1/projects/{p}/secrets/{s}/data
                if len(parts) == 7 and parts[1:3] == ["v1", "projects"] and parts[4] == "secrets" and parts[6] == "data":
                    with fake._lock:
                        if fake.fail_gets_remaining > 0:
                            fake.fail_gets_remaining -= 1
                            self._send(fake.fail_status, {"message": "transient"})
                            return
                        forced = fake.get_status.get((parts[3], parts[5]))
                        raw = fake.raw_get_body
                        entry = fake.secrets.get((parts[3], parts[5]))
                    if forced is not None:
                        self._send(forced, {"message": "forced"})
                        return
                    if raw is not None:
                        self.send_response(200)
                        self.send_header("Content-Length", str(len(raw)))
                        self.end_headers()
                        self.wfile.write(raw)
                        return
                    if entry is None:
                        self._send(404, {"message": "not found"})
                        return
                    version, value = entry
                    self._send(200, {"data": {"dataVersion": version, "publicPart": value}, "message": "ok"})
                    return
                self._send(404, {"message": "no route"})

            def do_POST(self) -> None:  # noqa: N802
                body = self._body()
                self._record(body)
                if self.path == "/v1/secrets/batch":
                    updated = []
                    with fake._lock:
                        for item in (body or {}).get("secrets", []):
                            entry = fake.secrets.get((item["projectId"], item["secretName"]))
                            if entry and entry[0] > item["lastKnownVersion"]:
                                version, value = entry
                                updated.append({
                                    "projectId": item["projectId"],
                                    "secretName": item["secretName"],
                                    "dataVersion": version,
                                    "publicPart": value,
                                })
                    self._send(200, {"data": {"updatedCount": len(updated), "updatedSecrets": updated}, "message": "ok"})
                    return
                if self.path == "/v1/client/heartbeat":
                    with fake._lock:
                        status = fake.heartbeat_statuses.pop(0) if fake.heartbeat_statuses else fake.heartbeat_status
                        delay = fake.heartbeat_delay
                    if delay:
                        import time as _time
                        _time.sleep(delay)
                    self._send(status)
                    return
                self._send(404, {"message": "no route"})

        server = ThreadingHTTPServer((host, 0), Handler)
        server.daemon_threads = True
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        return RunningServer(server, thread)


class RunningServer:
    """A started FakeGrayskullServer; exposes its base URL and stops it."""

    def __init__(self, server: ThreadingHTTPServer, thread: threading.Thread) -> None:
        self._server = server
        self._thread = thread
        self.port = server.server_address[1]
        self.url = f"http://127.0.0.1:{self.port}"

    def stop(self) -> None:
        """Shut the server down and release its socket."""
        self._server.shutdown()
        self._server.server_close()
        self._thread.join(5)
