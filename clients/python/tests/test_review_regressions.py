"""Regression tests for the PR #92 review items; each test names the item it covers."""

import json
import logging
import math
import os
import socket
import threading
import time
import warnings
from importlib.metadata import version as package_version
from typing import Callable, List

import pytest

from grayskull import (BasicAuthHeaderProvider, DefaultWorkloadIdentityResolver, GrayskullClientConfiguration,
                       GrayskullClientImpl, GrayskullException, NoopMetricsRecorder, SecretValue, __version__)
from grayskull.client_impl.auth.grayskull_auth_header_provider import GrayskullAuthHeaderProvider
from grayskull.client_impl import grayskull_http_client as http_module
from grayskull.client_impl.grayskull_http_client import GrayskullHttpClient, _ConnectionPool
from grayskull.client_impl.heartbeat_scheduler import HeartbeatOutcome, HeartbeatScheduler
from grayskull.client_impl.hook_refresh_poller import HookRefreshPoller
from grayskull.client_impl.metrics.metrics_recorder import MetricsRecorder
from grayskull.client_impl.metrics.prometheus_metrics_recorder import PrometheusMetricsRecorder
from grayskull.client_impl.models.exceptions.retryable_exception import RetryableException
from grayskull.client_impl.models.request.heartbeat_request import HeldSecret
from grayskull.client_impl.usage_ledger import UsageLedger


class RecordingMetrics(MetricsRecorder):
    def __init__(self) -> None:
        self.requests: List[tuple] = []
        self.retries: List[tuple] = []

    def record_request(self, name, status_code, duration_ms):
        self.requests.append((name, status_code))

    def record_retry(self, url, attempt_number, success):
        self.retries.append((attempt_number, success))

    def get_recorder_name(self):
        return "recording"


class StaticAuth(GrayskullAuthHeaderProvider):
    def __init__(self, header) -> None:
        self.header = header

    def get_auth_header(self):
        return self.header


def http_client(url, auth=None, metrics=None, workload="pod-1", **config) -> GrayskullHttpClient:
    settings = dict(host=url, min_retry_delay=50)
    settings.update(config)
    configuration = GrayskullClientConfiguration(**settings)
    configuration.add_default_header("Grayskull-Workload", workload)
    return GrayskullHttpClient(auth or BasicAuthHeaderProvider("u", "p"), configuration, metrics or NoopMetricsRecorder())


class RawServer:
    """A TCP server that answers each connection with handler(conn); for responses http.server cannot produce."""

    def __init__(self, handler: Callable[[socket.socket], None]) -> None:
        self._sock = socket.socket()
        self._sock.bind(("127.0.0.1", 0))
        self._sock.listen(16)
        self.url = f"http://127.0.0.1:{self._sock.getsockname()[1]}"
        self.connections = 0
        self._handler = handler
        threading.Thread(target=self._serve, daemon=True).start()

    def _serve(self) -> None:
        while True:
            try:
                conn, _ = self._sock.accept()
            except OSError:
                return
            self.connections += 1
            threading.Thread(target=self._handle, args=(conn,), daemon=True).start()

    def _handle(self, conn: socket.socket) -> None:
        try:
            self._handler(conn)
        except OSError:
            pass
        finally:
            conn.close()

    def close(self) -> None:
        self._sock.close()


def read_request(conn: socket.socket) -> bytes:
    data = b""
    while b"\r\n\r\n" not in data:
        chunk = conn.recv(65536)
        if not chunk:
            return data
        data += chunk
    head, _, rest = data.partition(b"\r\n\r\n")
    length = 0
    for line in head.split(b"\r\n"):
        if line.lower().startswith(b"content-length:"):
            length = int(line.split(b":")[1])
    while len(rest) < length:
        rest += conn.recv(65536)
    return data


# 1. Redirects are not followed, so Authorization never reaches another host.
def test_1_redirect_is_not_followed_and_auth_is_not_forwarded(server):
    stolen: List[bytes] = []
    other = RawServer(lambda c: stolen.append(read_request(c)))

    def redirect(conn):
        read_request(conn)
        conn.sendall(f"HTTP/1.1 302 Found\r\nLocation: {other.url}/steal\r\nContent-Length: 0\r\n\r\n".encode())

    redirector = RawServer(redirect)
    with pytest.raises(GrayskullException) as info:
        http_client(redirector.url).do_get_with_retry(f"{redirector.url}/v1/x", "r")
    assert info.value.status_code == 302
    assert "not followed" in str(info.value)
    time.sleep(0.1)
    assert stolen == [] and other.connections == 0
    redirector.close()
    other.close()


