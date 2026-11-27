"""Port of Java RetryUtilTest.

Java wraps an exhausted retry in GrayskullException("Failed after N retry attempts"); the Python
RetryUtil re-raises the last RetryableException with .attempts set. Those tests assert the Python shape.
Thread interruption has no Python equivalent in this client, so that Java case is not ported.
"""

import pytest

from grayskull.client_impl.models.exceptions.grayskull_exception import GrayskullException
from grayskull.client_impl.models.exceptions.retryable_exception import RetryableException
from grayskull.client_impl.utils import retry_util
from grayskull.client_impl.utils.retry_util import RetryUtil


@pytest.fixture
def sleeps(monkeypatch):
    """Record requested sleeps instead of waiting. Jitter is pinned to the lower bound."""
    recorded = []
    monkeypatch.setattr(retry_util.time, "sleep", lambda seconds: recorded.append(seconds))
    monkeypatch.setattr(retry_util.random, "uniform", lambda low, high: 0.0)
    return recorded


def test_success_on_first_attempt_runs_once(sleeps):
    calls = []

    def task():
        calls.append(1)
        return "success"

    assert RetryUtil(3, 100).retry(task) == ("success", 1)
    assert len(calls) == 1 and sleeps == []


def test_success_on_second_attempt(sleeps):
    calls = []

    def task():
        calls.append(1)
        if len(calls) == 1:
            raise RetryableException(503, "Transient error")
        return "success"

    value, attempts = RetryUtil(3, 100).retry(task)
    assert (value, attempts) == ("success", 2)
    assert len(calls) == 2


def test_exhausting_all_attempts_reraises_the_last_retryable_error(sleeps):
    calls = []

    def task():
        calls.append(1)
        raise RetryableException(503, "Always fails")

    with pytest.raises(RetryableException) as info:
        RetryUtil(3, 100).retry(task)
    assert len(calls) == 3
    assert info.value.attempts == 3


def test_non_retryable_error_is_not_retried(sleeps):
    calls = []

    def task():
        calls.append(1)
        raise GrayskullException(404, "Not found")

    with pytest.raises(GrayskullException) as info:
        RetryUtil(3, 100).retry(task)
    assert len(calls) == 1
    assert info.value.status_code == 404
    assert str(info.value) == "Not found" or "Not found" in info.value.args[0]


def test_backoff_doubles_after_each_failure(sleeps):
    def task():
        raise RetryableException(503, "retry")

    with pytest.raises(RetryableException):
        RetryUtil(4, 100).retry(task)
    assert sleeps == pytest.approx([0.1, 0.2, 0.4])


def test_backoff_is_capped_at_one_minute(sleeps):
    def task():
        raise RetryableException(503, "Always fails")

    with pytest.raises(RetryableException):
        RetryUtil(3, 35_000).retry(task)
    assert sleeps == pytest.approx([35.0, 60.0])


def test_jitter_adds_up_to_a_quarter_of_the_delay(monkeypatch):
    recorded = []
    monkeypatch.setattr(retry_util.time, "sleep", lambda seconds: recorded.append(seconds))
    monkeypatch.setattr(retry_util.random, "uniform", lambda low, high: high)

    def task():
        raise RetryableException(503, "retry")

    with pytest.raises(RetryableException):
        RetryUtil(2, 100).retry(task)
    assert recorded == pytest.approx([0.1 * 1.25])


def test_zero_or_negative_max_attempts_still_runs_once(sleeps):
    calls = []

    def task():
        calls.append(1)
        raise RetryableException(503, "fails")

    with pytest.raises(RetryableException):
        RetryUtil(0, 100).retry(task)
    assert len(calls) == 1
