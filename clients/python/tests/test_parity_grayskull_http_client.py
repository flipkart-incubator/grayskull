"""Port of Java GrayskullHttpClientTest.

The Java tests use OkHttp's MockWebServer; these use ScriptedServer, which answers each request with
the next queued response and records what arrived on the wire.
"""

import socket

import pytest
from parity_helpers import RecordingMetrics, ScriptedServer, Scripted, StaticAuth

from grayskull import GrayskullClientConfiguration, GrayskullException
from grayskull.client_impl.grayskull_http_client import GrayskullHttpClient
from grayskull.client_impl.models.exceptions.retryable_exception import RetryableException


@pytest.fixture
def web():
    server = ScriptedServer()
    yield server
    server.stop()


def http(web, auth=None, metrics=None, headers=None, **settings) -> GrayskullHttpClient:
    config = GrayskullClientConfiguration(host=web.url, min_retry_delay=50, **settings)
    for name, value in (headers or {}).items():
        config.add_default_header(name, value)
    return GrayskullHttpClient(auth or StaticAuth("Basic dTpw"), config, metrics or RecordingMetrics())


def test_do_get_with_retry_success(web):
    web.enqueue(Scripted(body={"data": "ok"}))
    response = http(web).do_get_with_retry(f"{web.url}/v1/x", "req-1")
    assert response.status_code == 200
    assert response.json() == {"data": "ok"}
    assert len(web.requests()) == 1


def test_do_get_with_retry_includes_auth_header(web):
    http(web, StaticAuth("Basic abc")).do_get_with_retry(f"{web.url}/v1/x", "req-1")
    assert web.requests()[0].header("Authorization") == "Basic abc"


@pytest.mark.parametrize("header", [None, ""])
def test_do_get_with_retry_null_or_empty_auth_header_throws(web, header):
    with pytest.raises(GrayskullException) as info:
        http(web, StaticAuth(header)).do_get_with_retry(f"{web.url}/v1/x", "req-1")
    assert info.value.status_code == 0
    assert web.requests() == []  # rejected before anything is sent


def test_do_get_with_retry_retries_on_retryable_status(web):
    metrics = RecordingMetrics()
    web.enqueue(Scripted(503), Scripted(body={"ok": True}))
    response = http(web, metrics=metrics).do_get_with_retry(f"{web.url}/v1/x", "req-1")
    assert response.json() == {"ok": True}
    assert len(web.requests()) == 2
    assert metrics.retries == [(f"{web.url}/v1/x", 2, True)]


def test_do_get_with_retry_retries_on_network_error(web):
    web.enqueue(Scripted(drop=True), Scripted(body={"ok": True}))
    assert http(web).do_get_with_retry(f"{web.url}/v1/x", "req-1").json() == {"ok": True}
    assert len(web.requests()) == 2


def test_do_get_with_retry_retries_on_429_rate_limiting(web):
    web.enqueue(Scripted(429), Scripted(429), Scripted(body={"ok": True}))
    assert http(web).do_get_with_retry(f"{web.url}/v1/x", "req-1").status_code == 200
    assert len(web.requests()) == 3


def test_do_get_with_retry_exhausts_retries_throws_grayskull_exception(web):
    metrics = RecordingMetrics()
    web.route = lambda seen: Scripted(503, {"message": "down"})
    with pytest.raises(GrayskullException) as info:
        http(web, metrics=metrics, max_retries=3).do_get_with_retry(f"{web.url}/v1/x", "req-1")
    assert info.value.status_code == 503
    assert isinstance(info.value.__cause__, RetryableException)
    assert len(web.requests()) == 3
    assert metrics.retries == [(f"{web.url}/v1/x", 3, False)]


@pytest.mark.parametrize("status", [400, 401, 403, 404])
def test_do_get_with_retry_non_retryable_status_is_not_retried(web, status):
    web.enqueue(Scripted(status, {"message": "nope"}))
    with pytest.raises(GrayskullException) as info:
        http(web).do_get_with_retry(f"{web.url}/v1/x", "req-1")
    assert info.value.status_code == status
    assert len(web.requests()) == 1


def test_do_get_with_retry_emits_default_headers(web):
    http(web, headers={"Grayskull-Workload": "pod-1"}).do_get_with_retry(f"{web.url}/v1/x", "req-1")
    assert web.requests()[0].header("Grayskull-Workload") == "pod-1"


def test_do_get_with_retry_emits_multiple_default_headers(web):
    http(web, headers={"X-A": "1", "X-B": "2"}).do_get_with_retry(f"{web.url}/v1/x", "req-1")
    seen = web.requests()[0]
    assert (seen.header("X-A"), seen.header("X-B")) == ("1", "2")


def test_do_get_with_retry_no_default_headers_emits_only_sdk_headers(web):
    http(web).do_get_with_retry(f"{web.url}/v1/x", "req-1")
    names = {name.lower() for name, _ in web.requests()[0].headers}
    assert {"authorization", "x-request-id"} <= names
    assert "grayskull-workload" not in names