# 2. A None, empty or blank auth header is rejected before sending, without retries.
@pytest.mark.parametrize("header", [None, "", "   ", 42])
def test_2_null_or_blank_auth_header_is_rejected_before_sending(server, header):
    with pytest.raises(GrayskullException) as info:
        http_client(server.url, auth=StaticAuth(header)).do_get_with_retry(f"{server.url}/v1/x", "r")
    assert info.value.status_code == 0
    assert server.requests == []


# 4. Intervals must be finite integers; every field is validated on assignment too.
@pytest.mark.parametrize("field,value", [
    ("polling_interval_seconds", math.nan),
    ("polling_interval_seconds", math.inf),
    ("polling_interval_seconds", 0.001),
    ("polling_interval_seconds", 0),
    ("polling_interval_seconds", True),
    ("polling_interval_seconds", "60"),
    ("heartbeat_interval_seconds", math.nan),
    ("heartbeat_interval_seconds", -1),
    ("heartbeat_interval_seconds", False),
    ("connection_timeout", 1.5),
    ("max_retries", True),
    ("max_connections", 0),
    ("metrics_enabled", 1),
])
def test_4_invalid_values_are_rejected_at_construction_and_on_assignment(field, value):
    with pytest.raises(ValueError):
        GrayskullClientConfiguration(host="http://x", **{field: value})
    config = GrayskullClientConfiguration(host="http://x")
    with pytest.raises(ValueError):
        setattr(config, field, value)


def test_4_assignment_normalises_like_construction():
    config = GrayskullClientConfiguration(host="http://x")
    config.heartbeat_interval_seconds = 0
    config.host = "https://gs.example/"
    assert config.heartbeat_interval_seconds == 86400 and config.host == "https://gs.example"
    assert config.polling_interval_seconds == 60 and config.max_connections == 10


# 5. An unexpected error in one batch chunk does not stop the other chunks.
def test_5_unexpected_error_in_one_chunk_does_not_abort_the_cycle():
    calls: List[str] = []

    class FlakyHttp:
        def do_post_with_retry(self, url, body, request_id):
            calls.append(request_id)
            if len(calls) == 1:
                raise KeyError("dataVersion")
            raise GrayskullException(503, "down")

    poller = HookRefreshPoller(FlakyHttp(), "http://gs", 3600, UsageLedger(), NoopMetricsRecorder())
    for i in range(120):
        poller.register("p", f"s{i}", lambda v: None, 0)
    poller.poll_once()
    assert len(calls) == 3
    poller.close()


# 6. Malformed or truncated responses are wrapped and retried.
@pytest.mark.parametrize("response", [
    b"HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\nshort",  # IncompleteRead
    b"NOT-HTTP garbage\r\n\r\n",  # BadStatusLine
    b"",  # RemoteDisconnected
])
def test_6_truncated_or_malformed_responses_are_retried(response):
    def handler(conn):
        read_request(conn)
        conn.sendall(response)

    raw = RawServer(handler)
    with pytest.raises(GrayskullException) as info:
        http_client(raw.url, max_retries=2).do_get_with_retry(f"{raw.url}/v1/x", "r")
    assert info.value.status_code == 0
    assert isinstance(info.value.__cause__, RetryableException)
    assert raw.connections >= 2, "retried on a new connection"
    raw.close()


# 7. A header that cannot be encoded is a non-retryable GrayskullException.
def test_7_non_latin1_workload_header_is_not_retryable(server):
    with pytest.raises(GrayskullException) as info:
        http_client(server.url, workload="pod-日本").do_get_with_retry(f"{server.url}/v1/x", "r")
    assert info.value.status_code == 0
    assert "Grayskull-Workload" in str(info.value)
    assert server.requests == []


def test_7_auth_value_is_never_echoed_in_errors(server):
    with pytest.raises(GrayskullException) as info:
        http_client(server.url, auth=StaticAuth("Basic sëcret-日本")).do_get_with_retry(f"{server.url}/v1/x", "r")
    assert "sëcret" not in str(info.value)


