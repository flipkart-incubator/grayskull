"""Records request and retry metrics."""

from abc import ABC, abstractmethod


class MetricsRecorder(ABC):
    """Receives one observation per request and per retried call."""

    @abstractmethod
    def record_request(self, name: str, status_code: int, duration_ms: int) -> None:
        """Record one request outcome."""

    @abstractmethod
    def record_retry(self, url: str, attempt_number: int, success: bool) -> None:
        """Record a call that needed more than one attempt."""

    @abstractmethod
    def get_recorder_name(self) -> str:
        """Return a short name for this recorder."""


class NoopMetricsRecorder(MetricsRecorder):
    """Discards every metric; used when metrics are disabled or no recorder is given."""

    def record_request(self, name: str, status_code: int, duration_ms: int) -> None:
        """Discard the observation."""

    def record_retry(self, url: str, attempt_number: int, success: bool) -> None:
        """Discard the observation."""

    def get_recorder_name(self) -> str:
        """Return 'noop'."""
        return "noop"
