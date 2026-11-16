from __future__ import annotations

import base64

import httpx
import pytest

from conftest import RecordingMetrics, json_response
from grayskull import (
    GrayskullClientConfig,
    GrayskullError,
    InvalidArgumentError,
    SecretRef,
    SecretValue,
    request_scope,
)
from grayskull.client import USER_AGENT

SECRET = {"dataVersion": 3, "publicPart": "user", "privatePart": "s3cr3t"}


def test_get_secret_returns_value_and_sends_sdk_headers(server, make_client):
    server.on("GET", "/v1/projects/proj/secrets/db/data", lambda r: json_response(200, SECRET))
    client = make_client(default_headers={"X-Team": "payments", "Authorization": "spoofed"})

    assert client.get_secret("proj:db") == SecretValue(3, "user", "s3cr3t")

    request = server.requests[0]
    assert request.headers["Authorization"] == "Basic " + base64.b64encode(b"user:pass").decode()
    assert request.headers["Grayskull-Workload"] == "test-workload"
    assert request.headers["User-Agent"] == USER_AGENT
    assert request.headers["X-Team"] == "payments"
    assert request.headers["X-Request-Id"]


def test_caller_request_id_is_propagated(server, make_client):
    server.on("GET", "/v1/", lambda r: json_response(200, SECRET))
    with request_scope("trace-123"):
        make_client().get_secret("proj:db")
    assert server.requests[0].headers["X-Request-Id"] == "trace-123"


def test_secret_name_with_colon_and_reserved_chars_is_escaped(server, make_client):
    server.on("GET", "/v1/", lambda r: json_response(200, SECRET))
    make_client().get_secret("my proj:db:primary/v2")
    assert server.requests[0].url.raw_path == b"/v1/projects/my%20proj/secrets/db%3Aprimary%2Fv2/data"


@pytest.mark.parametrize("ref", ["", "nocolon", ":name", "proj:"])
def test_invalid_refs_are_rejected_without_io(server, make_client, ref):
    metrics = RecordingMetrics()
    with pytest.raises(InvalidArgumentError):
        make_client(metrics).get_secret(ref)
    assert server.requests == []
    assert metrics.requests == [("get_secret", 400)]


def test_transient_errors_are_retried_until_success(server, make_client):
    responses = iter([httpx.Response(503), httpx.Response(429), json_response(200, SECRET)])
    server.on("GET", "/v1/", lambda r: next(responses))
    metrics = RecordingMetrics()

    assert make_client(metrics).get_secret("proj:db").data_version == 3
    assert len(server.requests) == 3
    assert metrics.retries == [("/v1/projects/{projectId}/secrets/{secretName}/data", 3, True)]
    assert ("get_secret", 200) in metrics.requests


def test_retries_exhausted_keep_last_status(server, make_client):
    server.on("GET", "/v1/", lambda r: httpx.Response(502, text="bad gateway"))
    with pytest.raises(GrayskullError) as info:
        make_client(max_retries=2).get_secret("proj:db")
    assert info.value.status_code == 502
    assert len(server.requests) == 2


def test_network_errors_are_retried(server, make_client):
    calls = {"n": 0}

    def flaky(request):
        calls["n"] += 1
        if calls["n"] == 1:
            raise httpx.ConnectError("boom", request=request)
        return json_response(200, SECRET)

    server.on("GET", "/v1/", flaky)
    assert make_client().get_secret("proj:db").data_version == 3
    assert calls["n"] == 2


@pytest.mark.parametrize("status", [400, 401, 403, 404])
def test_client_errors_are_not_retried(server, make_client, status):
    server.on("GET", "/v1/", lambda r: httpx.Response(status, text="nope"))
    with pytest.raises(GrayskullError) as info:
        make_client().get_secret("proj:db")
    assert info.value.status_code == status
    assert len(server.requests) == 1


def test_missing_data_is_an_error(server, make_client):
    server.on("GET", "/v1/", lambda r: json_response(200, None))
    with pytest.raises(GrayskullError) as info:
        make_client().get_secret("proj:db")
    assert info.value.status_code == 500


def test_auth_provider_failure_surfaces(server):
    class Broken:
        def get_auth_header(self) -> str:
            raise RuntimeError("token service down")

    from grayskull import GrayskullClientImpl

    client = GrayskullClientImpl(
        Broken(),
        GrayskullClientConfig(host="https://g.test", heartbeat_enabled=False, metrics_enabled=False),
        http_transport=httpx.MockTransport(server),
    )
    with pytest.raises(GrayskullError, match="auth header"):
        client.get_secret("p:s")
    assert server.requests == []
    client.close()


def test_close_is_idempotent_and_blocks_new_work(make_client):
    client = make_client()
    client.close()
    client.close()
    with pytest.raises(GrayskullError):
        client.get_secret("p:s")
    with pytest.raises(GrayskullError):
        client.register_refresh_hook("p:s", lambda v: None)


def test_context_manager_closes(make_client):
    with make_client() as client:
        pass
    with pytest.raises(GrayskullError):
        client.get_secret("p:s")


def test_secret_value_repr_masks_private_part():
    value = SecretValue(1, "user", "hunter2")
    assert "hunter2" not in repr(value) and "hunter2" not in str(value)


def test_secret_ref_parse_splits_on_first_colon():
    assert SecretRef.parse("a:b:c") == SecretRef("a", "b:c")
    assert str(SecretRef("a", "b:c")) == "a:b:c"


@pytest.mark.parametrize(
    "overrides",
    [
        {"host": ""},
        {"host": "grayskull.example.com"},
        {"host": "ftp://x"},
        {"max_connections": 0},
        {"max_retries": -1},
        {"max_retries": 0},
        {"max_retries": 11},
        {"read_timeout": -1},
        {"polling_interval": 0},
        {"connect_timeout": float("nan")},
    ],
)
def test_config_validation(overrides):
    settings = {"host": "https://g.test", **overrides}
    with pytest.raises(InvalidArgumentError):
        GrayskullClientConfig(**settings)


def test_config_normalises_and_freezes():
    config = GrayskullClientConfig(host="  https://g.test/ ", default_headers={"A": "1", "": "x", "B": ""})
    assert config.host == "https://g.test"
    assert dict(config.default_headers) == {"A": "1"}
    with pytest.raises(TypeError):
        config.default_headers["C"] = "3"  # type: ignore[index]
    assert config.replace(read_timeout=5).read_timeout == 5
