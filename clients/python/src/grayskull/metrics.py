"""Metrics recording.

Prometheus support is an optional extra (``pip install grayskull-client[prometheus]``).
Metric names, label names and buckets match the Go SDK, so existing dashboards work:

* ``grayskull_http_client_request_duration_seconds{name, status_code}`` (histogram)
* ``grayskull_http_client_retry_attempts_total{url, success}`` (counter)

Unlike the Go SDK, ``name``/``url`` labels carry route *templates* and operation names,
never concrete URLs or secret names, so label cardinality stays bounded.
"""

from __future__ import annotations

import logging
import threading
from typing import Any, Dict, Optional, Protocol, Tuple

__all__ = [
    "MetricsRecorder",
    "NoopMetricsRecorder",
    "PrometheusMetricsRecorder",
    "default_metrics_recorder",
]

_log = logging.getLogger("grayskull.metrics")

# prometheus.DefBuckets from client_golang; Python's defaults differ.
GO_DEFAULT_BUCKETS = (0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0)


class MetricsRecorder(Protocol):
    def record_request(self, name: str, status_code: int, duration_seconds: float) -> None: ...

    def record_retry(self, url: str, attempts: int, success: bool) -> None: ...


class NoopMetricsRecorder:
    __slots__ = ()

    def record_request(self, name: str, status_code: int, duration_seconds: float) -> None:
        pass

    def record_retry(self, url: str, attempts: int, success: bool) -> None:
        pass


# Collectors are registered once per registry: Prometheus rejects duplicate names, so
# building a second client (or one per test) must reuse the existing collectors.
_collectors: Dict[int, Tuple[Any, Any, Any]] = {}
_collectors_lock = threading.Lock()


def _collectors_for(registry: Any) -> Tuple[Any, Any]:
    from prometheus_client import REGISTRY, Counter, Histogram

    registry = registry if registry is not None else REGISTRY
    with _collectors_lock:
        cached = _collectors.get(id(registry))
        if cached is None or cached[0] is not registry:  # guard against id() reuse
            histogram = Histogram(
                "grayskull_http_client_request_duration_seconds",
                "Duration of HTTP requests in seconds",
                ("name", "status_code"),
                buckets=GO_DEFAULT_BUCKETS,
                registry=registry,
            )
            counter = Counter(
                "grayskull_http_client_retry_attempts",  # exposed as *_total
                "Total number of retry attempts",
                ("url", "success"),
                registry=registry,
            )
            cached = _collectors[id(registry)] = (registry, histogram, counter)
        return cached[1], cached[2]


class PrometheusMetricsRecorder:
    """Records into a ``prometheus_client`` registry (the global one by default)."""

    __slots__ = ("_histogram", "_counter")

    def __init__(self, registry: Optional[Any] = None) -> None:
        try:
            self._histogram, self._counter = _collectors_for(registry)
        except ImportError as exc:  # pragma: no cover - exercised only without the extra
            raise ImportError("install 'grayskull-client[prometheus]' to enable Prometheus metrics") from exc

    def record_request(self, name: str, status_code: int, duration_seconds: float) -> None:
        self._histogram.labels(name, str(status_code)).observe(duration_seconds)

    def record_retry(self, url: str, attempts: int, success: bool) -> None:
        self._counter.labels(url, "true" if success else "false").inc()


def default_metrics_recorder(enabled: bool) -> MetricsRecorder:
    """Prometheus when enabled and installed, otherwise a no-op recorder."""
    if not enabled:
        return NoopMetricsRecorder()
    try:
        return PrometheusMetricsRecorder()
    except ImportError:
        _log.debug("prometheus-client not installed; metrics disabled")
        return NoopMetricsRecorder()
