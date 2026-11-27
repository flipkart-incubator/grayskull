"""Prometheus metrics with the same names and labels as the Go SDK.

- grayskull_http_client_request_duration_seconds (histogram; labels: name, status_code)
- grayskull_http_client_retry_attempts_total (counter; labels: url, success)

Needs the optional prometheus_client package: pip install 'grayskull-client[prometheus]'.
"""

import threading
import weakref
from typing import Any, Optional, Tuple

from .metrics_recorder import MetricsRecorder

_NAMESPACE = "grayskull"
_SUBSYSTEM = "http_client"
_lock = threading.Lock()
# Collectors per registry: several clients in one process share them instead of failing on re-registration.
_collectors: "weakref.WeakKeyDictionary[Any, Tuple[Any, Any]]" = weakref.WeakKeyDictionary()


def prometheus_available() -> bool:
    """Return True when prometheus_client can be imported."""
    try:
        import prometheus_client  # noqa: F401
    except ImportError:
        return False
    return True


class PrometheusMetricsRecorder(MetricsRecorder):
    """Records request durations and retries into a Prometheus registry (the default one unless given)."""

    def __init__(self, registry: Optional[Any] = None) -> None:
        from prometheus_client import REGISTRY, Counter, Histogram

        registry = registry if registry is not None else REGISTRY
        with _lock:
            collectors = _collectors.get(registry)
            if collectors is None:
                collectors = (
                    Histogram("request_duration_seconds", "Duration of HTTP requests in seconds",
                              ["name", "status_code"], namespace=_NAMESPACE, subsystem=_SUBSYSTEM,
                              registry=registry),
                    Counter("retry_attempts", "Total number of retry attempts", ["url", "success"],
                            namespace=_NAMESPACE, subsystem=_SUBSYSTEM, registry=registry),
                )
                _collectors[registry] = collectors
        self._request_duration, self._retry_counter = collectors

    def record_request(self, name: str, status_code: int, duration_ms: int) -> None:
        """Observe one request duration."""
        self._request_duration.labels(name=name, status_code=str(status_code)).observe(duration_ms / 1000.0)

    def record_retry(self, url: str, attempt_number: int, success: bool) -> None:
        """Count one call that needed more than one attempt."""
        self._retry_counter.labels(url=url, success="true" if success else "false").inc()

    def get_recorder_name(self) -> str:
        """Return 'prometheus'."""
        return "prometheus"
