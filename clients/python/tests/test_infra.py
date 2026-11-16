from __future__ import annotations

import logging
import os
import sys

import httpx
import pytest

from conftest import json_response
from grayskull import (
    BasicAuthHeaderProvider,
    GrayskullClientConfig,
    GrayskullClientImpl,
    GrayskullError,
    InvalidArgumentError,
    PrometheusMetricsRecorder,
    RequestIdLogFilter,
    request_scope,
    secret_scope,
)
from grayskull.transport import HttpTransport, backoff_delay, is_retryable_status


def test_backoff_is_exponential_capped_with_bounded_jitter():
    assert backoff_delay(1, 0.1, 60, rng=lambda: 0.0) == pytest.approx(0.1)
    assert backoff_delay(3, 0.1, 60, rng=lambda: 0.0) == pytest.approx(0.4)
    assert backoff_delay(3, 0.1, 60, rng=lambda: 1.0) == pytest.approx(0.5)
    assert backoff_delay(20, 0.1, 60, rng=lambda: 0.0) == pytest.approx(60)


@pytest.mark.parametrize("status, expected", [(429, True), (500, True), (599, True), (408, False), (404, False)])
def test_retryable_statuses(status, expected):
    assert is_retryable_status(status) is expected


class _Settings:
    host = "https://g.test"
    connect_timeout = read_timeout = keepalive_expiry = 1.0
    max_connections = max_keepalive_connections = 2
    max_retries = 3
    min_retry_delay = 0.0
    max_retry_delay = 0.0


def _transport(handler):
    return HttpTransport(_Settings(), lambda: "Basic x", transport=httpx.MockTransport(handler))


@pytest.mark.parametrize(
    "failure",
    [lambda r: httpx.Response(500), lambda r: httpx.Response(504), lambda r: (_ for _ in ()).throw(httpx.ReadTimeout("t"))],
)
def test_non_idempotent_writes_are_not_retried_when_maybe_applied(failure):
    calls = []

    def handler(request):
        calls.append(request)
        return failure(request)

    with pytest.raises(GrayskullError):
        _transport(handler).request("POST", "/v1/x", route="/v1/x", json_body={}, idempotent=False)
    assert len(calls) == 1


def test_non_idempotent_writes_retry_when_provably_not_applied():
    outcomes = iter([httpx.ConnectError("refused"), httpx.Response(503), httpx.Response(429), httpx.Response(200)])
    calls = []

    def handler(request):
        calls.append(request)
        item = next(outcomes)
        if isinstance(item, Exception):
            raise item
        return item

    settings = _Settings()
    settings.max_retries = 4
    transport = HttpTransport(settings, lambda: "Basic x", transport=httpx.MockTransport(handler))
    assert transport.request("POST", "/v1/x", route="/v1/x", json_body={}, idempotent=False).status_code == 200
    assert len(calls) == 4


def test_retry_after_is_honoured(server, make_client, monkeypatch):
    sleeps = []
    monkeypatch.setattr("grayskull.transport.time.sleep", sleeps.append)
    responses = iter([httpx.Response(429, headers={"Retry-After": "2"}), json_response(200, {"dataVersion": 1})])
    server.on("GET", "/v1/", lambda r: next(responses))
    make_client().get_secret("p:s")
    assert sleeps == [2.0]


def test_basic_auth_rejects_blank_credentials():
    with pytest.raises(InvalidArgumentError):
        BasicAuthHeaderProvider("", "x")
    assert "x" not in repr(BasicAuthHeaderProvider("u", "x"))


def test_two_clients_share_prometheus_collectors():
    prometheus_client = pytest.importorskip("prometheus_client")
    registry = prometheus_client.CollectorRegistry()
    a = PrometheusMetricsRecorder(registry)
    b = PrometheusMetricsRecorder(registry)
    a.record_request("get_secret", 200, 0.01)
    b.record_retry("/v1/x", 2, True)
    assert registry.get_sample_value(
        "grayskull_http_client_request_duration_seconds_count", {"name": "get_secret", "status_code": "200"}
    ) == 1
    assert registry.get_sample_value("grayskull_http_client_retry_attempts_total", {"url": "/v1/x", "success": "true"}) == 1


def test_metrics_enabled_on_default_registry_twice():
    pytest.importorskip("prometheus_client")
    config = GrayskullClientConfig(host="https://g.test", heartbeat_enabled=False)
    for _ in range(2):
        GrayskullClientImpl(BasicAuthHeaderProvider("u", "p"), config).close()


def test_request_id_log_filter():
    record = logging.LogRecord("x", logging.INFO, __file__, 1, "msg", None, None)
    RequestIdLogFilter().filter(record)
    assert record.grayskullRequestId == "-"
    assert (record.projectId, record.secretName) == ("-", "-")
    with request_scope("abc"), secret_scope("proj", "db"):
        RequestIdLogFilter().filter(record)
    assert record.grayskullRequestId == "abc"
    assert (record.projectId, record.secretName) == ("proj", "db")


def test_get_secret_logs_carry_secret_context(server, make_client, caplog):
    server.on("GET", "/v1/", lambda r: json_response(200, {"dataVersion": 1}))
    caplog.handler.addFilter(RequestIdLogFilter())
    with caplog.at_level(logging.DEBUG, logger="grayskull"):
        make_client().get_secret("proj:db")
    record = next(r for r in caplog.records if r.name == "grayskull.client")
    assert (record.projectId, record.secretName) == ("proj", "db")
    assert record.grayskullRequestId != "-"


@pytest.mark.skipif(not hasattr(os, "fork") or sys.platform == "win32", reason="POSIX fork only")
@pytest.mark.filterwarnings("ignore:This process .* is multi-threaded:DeprecationWarning")
def test_client_reinitialises_after_fork(server, make_client):
    server.on("POST", "/v1/secrets/batch", lambda r: json_response(200, {"updatedSecrets": []}))
    client = make_client(polling_interval=30)
    client.register_refresh_hook("p:s", lambda v: None)
    parent_poller, parent_transport = client._poller, client._transport

    read_fd, write_fd = os.pipe()
    pid = os.fork()
    if pid == 0:  # child: report and exit without running pytest teardown
        ok = (
            client._poller is not parent_poller
            and client._poller._thread.is_alive()
            and client._transport is not parent_transport
        )
        os.write(write_fd, b"1" if ok else b"0")
        os._exit(0)
    os.close(write_fd)
    result = os.read(read_fd, 1)
    os.waitpid(pid, 0)
    assert result == b"1"
    assert client._poller is parent_poller
