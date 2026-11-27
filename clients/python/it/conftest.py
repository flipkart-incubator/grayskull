"""Integration fixtures: a real Grayskull server (the simple-app jar) and MongoDB, started with Testcontainers.

Run from clients/python:

    pip install -e '.[it]'
    pytest it

Needs Docker and a built server jar: `./mvnw -pl simple-app -am package -DskipTests` at the repo root,
or point GRAYSKULL_SERVER_JAR at a jar. Without Docker or a jar the tests are skipped, unless
GRAYSKULL_IT_REQUIRED=1 (set in CI), which turns that into a failure.

The server has no POST /v1/client/heartbeat yet, so the client talks to the server through
HeartbeatStubProxy: it forwards every request unchanged and answers the heartbeat itself, recording it.
Set GRAYSKULL_IT_HEARTBEAT_STUB=0 once the endpoint ships to send heartbeats to the real server.
"""

import base64
import glob
import http.client
import json
import os
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple
from urllib.parse import quote, urlsplit

import pytest
from it_support import ADMIN, REPO_ROOT

MONGO_IMAGE = os.environ.get("GRAYSKULL_IT_MONGO_IMAGE", "mongo:7.0")
JAVA_IMAGE = os.environ.get("GRAYSKULL_IT_JAVA_IMAGE", "eclipse-temurin:21-jre")
SERVER_START_TIMEOUT = float(os.environ.get("GRAYSKULL_IT_START_TIMEOUT", "240"))


def _unavailable(reason: str) -> None:
    if os.environ.get("GRAYSKULL_IT_REQUIRED") == "1":
        pytest.fail(f"integration suite cannot run: {reason}")
    pytest.skip(reason)


def _server_jar() -> Path:
    configured = os.environ.get("GRAYSKULL_SERVER_JAR")
    if configured:
        jar = Path(configured)
        if not jar.is_file():
            _unavailable(f"GRAYSKULL_SERVER_JAR={configured} does not exist")
        return jar
    jars = sorted(p for p in glob.glob(str(REPO_ROOT / "simple-app" / "target" / "simple-app-*.jar"))
                  if not p.endswith(".original"))
    if not jars:
        _unavailable("no simple-app jar; run ./mvnw -pl simple-app -am package -DskipTests")
    return Path(jars[-1])


def _docker_available() -> bool:
    try:
        import docker
        docker.from_env().ping()
        return True
    except Exception:
        return False


def basic(user: str) -> str:
    return "Basic " + base64.b64encode(f"{user}:any".encode()).decode()