# 8. Any exception in one heartbeat chunk only fails that chunk.
def test_8_unexpected_heartbeat_error_does_not_abort_other_chunks():
    calls: List[str] = []

    class FlakyHttp:
        def do_post(self, url, body, request_id):
            calls.append(request_id)
            if len(calls) == 1:
                raise KeyError("boom")
            return type("R", (), {"status_code": 204})()

    held = [HeldSecret("p", f"s{i}", 1) for i in range(120)]
    scheduler = HeartbeatScheduler(FlakyHttp(), "http://gs", 3600, lambda: held, NoopMetricsRecorder())
    assert scheduler._beat_once() is HeartbeatOutcome.FAILED
    assert len(calls) == 3


# 9. repr() never includes the private part.
def test_9_repr_does_not_leak_private_part():
    value = SecretValue(data_version=1, public_part="user", private_part="hunter2")
    assert "hunter2" not in repr(value) and "hunter2" not in str(value)
    assert value.private_part == "hunter2"


# 10. Any hostname supplier failure falls back to UNKNOWN.
def test_10_resolver_falls_back_on_any_supplier_error():
    def broken():
        raise RuntimeError("no hostname")

    assert DefaultWorkloadIdentityResolver(broken).resolve() == "UNKNOWN"


# 11. A server trickling bytes is cut off by the overall deadline (connect + read timeout).
def test_11_trickling_response_hits_the_overall_deadline():
    def trickle(conn):
        read_request(conn)
        conn.sendall(b"HTTP/1.1 200 OK\r\nContent-Length: 1000\r\n\r\n")
        for _ in range(100):
            conn.sendall(b"x")
            time.sleep(0.1)

    raw = RawServer(trickle)
    client = http_client(raw.url, connection_timeout=500, read_timeout=1000)
    start = time.monotonic()
    with pytest.raises(RetryableException):
        client.do_post(f"{raw.url}/v1/x", b"{}", "r")
    assert time.monotonic() - start < 2.5, "each byte arrives within the read timeout, so only the deadline stops it"
    raw.close()


def test_11_read_timeout_applies_when_the_server_goes_silent():
    def silent(conn):
        read_request(conn)
        time.sleep(3)

    raw = RawServer(silent)
    start = time.monotonic()
    with pytest.raises(RetryableException):
        http_client(raw.url, connection_timeout=5000, read_timeout=300).do_post(f"{raw.url}/v1/x", b"{}", "r")
    assert time.monotonic() - start < 1.5
    raw.close()


# 12. close() waits for a running hook (bounded) and the dispatcher threads are daemons.
def test_12_close_waits_for_a_running_hook():
    poller = HookRefreshPoller(None, "http://gs", 3600, UsageLedger(), NoopMetricsRecorder())
    finished = threading.Event()
    started = threading.Event()

    def slow_hook(value):
        started.set()
        time.sleep(0.3)
        finished.set()

    poller.register("p", "s", slow_hook, 0)
    poller._handle_updated_secret("p", "s", SecretValue(2, "v"))
    assert started.wait(2)
    poller.close()
    assert finished.is_set(), "close() returned before the running hook finished"


def test_12_close_gives_up_after_the_timeout_and_warns(caplog):
    poller = HookRefreshPoller(None, "http://gs", 3600, UsageLedger(), NoopMetricsRecorder())
    release = threading.Event()
    started = threading.Event()
    poller.register("p", "s", lambda v: (started.set(), release.wait(5)), 0)
    poller._handle_updated_secret("p", "s", SecretValue(2, "v"))
    assert started.wait(2)
    start = time.monotonic()
    with caplog.at_level(logging.WARNING):
        poller.close(timeout_seconds=0.3)
    assert time.monotonic() - start < 1.0
    assert any("did not stop" in r.getMessage() for r in caplog.records)
    assert all(t.daemon for t in threading.enumerate() if t.name.startswith("grayskull-"))
    release.set()


