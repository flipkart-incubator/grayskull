"""Connection, retry, polling and heartbeat settings for the Grayskull client."""

from dataclasses import dataclass, field, fields
from typing import Any, Dict
from urllib.parse import urlparse

from ...client_api.workload.workload_identity_resolver import WorkloadIdentityResolver
from ..workload.default_workload_identity_resolver import DefaultWorkloadIdentityResolver

DEFAULT_HEARTBEAT_INTERVAL_SECONDS = 86400


def _require_int(name: str, value: Any) -> int:
    # bool is a subclass of int, and floats (including NaN and inf) are not accepted
    if isinstance(value, bool) or not isinstance(value, int):
        raise ValueError(f"{name} must be an integer, got: {value!r}")
    return value


def _require_bool(name: str, value: Any) -> bool:
    if not isinstance(value, bool):
        raise ValueError(f"{name} must be a boolean, got: {value!r}")
    return value


def _validate_host(value: Any) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValueError("Host cannot be null or empty")
    host = value.strip().rstrip("/")
    parsed = urlparse(host)
    if parsed.scheme not in ("http", "https") or not parsed.netloc:
        raise ValueError(f"Invalid host URL: {host}")
    return host


def _validate_connection_timeout(value: Any) -> int:
    if _require_int("Connection timeout", value) <= 0:
        raise ValueError(f"Connection timeout must be positive, got: {value}")
    return value


def _validate_read_timeout(value: Any) -> int:
    if _require_int("Read timeout", value) <= 0:
        raise ValueError(f"Read timeout must be positive, got: {value}")
    return value


def _validate_max_connections(value: Any) -> int:
    if _require_int("Max connections", value) <= 0:
        raise ValueError(f"Max connections must be positive, got: {value}")
    return value


def _validate_max_retries(value: Any) -> int:
    _require_int("Max retries", value)
    if value < 1:
        raise ValueError(f"Max retries cannot be less than 1, got: {value}")
    if value > 10:
        raise ValueError(f"Max retries cannot be greater than 10, got: {value}")
    return value


def _validate_min_retry_delay(value: Any) -> int:
    if _require_int("Min retry delay", value) < 50:
        raise ValueError(f"Min retry delay must be at least 50ms, got: {value}")
    return value


def _validate_polling_interval(value: Any) -> int:
    if _require_int("Polling interval", value) < 1:
        raise ValueError(f"Polling interval must be at least 1 second, got: {value}")
    return value


def _validate_heartbeat_interval(value: Any) -> int:
    if _require_int("Heartbeat interval", value) < 0:
        raise ValueError(f"Heartbeat interval cannot be negative, got: {value}")
    return value or DEFAULT_HEARTBEAT_INTERVAL_SECONDS  # 0 means the 24-hour default


def _validate_workload_identity_resolver(value: Any) -> WorkloadIdentityResolver:
    return DefaultWorkloadIdentityResolver() if value is None else value


_VALIDATORS = {
    "host": _validate_host,
    "connection_timeout": _validate_connection_timeout,
    "read_timeout": _validate_read_timeout,
    "max_connections": _validate_max_connections,
    "max_retries": _validate_max_retries,
    "min_retry_delay": _validate_min_retry_delay,
    "metrics_enabled": lambda v: _require_bool("metrics_enabled", v),
    "polling_interval_seconds": _validate_polling_interval,
    "heartbeat_enabled": lambda v: _require_bool("heartbeat_enabled", v),
    "heartbeat_interval_seconds": _validate_heartbeat_interval,
    "workload_identity_resolver": _validate_workload_identity_resolver,
}


@dataclass
class GrayskullClientConfiguration:
    """Settings for GrayskullClientImpl; times are milliseconds unless the name says seconds.

    Every setting is validated when it is assigned, so an invalid value raises ValueError
    at the point it is set rather than later inside a background thread.
    """

    host: str
    connection_timeout: int = 10_000
    read_timeout: int = 30_000
    max_connections: int = 10
    max_retries: int = 3
    min_retry_delay: int = 100
    metrics_enabled: bool = True
    polling_interval_seconds: int = 60
    heartbeat_enabled: bool = True
    heartbeat_interval_seconds: int = DEFAULT_HEARTBEAT_INTERVAL_SECONDS
    workload_identity_resolver: WorkloadIdentityResolver = field(default_factory=DefaultWorkloadIdentityResolver)
    _default_headers: Dict[str, str] = field(default_factory=dict, repr=False)

    def __setattr__(self, name: str, value: Any) -> None:
        if name == "workload_identity_resolver" and value is None and name in self.__dict__:
            return  # like Java's setter, None keeps the current resolver instead of erasing it
        validator = _VALIDATORS.get(name)
        super().__setattr__(name, validator(value) if validator else value)

    def validate(self) -> None:
        """Raise ValueError for any invalid setting; a zero heartbeat interval becomes the 24-hour default."""
        for f in fields(self):
            if f.name in _VALIDATORS:
                setattr(self, f.name, getattr(self, f.name))

    def add_default_header(self, key: str, value: str) -> None:
        """Send this header on every request; SDK headers win on conflict."""
        if key and value:
            self._default_headers[key] = value

    def get_default_headers(self) -> Dict[str, str]:
        """Return a copy of the headers sent on every request."""
        return dict(self._default_headers)