class AdminApi:
    """Direct calls to the server for test setup and for raw wire checks (not through the SDK)."""

    def __init__(self, url: str) -> None:
        self.url = url

    def call(self, method: str, path: str, body: Optional[Dict[str, Any]] = None,
             user: str = ADMIN) -> Tuple[int, Any]:
        data = json.dumps(body).encode() if body is not None else None
        request = urllib.request.Request(self.url + path, data=data, method=method, headers={
            "Authorization": basic(user), "Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                raw = response.read()
                return response.status, json.loads(raw) if raw else None
        except urllib.error.HTTPError as exc:
            raw = exc.read()
            try:
                return exc.code, json.loads(raw) if raw else None
            except ValueError:
                return exc.code, raw.decode("utf-8", "replace")

    def create_secret(self, project: str, name: str, public: str, private: str) -> None:
        status, body = self.call("POST", f"/v1/projects/{quote(project, safe='')}/secrets", {
            "name": name, "provider": "SELF", "data": {"publicPart": public, "privatePart": private}})
        assert status == 200, (status, body)

    def upgrade_secret(self, project: str, name: str, public: str, private: str) -> None:
        path = f"/v1/projects/{quote(project, safe='')}/secrets/{quote(name, safe='')}/data"
        status, body = self.call("POST", path, {"publicPart": public, "privatePart": private})
        assert status == 200, (status, body)


class HeartbeatStubProxy:
    """Forwards requests to the server byte for byte, except the heartbeat, which it records and answers."""

    def __init__(self, upstream: str, stub_heartbeat: bool) -> None:
        self.heartbeats: List[Tuple[Dict[str, str], Any]] = []
        self.heartbeat_status = 204
        self._lock = threading.Lock()
        target = urlsplit(upstream)
        proxy = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, format: str, *args: Any) -> None:
                return

            def _handle(self) -> None:
                length = int(self.headers.get("Content-Length") or 0)
                body = self.rfile.read(length) if length else b""
                if stub_heartbeat and self.command == "POST" and self.path == "/v1/client/heartbeat":
                    with proxy._lock:
                        proxy.heartbeats.append((dict(self.headers.items()), json.loads(body or b"null")))
                        status = proxy.heartbeat_status
                    self.send_response(status)
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return
                conn = http.client.HTTPConnection(target.hostname, target.port, timeout=60)
                try:
                    headers = {k: v for k, v in self.headers.items() if k.lower() not in ("host", "connection")}
                    conn.request(self.command, self.path, body=body or None, headers=headers)
                    response = conn.getresponse()
                    payload = response.read()
                    self.send_response(response.status)
                    for name, value in response.getheaders():
                        if name.lower() not in ("transfer-encoding", "connection", "content-length"):
                            self.send_header(name, value)
                    self.send_header("Content-Length", str(len(payload)))
                    self.end_headers()
                    self.wfile.write(payload)
                finally:
                    conn.close()

            do_GET = _handle
            do_POST = _handle

        self._server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self._server.daemon_threads = True
        self._thread = threading.Thread(target=self._server.serve_forever, args=(0.05,), daemon=True)
        self._thread.start()
        self.url = f"http://127.0.0.1:{self._server.server_address[1]}"

    def recorded(self) -> List[Tuple[Dict[str, str], Any]]:
        with self._lock:
            return list(self.heartbeats)

    def stop(self) -> None:
        self._server.shutdown()
        self._server.server_close()


def _start_mongo(network):
    from testcontainers.core.container import DockerContainer

    mongo = (DockerContainer(MONGO_IMAGE)
             .with_network(network)
             .with_network_aliases("mongo")
             .with_command("--replSet rs0 --bind_ip_all"))
    mongo.start()
    deadline = time.monotonic() + 60
    initiate = "try { rs.status().ok } catch (e) { rs.initiate({_id: 'rs0', members: [{_id: 0, host: 'mongo:27017'}]}).ok }"
    while time.monotonic() < deadline:
        code, output = mongo.exec(["mongosh", "--quiet", "--eval", initiate])
        if code == 0:
            code, output = mongo.exec(["mongosh", "--quiet", "--eval", "db.hello().isWritablePrimary"])
            if code == 0 and output.strip().endswith(b"true"):
                return mongo
        time.sleep(1)
    mongo.stop()
    raise RuntimeError(f"MongoDB replica set did not become primary: {output!r}")


def _start_server(network, jar: Path):
    from testcontainers.core.container import DockerContainer

    server = (DockerContainer(JAVA_IMAGE)
              .with_network(network)
              .with_volume_mapping(str(jar.resolve()), "/app/server.jar", "ro")
              .with_env("SPRING_DATA_MONGODB_URI", "mongodb://mongo:27017/grayskull-it?replicaSet=rs0")
              .with_env("SPRING_DATA_MONGODB_DATABASE", "grayskull-it")
              .with_env("AUDIT_DERBY_DIRECTORY", "/tmp/derby")
              .with_exposed_ports(8080)
              .with_command("java -jar /app/server.jar"))
    server.start()
    url = f"http://{server.get_container_host_ip()}:{server.get_exposed_port(8080)}"
    deadline = time.monotonic() + SERVER_START_TIMEOUT
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(url + "/actuator/health", timeout=2) as response:
                if json.loads(response.read()).get("status") == "UP":
                    return server, url
        except Exception:
            pass
        container = server.get_wrapped_container()
        container.reload()
        if container.status == "exited":
            break
        time.sleep(2)
    logs = b"".join(server.get_logs()).decode("utf-8", "replace")[-4000:]
    server.stop()
    raise RuntimeError(f"Grayskull server did not become healthy within {SERVER_START_TIMEOUT}s:\n{logs}")


@pytest.fixture(scope="session")
def grayskull_url():
    """Base URL of a running Grayskull server backed by MongoDB."""
    external = os.environ.get("GRAYSKULL_IT_SERVER_URL")  # reuse a server you started yourself
    if external:
        yield external.rstrip("/")
        return
    jar = _server_jar()
    if not _docker_available():
        _unavailable("Docker is not reachable")
    from testcontainers.core.network import Network

    with Network() as network:
        mongo = _start_mongo(network)
        try:
            server, url = _start_server(network, jar)
            try:
                yield url
            finally:
                server.stop()
        finally:
            mongo.stop()


@pytest.fixture(scope="session")
def admin(grayskull_url):
    return AdminApi(grayskull_url)


@pytest.fixture
def proxy(grayskull_url):
    stub = HeartbeatStubProxy(grayskull_url, os.environ.get("GRAYSKULL_IT_HEARTBEAT_STUB", "1") != "0")
    yield stub
    stub.stop()


@pytest.fixture
def unique():
    """A fresh project id per test, so tests never see each other's secrets."""
    return f"it-{time.time_ns()}"