def test_12_client_close_order_is_heartbeat_poller_then_http(server):
    client = GrayskullClientImpl(BasicAuthHeaderProvider("u", "p"), GrayskullClientConfiguration(host=server.url))
    order: List[str] = []
    for name, target in (("heartbeat", client._heartbeat_scheduler), ("poller", client._refresh_poller),
                         ("http", client._http_client)):
        original = target.close
        target.close = lambda *a, _n=name, _o=original, **k: (order.append(_n), _o(*a, **k))
    client.close()
    assert order == ["heartbeat", "poller", "http"]


# 13. Threads are restarted in a forked child.
@pytest.mark.skipif(not hasattr(os, "fork"), reason="needs os.fork")
def test_13_threads_are_restarted_after_fork(server):
    client = GrayskullClientImpl(BasicAuthHeaderProvider("u", "p"), GrayskullClientConfiguration(host=server.url))
    read_fd, write_fd = os.pipe()
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", DeprecationWarning)  # 3.12+ warns about fork() with threads running
        pid = os.fork()
    if pid == 0:  # child
        try:
            names = sorted({t.name.rsplit("-", 1)[0] if "dispatcher" in t.name else t.name
                            for t in threading.enumerate() if t.name.startswith("grayskull-")})
            os.write(write_fd, json.dumps(names).encode())
        finally:
            os._exit(0)
    os.close(write_fd)
    os.waitpid(pid, 0)
    names = json.loads(os.read(read_fd, 4096).decode())
    os.close(read_fd)
    client.close()
    assert names == ["grayskull-heartbeat", "grayskull-hook-dispatcher", "grayskull-poller"]


@pytest.mark.skipif(not hasattr(os, "fork"), reason="needs os.fork")
def test_13_child_does_not_reuse_the_parents_pooled_connection(server):
    server.set_secret("p", "s", 1, "v")
    client = GrayskullClientImpl(BasicAuthHeaderProvider("u", "p"),
                                 GrayskullClientConfiguration(host=server.url, heartbeat_enabled=False))
    client.get_secret("p:s")
    read_fd, write_fd = os.pipe()
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", DeprecationWarning)
        pid = os.fork()
    if pid == 0:
        try:
            client.get_secret("p:s")
            os.write(write_fd, b"ok")
        finally:
            os._exit(0)
    os.close(write_fd)
    os.waitpid(pid, 0)
    assert os.read(read_fd, 16) == b"ok"
    os.close(read_fd)
    client.get_secret("p:s")
    ports = [r.client_port for r in server.recorded("/data")]
    assert ports[0] == ports[2] != ports[1], "parent kept its connection; child opened its own"
    client.close()


# 14. Connections are pooled, bounded and evicted.
def test_14_keep_alive_connections_are_reused(server):
    server.set_secret("p", "s", 1, "v")
    client = http_client(server.url)
    for _ in range(3):
        client.do_get_with_retry(f"{server.url}/v1/projects/p/secrets/s/data", "r")
    assert len({r.client_port for r in server.recorded("/data")}) == 1


def test_14_close_drops_idle_connections(server):
    server.set_secret("p", "s", 1, "v")
    client = http_client(server.url)
    client.do_get_with_retry(f"{server.url}/v1/projects/p/secrets/s/data", "r")
    client.close()
    assert client._pool._idle == {}


def test_14_idle_connections_expire(server, monkeypatch):
    server.set_secret("p", "s", 1, "v")
    monkeypatch.setattr(http_module, "IDLE_CONNECTION_TTL_SECONDS", 0.05)
    client = http_client(server.url)
    client.do_get_with_retry(f"{server.url}/v1/projects/p/secrets/s/data", "r")
    time.sleep(0.1)
    client.do_get_with_retry(f"{server.url}/v1/projects/p/secrets/s/data", "r")
    assert len({r.client_port for r in server.recorded("/data")}) == 2


def test_14_pool_limits_connections_in_use():
    pool = _ConnectionPool(2)
    assert pool.acquire_slot(time.monotonic() + 1) and pool.acquire_slot(time.monotonic() + 1)
    assert not pool.acquire_slot(time.monotonic() + 0.05)
    pool.release_slot()
    assert pool.acquire_slot(time.monotonic() + 0.05)