def test_do_get_with_retry_default_headers_emitted_on_every_retry(web):
    web.enqueue(Scripted(503), Scripted(503), Scripted(body={}))
    http(web, headers={"X-A": "1"}).do_get_with_retry(f"{web.url}/v1/x", "req-1")
    assert [s.header("X-A") for s in web.requests()] == ["1", "1", "1"]


def test_do_post_with_retry_success(web):
    web.enqueue(Scripted(body={"created": True}))
    response = http(web).do_post_with_retry(f"{web.url}/v1/x", b'{"a":1}', "req-1")
    seen = web.requests()[0]
    assert response.json() == {"created": True}
    assert (seen.method, seen.body) == ("POST", b'{"a":1}')
    assert seen.header("Content-Type") == "application/json; charset=utf-8"


def test_do_post_with_retry_empty_body_sends_empty_payload(web):
    http(web).do_post_with_retry(f"{web.url}/v1/x", b"", "req-1")
    seen = web.requests()[0]
    assert (seen.method, seen.body, seen.header("Content-Length")) == ("POST", b"", "0")


def test_do_post_with_retry_retries_on_503_then_succeeds(web):
    web.enqueue(Scripted(503), Scripted(body={"ok": True}))
    assert http(web).do_post_with_retry(f"{web.url}/v1/x", b"{}", "req-1").json() == {"ok": True}
    assert [s.body for s in web.requests()] == [b"{}", b"{}"]


def test_do_post_with_retry_exhausts_retries_throws_grayskull_exception(web):
    web.route = lambda seen: Scripted(500)
    with pytest.raises(GrayskullException) as info:
        http(web, max_retries=2).do_post_with_retry(f"{web.url}/v1/x", b"{}", "req-1")
    assert info.value.status_code == 500
    assert len(web.requests()) == 2


def test_do_post_is_a_single_attempt(web):
    web.enqueue(Scripted(503))
    with pytest.raises(RetryableException):
        http(web).do_post(f"{web.url}/v1/x", b"{}", "req-1")
    assert len(web.requests()) == 1


def test_sdk_authorization_wins_over_user_default(web):
    http(web, StaticAuth("Basic sdk"), headers={"Authorization": "Basic user"}).do_get_with_retry(
        f"{web.url}/v1/x", "req-1")
    assert web.requests()[0].all("Authorization") == ["Basic sdk"]


def test_sdk_request_id_wins_over_user_default(web):
    http(web, headers={"X-Request-Id": "user-id"}).do_get_with_retry(f"{web.url}/v1/x", "sdk-id")
    assert web.requests()[0].all("X-Request-Id") == ["sdk-id"]


def test_custom_header_single_value_on_wire(web):
    http(web, headers={"X-Custom": "one"}).do_get_with_retry(f"{web.url}/v1/x", "req-1")
    assert web.requests()[0].all("X-Custom") == ["one"]


def test_unexpected_error_from_request_path_is_wrapped(web):
    with pytest.raises(GrayskullException) as info:
        http(web, StaticAuth(error=RuntimeError("token service down"))).do_get_with_retry(f"{web.url}/v1/x", "r")
    assert "token service down" in str(info.value)
    assert isinstance(info.value.__cause__, RuntimeError)
    assert web.requests() == []


def test_read_timeout_eventually_grayskull_exception_with_timeout_cause(web):
    web.route = lambda seen: Scripted(body={}, delay=0.5)
    with pytest.raises(GrayskullException) as info:
        http(web, read_timeout=100, max_retries=2).do_get_with_retry(f"{web.url}/v1/x", "req-1")
    retryable = info.value.__cause__
    assert isinstance(retryable, RetryableException)
    assert isinstance(retryable.__cause__, (socket.timeout, TimeoutError))
    assert len(web.requests()) == 2


def test_http_600_is_not_retryable_single_attempt(web):
    web.enqueue(Scripted(600, {"message": "odd"}))
    with pytest.raises(GrayskullException) as info:
        http(web).do_get_with_retry(f"{web.url}/v1/x", "req-1")
    assert info.value.status_code == 600
    assert len(web.requests()) == 1


def test_empty_request_id_skips_header(web):
    http(web, headers={"X-Request-Id": "user-id"}).do_get_with_retry(f"{web.url}/v1/x", "")
    assert web.requests()[0].all("X-Request-Id") == []


@pytest.mark.parametrize("method", ["get", "post"])
def test_non_empty_response_body_is_returned(web, method):
    web.enqueue(Scripted(body=b'{"k":"v"}'))
    client = http(web)
    url = f"{web.url}/v1/x"
    response = client.do_get_with_retry(url, "r") if method == "get" else client.do_post_with_retry(url, b"{}", "r")
    assert response.body == b'{"k":"v"}'


@pytest.mark.parametrize("method", ["get", "post"])
def test_empty_response_body_is_zero_length(web, method):
    web.enqueue(Scripted(204))
    client = http(web)
    url = f"{web.url}/v1/x"
    response = client.do_get_with_retry(url, "r") if method == "get" else client.do_post_with_retry(url, b"{}", "r")
    assert (response.status_code, response.body, response.json()) == (204, b"", {})
