import pytest

from grayskull import BasicAuthHeaderProvider, GrayskullClientConfiguration, GrayskullException
from grayskull.client_impl.auth.grayskull_auth_header_provider import GrayskullAuthHeaderProvider
from grayskull.client_impl.grayskull_http_client import GrayskullHttpClient
from grayskull.client_impl.metrics.metrics_recorder import MetricsRecorder, NoopMetricsRecorder
from grayskull.client_impl.models.exceptions.retryable_exception import RetryableException


class RecordingMetricsRecorder(MetricsRecorder):
    """Keeps every observation for assertions."""

    def __init__(self) -> None:
        self.requests = []
        self.retries = []

    def record_request(self, name: str, status_code: int, duration_ms: int) -> None:
        self.requests.append((name, status_code))

    def record_retry(self, url: str, attempt_number: int, success: bool) -> None:
        self.retries.append((attempt_number, success))

    def get_recorder_name(self) -> str:
        return "recording"


def new_http_client(url: str, max_retries: int = 3, auth=None, metrics=None) -> GrayskullHttpClient:
    config = GrayskullClientConfiguration(host=url, max_retries=max_retries, min_retry_delay=50)
    config.add_default_header("Grayskull-Workload", "pod-1")
    config.add_default_header("User-Agent", "grayskull-python/test")
    return GrayskullHttpClient(auth or BasicAuthHeaderProvider("user", "pass"), config, metrics or NoopMetricsRecorder())


def test_requests_carry_auth_workload_user_agent_and_request_id(server):
    server.set_secret("p", "s", 1, "v")
    new_http_client(server.url).do_get_with_retry(f"{server.url}/v1/projects/p/secrets/s/data", "req-123")
    sent = server.recorded("/data")[0].headers
    assert sent["Authorization"].startswith("Basic ")
    assert sent["Grayskull-Workload"] == "pod-1"
    assert sent["User-Agent"] == "grayskull-python/test"
    assert sent["X-Request-Id"] == "req-123"


def test_transient_failures_are_retried_until_success(server):
    server.set_secret("p", "s", 2, "v")
    server.fail_gets_remaining = 2
    metrics = RecordingMetricsRecorder()
    response = new_http_client(server.url, metrics=metrics).do_get_with_retry(
        f"{server.url}/v1/projects/p/secrets/s/data", "r")
    assert response.json()["data"]["dataVersion"] == 2
    assert len(server.recorded("/data")) == 3
    assert metrics.retries == [(3, True)]


def test_exhausted_retries_raise_grayskull_exception(server):
    server.set_secret("p", "s", 2, "v")
    server.fail_gets_remaining = 99
    with pytest.raises(GrayskullException) as info:
        new_http_client(server.url, max_retries=2).do_get_with_retry(f"{server.url}/v1/projects/p/secrets/s/data", "r")
    assert info.value.status_code == 503
    assert len(server.recorded("/data")) == 2


def test_client_errors_are_not_retried(server):
    with pytest.raises(GrayskullException) as info:
        new_http_client(server.url).do_get_with_retry(f"{server.url}/v1/projects/p/secrets/missing/data", "r")
    assert info.value.status_code == 404
    assert len(server.recorded("/data")) == 1


def test_do_post_is_a_single_attempt_even_when_retryable(server):
    server.heartbeat_status = 503
    with pytest.raises(RetryableException) as info:
        new_http_client(server.url).do_post(f"{server.url}/v1/client/heartbeat", b"{}", "r")
    assert info.value.status_code == 503
    assert len(server.recorded("/v1/client/heartbeat")) == 1


class FailingAuthHeaderProvider(GrayskullAuthHeaderProvider):
    def get_auth_header(self) -> str:
        raise OSError("token service unavailable")


def test_auth_provider_failure_surfaces_as_grayskull_exception(server):
    with pytest.raises(GrayskullException) as info:
        new_http_client(server.url, auth=FailingAuthHeaderProvider()).do_post(
            f"{server.url}/v1/client/heartbeat", b"{}", "r")
    assert "Failed to obtain auth header" in str(info.value)


def test_connection_failure_is_retryable_with_status_zero(server):
    url = server.url
    client = new_http_client(url)
    closed_port_url = "http://127.0.0.1:1/v1/client/heartbeat"
    with pytest.raises(RetryableException) as info:
        client.do_post(closed_port_url, b"{}", "r")
    assert info.value.status_code == 0


class RejectingAuthHeaderProvider(GrayskullAuthHeaderProvider):
    def get_auth_header(self) -> str:
        raise GrayskullException(401, "token rejected")


def test_grayskull_exception_from_the_auth_provider_is_raised_unchanged(server):
    with pytest.raises(GrayskullException) as info:
        new_http_client(server.url, auth=RejectingAuthHeaderProvider()).do_post(f"{server.url}/x", b"{}", "r")
    assert info.value.status_code == 401 and str(info.value) == "token rejected"
