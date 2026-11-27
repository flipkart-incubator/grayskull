"""Ports of the small Java test classes: GrayskullHeadersTest, DefaultRefreshHandlerRefTest and the
recorder tests (MicrometerMetricsRecorderTest / JmxMetricsRecorderTest map to PrometheusMetricsRecorder).

Not ported, because the Python SDK has no such class:
- URLNormalizerTest: Java normalises URLs into metric names; Python names metrics directly
  (getSecret.<ref>, batchGetSecrets, heartbeat, hook.execute.<ref>).
- MetricsPublisherTest: Java has a global enable/disable singleton; Python takes a MetricsRecorder
  per client and metrics_enabled in the configuration.
"""

import threading

import pytest

from grayskull import GrayskullHeaders
from grayskull.client_impl.hooks.default_refresh_handler_ref import DefaultRefreshHandlerRef

prometheus_client = pytest.importorskip("prometheus_client")
from grayskull.client_impl.metrics.prometheus_metrics_recorder import PrometheusMetricsRecorder  # noqa: E402


# ---- GrayskullHeadersTest ----

def test_headers_constants_holder_cannot_be_instantiated():
    with pytest.raises(TypeError):
        GrayskullHeaders()
    assert (GrayskullHeaders.WORKLOAD, GrayskullHeaders.USER_AGENT) == ("Grayskull-Workload", "User-Agent")


# ---- DefaultRefreshHandlerRefTest ----

def test_new_handle_is_active_and_exposes_secret_ref():
    handle = DefaultRefreshHandlerRef("p:s", lambda: None)
    assert handle.is_active()
    assert handle.get_secret_ref() == "p:s"


def test_unregister_is_idempotent_callback_invoked_exactly_once():
    calls = []
    handle = DefaultRefreshHandlerRef("p:s", lambda: calls.append(1))
    threads = [threading.Thread(target=handle.unregister) for _ in range(8)]
    for t in threads:
        t.start()
    for t in threads:
        t.join(5)
    handle.unregister()
    assert calls == [1]
    assert not handle.is_active()


# ---- Micrometer/JMX recorder tests -> PrometheusMetricsRecorder ----

@pytest.fixture
def registry():
    return prometheus_client.CollectorRegistry()


def count(registry, name, labels):
    return registry.get_sample_value(name, labels) or 0.0


def test_recorder_name(registry):
    assert PrometheusMetricsRecorder(registry).get_recorder_name() == "prometheus"


def test_record_request(registry):
    PrometheusMetricsRecorder(registry).record_request("getSecret.p:s", 200, 150)
    labels = {"name": "getSecret.p:s", "status_code": "200"}
    assert count(registry, "grayskull_http_client_request_duration_seconds_count", labels) == 1
    assert count(registry, "grayskull_http_client_request_duration_seconds_sum", labels) == pytest.approx(0.15)


def test_record_multiple_requests(registry):
    recorder = PrometheusMetricsRecorder(registry)
    for _ in range(3):
        recorder.record_request("batchGetSecrets", 200, 10)
    labels = {"name": "batchGetSecrets", "status_code": "200"}
    assert count(registry, "grayskull_http_client_request_duration_seconds_count", labels) == 3


def test_record_request_with_different_status_codes(registry):
    recorder = PrometheusMetricsRecorder(registry)
    for status in (200, 404, 500):
        recorder.record_request("getSecret.p:s", status, 5)
    for status in ("200", "404", "500"):
        labels = {"name": "getSecret.p:s", "status_code": status}
        assert count(registry, "grayskull_http_client_request_duration_seconds_count", labels) == 1


def test_record_retry(registry):
    PrometheusMetricsRecorder(registry).record_retry("http://g/v1/x", 2, True)
    assert count(registry, "grayskull_http_client_retry_attempts_total", {"url": "http://g/v1/x", "success": "true"}) == 1


def test_record_multiple_retries(registry):
    recorder = PrometheusMetricsRecorder(registry)
    for _ in range(4):
        recorder.record_retry("http://g/v1/x", 3, False)
    assert count(registry, "grayskull_http_client_retry_attempts_total", {"url": "http://g/v1/x", "success": "false"}) == 4


def test_record_retry_with_success_and_failure(registry):
    recorder = PrometheusMetricsRecorder(registry)
    recorder.record_retry("u", 2, True)
    recorder.record_retry("u", 3, False)
    assert count(registry, "grayskull_http_client_retry_attempts_total", {"url": "u", "success": "true"}) == 1
    assert count(registry, "grayskull_http_client_retry_attempts_total", {"url": "u", "success": "false"}) == 1


def test_collectors_are_created_once_per_registry(registry):
    first = PrometheusMetricsRecorder(registry)
    second = PrometheusMetricsRecorder(registry)  # must not fail with a duplicate registration
    first.record_request("n", 200, 1)
    second.record_request("n", 200, 1)
    labels = {"name": "n", "status_code": "200"}
    assert count(registry, "grayskull_http_client_request_duration_seconds_count", labels) == 2
