"""Client configuration."""

from __future__ import annotations

import dataclasses
import math
from dataclasses import dataclass, field
from types import MappingProxyType
from typing import Any, Mapping, Optional
from urllib.parse import urlsplit

from grayskull.api import WorkloadIdentityResolver
from grayskull.errors import InvalidArgumentError

__all__ = [
    "DEFAULT_HEARTBEAT_INTERVAL",
    "DEFAULT_POLLING_INTERVAL",
    "GrayskullClientConfig",
    "validate_host",
    "validate_max_retries",
]

DEFAULT_POLLING_INTERVAL = 60.0
DEFAULT_HEARTBEAT_INTERVAL = 24 * 60 * 60.0


def validate_host(host: Any) -> str:
    """Return ``host`` stripped and without a trailing slash, or raise ``InvalidArgumentError``."""
    if not isinstance(host, str) or not host.strip():
        raise InvalidArgumentError("host is required")
    normalised: str = host.strip().rstrip("/")
    parts = urlsplit(normalised)
    if parts.scheme not in ("http", "https") or not parts.netloc:
        raise InvalidArgumentError(f"invalid host URL (must start with http:// or https://): {normalised}")
    return normalised


MAX_RETRIES_LIMIT = 10


def validate_max_retries(value: Any) -> None:
    """Total attempts must be within [1, 10], as in the Java SDK (0 meant 1 attempt here and 3 in Go)."""
    if isinstance(value, bool) or not isinstance(value, int) or not 1 <= value <= MAX_RETRIES_LIMIT:
        raise InvalidArgumentError(f"max_retries must be between 1 and {MAX_RETRIES_LIMIT}, got: {value!r}")


def _freeze_headers(headers: Optional[Mapping[str, str]]) -> Mapping[str, str]:
    # Blank names/values are dropped, matching AddDefaultHeader in the Go/Java SDKs.
    return MappingProxyType({k: v for k, v in (headers or {}).items() if k and v})


@dataclass(frozen=True)
class GrayskullClientConfig:
    """Immutable, validated client configuration.

    Durations are in **seconds** (floats). Derive variants with :meth:`replace`.

    =============================  ============  ==========================================
    Field                          Default       Meaning
    =============================  ============  ==========================================
    host                           (required)    Grayskull base URL
    connect_timeout                10.0          TCP/TLS connect timeout
    read_timeout                   30.0          Socket read timeout
    max_connections                10            Connection pool size
    max_keepalive_connections      10            Idle connections kept in the pool
    keepalive_expiry               300.0         Idle connection lifetime
    max_retries                    3             Total attempts (1-10) for transient failures
    min_retry_delay                0.1           Base exponential-backoff delay
    max_retry_delay                60.0          Backoff cap
    metrics_enabled                True          Publish Prometheus metrics (needs extra)
    polling_interval               60.0          Refresh-hook poll period
    heartbeat_enabled              True          Report held secret versions
    heartbeat_interval             86400.0       Heartbeat period (± 1/24 jitter)
    workload_identity_resolver     hostname      ``Grayskull-Workload`` header source
    default_headers                {}            Extra headers on every request
    =============================  ============  ==========================================
    """

    host: str
    connect_timeout: float = 10.0
    read_timeout: float = 30.0
    max_connections: int = 10
    max_keepalive_connections: int = 10
    keepalive_expiry: float = 300.0
    max_retries: int = 3
    min_retry_delay: float = 0.1
    max_retry_delay: float = 60.0
    metrics_enabled: bool = True
    polling_interval: float = DEFAULT_POLLING_INTERVAL
    heartbeat_enabled: bool = True
    heartbeat_interval: float = DEFAULT_HEARTBEAT_INTERVAL
    workload_identity_resolver: Optional[WorkloadIdentityResolver] = None
    default_headers: Mapping[str, str] = field(default_factory=dict)

    def __post_init__(self) -> None:
        # Frozen dataclass: normalised values are written via object.__setattr__.
        object.__setattr__(self, "host", validate_host(self.host))
        object.__setattr__(self, "default_headers", _freeze_headers(self.default_headers))

        for name in ("connect_timeout", "read_timeout", "keepalive_expiry", "min_retry_delay", "max_retry_delay"):
            _require(name, getattr(self, name), allow_zero=True)
        for name in ("polling_interval", "heartbeat_interval"):
            _require(name, getattr(self, name), allow_zero=False)
        if not isinstance(self.max_connections, int) or self.max_connections <= 0:
            raise InvalidArgumentError("max_connections must be greater than 0")
        if not isinstance(self.max_keepalive_connections, int) or self.max_keepalive_connections < 0:
            raise InvalidArgumentError("max_keepalive_connections cannot be negative")
        validate_max_retries(self.max_retries)
        if self.workload_identity_resolver is not None and not callable(
            getattr(self.workload_identity_resolver, "resolve", None)
        ):
            raise InvalidArgumentError("workload_identity_resolver must define resolve() -> str")

    def replace(self, **changes: Any) -> "GrayskullClientConfig":
        """Return a copy with ``changes`` applied (and re-validated)."""
        return dataclasses.replace(self, **changes)


def _require(name: str, value: Any, *, allow_zero: bool) -> None:
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        raise InvalidArgumentError(f"{name} must be a finite number")
    if value < 0 or (value == 0 and not allow_zero):
        raise InvalidArgumentError(f"{name} must be {'>= 0' if allow_zero else '> 0'}")
