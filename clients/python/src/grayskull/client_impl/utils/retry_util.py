"""Retries a call on RetryableException with exponential backoff and jitter."""

import random
import time
from typing import Callable, Tuple, TypeVar

from ..models.exceptions.retryable_exception import RetryableException

T = TypeVar("T")
_MAX_BACKOFF_SECONDS = 60.0
_BACKOFF_JITTER = 0.25


class RetryUtil:
    """Runs a call up to max_attempts times, doubling the delay after each transient failure."""

    def __init__(self, max_attempts: int, initial_delay_ms: int) -> None:
        self._max_attempts = max(1, max_attempts)
        self._initial_delay_seconds = initial_delay_ms / 1000.0

    def retry(self, task: Callable[[], T]) -> Tuple[T, int]:
        """Return the task result and the number of attempts.

        When every attempt fails, re-raises the last RetryableException with its attempts attribute set.
        """
        attempt = 1
        while True:
            try:
                return task(), attempt
            except RetryableException as exc:
                if attempt >= self._max_attempts:
                    exc.attempts = attempt
                    raise
                delay = min(self._initial_delay_seconds * (2 ** (attempt - 1)), _MAX_BACKOFF_SECONDS)
                time.sleep(delay * (1 + random.uniform(0, _BACKOFF_JITTER)))
                attempt += 1
