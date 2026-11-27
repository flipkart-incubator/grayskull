import pytest

from grayskull import GrayskullException, GrayskullHeaders, NoopMetricsRecorder
from grayskull.client_impl.hooks.default_refresh_handler_ref import DefaultRefreshHandlerRef
from grayskull.client_impl.hooks.secret_state import SecretState
from grayskull.client_impl.models.response.http_response import HttpResponse
from grayskull.client_impl.models.exceptions.retryable_exception import RetryableException
from grayskull.client_impl.utils.retry_util import RetryUtil


def test_grayskull_headers_cannot_be_instantiated():
    with pytest.raises(TypeError):
        GrayskullHeaders()


def test_noop_metrics_recorder_discards_everything():
    recorder = NoopMetricsRecorder()
    recorder.record_request("getSecret", 200, 5)
    recorder.record_retry("http://x", 2, True)
    assert recorder.get_recorder_name() == "noop"


def test_http_response_json_handles_empty_and_non_object_bodies():
    assert HttpResponse(204, b"").json() == {}
    with pytest.raises(GrayskullException, match="Unexpected response body shape"):
        HttpResponse(200, b"[1, 2]").json()
    with pytest.raises(GrayskullException, match="Failed to parse response"):
        HttpResponse(200, b"\xff").json()


def test_refresh_handler_ref_reports_its_secret_and_unregisters_once():
    calls = []
    handle = DefaultRefreshHandlerRef("p:s", lambda: calls.append(1))
    assert handle.get_secret_ref() == "p:s" and handle.is_active()
    handle.unregister()
    handle.unregister()
    assert calls == [1] and not handle.is_active()


def test_removing_a_hook_that_was_never_added_returns_false():
    state = SecretState("p", "s", 0)
    state.add_hook(print)
    assert state.remove_hook(len) is False
    assert state.remove_hook(print) is True


def test_retry_util_raises_the_last_error_after_the_final_attempt():
    attempts = []

    def always_fails():
        attempts.append(1)
        raise RetryableException(503, "unavailable")

    with pytest.raises(RetryableException):
        RetryUtil(max_attempts=0, initial_delay_ms=1).retry(always_fails)
    assert len(attempts) == 1, "at least one attempt is always made"