def test_14_connection_closed_by_the_server_is_replaced_transparently():
    def one_request_then_close(conn):
        read_request(conn)
        conn.sendall(b"HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n")  # claims keep-alive, then closes

    raw = RawServer(one_request_then_close)
    client = http_client(raw.url)
    client.do_post(f"{raw.url}/v1/x", b"{}", "r1")
    time.sleep(0.05)
    assert client.do_post(f"{raw.url}/v1/x", b"{}", "r2").status_code == 204, "single attempt still succeeds"
    assert raw.connections == 2
    raw.close()


# 15. Retry metrics only for calls with more than one attempt, with the real count.
def test_15_retry_metric_uses_the_real_attempt_count_on_exhaustion(server):
    server.fail_gets_remaining = 99
    metrics = RecordingMetrics()
    with pytest.raises(GrayskullException):
        http_client(server.url, metrics=metrics, max_retries=3).do_get_with_retry(
            f"{server.url}/v1/projects/p/secrets/s/data", "r")
    assert metrics.retries == [(3, False)]


def test_15_no_retry_metric_for_a_single_failed_attempt(server):
    server.fail_gets_remaining = 99
    metrics = RecordingMetrics()
    with pytest.raises(GrayskullException):
        http_client(server.url, metrics=metrics, max_retries=1).do_get_with_retry(
            f"{server.url}/v1/projects/p/secrets/s/data", "r")
    assert metrics.retries == []


# 16. The HTTP layer records no per-attempt raw-URL request metric.
def test_16_http_client_does_not_record_raw_url_requests(server):
    server.set_secret("p", "s", 1, "v")
    server.fail_gets_remaining = 1
    metrics = RecordingMetrics()
    http_client(server.url, metrics=metrics).do_get_with_retry(f"{server.url}/v1/projects/p/secrets/s/data", "r")
    assert metrics.requests == []


def test_16_get_secret_records_exactly_one_request_metric(server):
    server.set_secret("p", "s", 1, "v")
    server.fail_gets_remaining = 1
    metrics = RecordingMetrics()
    client = GrayskullClientImpl(BasicAuthHeaderProvider("u", "p"),
                                 GrayskullClientConfiguration(host=server.url, min_retry_delay=50), metrics)
    client.get_secret("p:s")
    client.close()
    assert metrics.requests == [("getSecret.p:s", 200)]


# 17. One X-Request-Id per poll cycle, shared by every chunk.
def test_17_one_request_id_per_poll_cycle(server):
    client = GrayskullClientImpl(BasicAuthHeaderProvider("u", "p"),
                                 GrayskullClientConfiguration(host=server.url, polling_interval_seconds=600,
                                                              heartbeat_enabled=False))
    for i in range(120):
        client.register_refresh_hook(f"p:s{i}", lambda v: None)
    client._refresh_poller.poll_once()
    client._refresh_poller.poll_once()
    ids = [r.headers["X-Request-Id"] for r in server.recorded("/v1/secrets/batch")]
    client.close()
    assert len(ids) == 6 and len(set(ids[:3])) == 1 and len(set(ids[3:])) == 1 and ids[0] != ids[3]


# 18. Background log lines carry the request id that was sent.
def test_18_poller_log_lines_carry_the_request_id(caplog):
    sent: List[str] = []

    class FailingHttp:
        def do_post_with_retry(self, url, body, request_id):
            sent.append(request_id)
            raise GrayskullException(503, "down")

    poller = HookRefreshPoller(FailingHttp(), "http://gs", 3600, UsageLedger(), NoopMetricsRecorder())
    poller.register("p", "s", lambda v: None, 0)
    with caplog.at_level(logging.WARNING):
        poller.poll_once()
    poller.close()
    record = next(r for r in caplog.records if "Batch refresh failed" in r.getMessage())
    assert f"[RequestId:{sent[0]}]" in record.getMessage()
    assert record.request_id == sent[0]


def test_18_heartbeat_log_lines_carry_the_request_id(caplog):
    sent: List[str] = []

    class FailingHttp:
        def do_post(self, url, body, request_id):
            sent.append(request_id)
            raise RetryableException(503, "down")

    scheduler = HeartbeatScheduler(FailingHttp(), "http://gs", 3600, lambda: [HeldSecret("p", "s", 1)],
                                   NoopMetricsRecorder())
    with caplog.at_level(logging.WARNING):
        scheduler._beat_once()
    assert any(f"[RequestId:{sent[0]}]" in r.getMessage() for r in caplog.records)


# 19. Prometheus metrics with the Go SDK's names and labels.
def test_19_prometheus_recorder_uses_go_names_and_labels():
    from prometheus_client import CollectorRegistry

    registry = CollectorRegistry()
    recorder = PrometheusMetricsRecorder(registry)
    recorder.record_request("getSecret.p:s", 200, 1500)
    recorder.record_retry("http://gs/v1/x", 3, True)
    PrometheusMetricsRecorder(registry)  # a second client shares the collectors instead of failing
    assert registry.get_sample_value("grayskull_http_client_request_duration_seconds_sum",
                                     {"name": "getSecret.p:s", "status_code": "200"}) == 1.5
    assert registry.get_sample_value("grayskull_http_client_retry_attempts_total",
                                     {"url": "http://gs/v1/x", "success": "true"}) == 1


def test_19_metrics_enabled_defaults_to_prometheus(server):
    client = GrayskullClientImpl(BasicAuthHeaderProvider("u", "p"), GrayskullClientConfiguration(host=server.url))
    disabled = GrayskullClientImpl(BasicAuthHeaderProvider("u", "p"),
                                   GrayskullClientConfiguration(host=server.url, metrics_enabled=False))
    assert client._metrics_recorder.get_recorder_name() == "prometheus"
    assert disabled._metrics_recorder.get_recorder_name() == "noop"
    client.close()
    disabled.close()


# 20. Path segments are encoded so the server decodes back the same names.
@pytest.mark.parametrize("name", ["a b", "a+b", "a%b", "a;b", "a:b", "a/b", "a?b", "a#b", "näme-日本"])
def test_20_secret_names_round_trip_through_path_encoding(server, name):
    server.set_secret("proj ect", name, 3, "v")
    client = GrayskullClientImpl(BasicAuthHeaderProvider("u", "p"),
                                 GrayskullClientConfiguration(host=server.url, heartbeat_enabled=False))
    try:
        assert client.get_secret(f"proj ect:{name}").data_version == 3
    finally:
        client.close()


@pytest.mark.parametrize("name,encoded", [
    ("a b", "a%20b"), ("a+b", "a%2Bb"), ("a%b", "a%25b"), ("a;b", "a%3Bb"), ("a:b", "a%3Ab"),
    ("a/b", "a%2Fb"), ("a?b", "a%3Fb"), ("a#b", "a%23b"), ("né", "n%C3%A9"), ("a_b.c-d~e", "a_b.c-d~e"),
])
def test_20_exact_path_sent_for_special_characters(server, name, encoded):
    # Pins the wire form; a real-server check (Spring's StrictHttpFirewall rejects %2F, %25 and ;) belongs in the IT suite.
    client = GrayskullClientImpl(BasicAuthHeaderProvider("u", "p"),
                                 GrayskullClientConfiguration(host=server.url, heartbeat_enabled=False))
    with pytest.raises(GrayskullException):
        client.get_secret(f"p:{name}")  # 404 from the fake; only the request path matters here
    client.close()
    assert server.recorded("/data")[0].path == f"/v1/projects/p/secrets/{encoded}/data"


# 21. Proxy environment variables are ignored.
def test_21_proxy_environment_variables_are_ignored(server, monkeypatch):
    server.set_secret("p", "s", 1, "v")
    for var in ("HTTP_PROXY", "http_proxy", "HTTPS_PROXY", "https_proxy", "ALL_PROXY", "all_proxy"):
        monkeypatch.setenv(var, "http://127.0.0.1:1")
    monkeypatch.delenv("NO_PROXY", raising=False)
    monkeypatch.delenv("no_proxy", raising=False)
    response = http_client(server.url).do_get_with_retry(f"{server.url}/v1/projects/p/secrets/s/data", "r")
    assert response.status_code == 200


# 22. One version: module, package metadata and User-Agent agree.
def test_22_version_is_single_sourced(server):
    assert package_version("grayskull-client") == __version__
    server.set_secret("p", "s", 1, "v")
    client = GrayskullClientImpl(BasicAuthHeaderProvider("u", "p"),
                                 GrayskullClientConfiguration(host=server.url, heartbeat_enabled=False))
    client.get_secret("p:s")
    client.close()
    assert server.recorded("/data")[0].headers["User-Agent"] == f"grayskull-python/{__version__}"


# Edge branches of the items above.
def test_unsupported_url_scheme_is_rejected(server):
    with pytest.raises(GrayskullException) as info:
        http_client(server.url).do_post("ftp://gs/x", b"{}", "r")
    assert info.value.status_code == 0


def test_header_with_a_line_break_is_rejected(server):
    with pytest.raises(GrayskullException) as info:
        http_client(server.url, workload="pod\r\nX-Injected: 1").do_post(f"{server.url}/x", b"{}", "r")
    assert "line break" in str(info.value) and server.requests == []


def test_full_pool_fails_the_attempt_as_retryable(server):
    client = http_client(server.url, max_connections=1, connection_timeout=50, read_timeout=50)
    assert client._pool.acquire_slot(time.monotonic() + 1)
    with pytest.raises(RetryableException, match="free connection"):
        client.do_post(f"{server.url}/x", b"{}", "r")


def test_https_failure_to_connect_is_retryable():
    with pytest.raises(RetryableException) as info:
        http_client("https://127.0.0.1:1", connection_timeout=200).do_post("https://127.0.0.1:1/x", b"{}", "r")
    assert info.value.status_code == 0


def test_metrics_enabled_without_prometheus_falls_back_to_noop(server, monkeypatch):
    import grayskull.client_impl.grayskull_client_impl as impl
    monkeypatch.setattr(impl, "prometheus_available", lambda: False)
    client = GrayskullClientImpl(BasicAuthHeaderProvider("u", "p"), GrayskullClientConfiguration(host=server.url))
    assert client._metrics_recorder.get_recorder_name() == "noop"
    client.close()


def test_after_fork_handler_restarts_threads_and_resets_state(server):
    # The real fork tests run the handler in a child process, where coverage is not collected.
    client = GrayskullClientImpl(BasicAuthHeaderProvider("u", "p"), GrayskullClientConfiguration(host=server.url))
    client.register_refresh_hook("p:s", lambda v: None)
    poller, heartbeat = client._refresh_poller, client._heartbeat_scheduler
    old = [poller._scheduler, heartbeat._scheduler, *poller._dispatcher._workers]
    poller._stopped.set()
    heartbeat._stopped.set()  # stand-ins for threads that did not survive the fork
    poller._stopped, heartbeat._stopped = threading.Event(), threading.Event()
    client._after_fork_in_child()
    new = [poller._scheduler, heartbeat._scheduler, *poller._dispatcher._workers]
    assert all(t.is_alive() for t in new) and not set(new) & set(old)
    client.close()
    assert not any(t.is_alive() for t in new)
    client._after_fork_in_child()  # a closed client stays closed in the child
    assert not poller._scheduler.is_alive()


def test_errors_in_staging_and_hook_dispatch_are_contained(caplog):
    poller = HookRefreshPoller(None, "http://gs", 3600, UsageLedger(), NoopMetricsRecorder())
    poller.register("p", "s", lambda v: None, 0)

    class Response:
        status_code = 200

        def json(self):
            return {"data": {"updatedSecrets": [
                {"projectId": "p", "secretName": "s", "dataVersion": 2, "publicPart": "v"}]}}

    poller._http_client = type("H", (), {"do_post_with_retry": lambda self, *a: Response()})()
    poller._handle_updated_secret = lambda *a: (_ for _ in ()).throw(RuntimeError("stage"))
    with caplog.at_level(logging.ERROR):
        poller.poll_once()
        poller._dispatcher.submit(lambda: 1 / 0)
        time.sleep(0.1)
    messages = [r.getMessage() for r in caplog.records]
    assert any("Could not stage the update" in m for m in messages)
    assert any("Hook dispatch failed" in m for m in messages)
    poller.close()


def test_poll_cycle_error_does_not_stop_polling():
    poller = HookRefreshPoller(None, "http://gs", 0.02, UsageLedger(), NoopMetricsRecorder())
    calls = []
    poller.poll_once = lambda: (calls.append(1), 1 / 0)
    poller.start()
    deadline = time.monotonic() + 3
    while len(calls) < 3 and time.monotonic() < deadline:
        time.sleep(0.01)
    poller.close()
    assert len(calls) >= 3
