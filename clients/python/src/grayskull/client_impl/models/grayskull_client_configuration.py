"""Connection, retry, polling and heartbeat settings for the Grayskull client."""

from dataclasses import dataclass, field
from typing import Dict
from urllib.parse import urlparse

from ...client_api.workload.workload_identity_resolver import WorkloadIdentityResolver
from ..workload.default_workload_identity_resolver import DefaultWorkloadIdentityResolver

DEFAULT_HEARTBEAT_INTERVAL_SECONDS = 86400


@dataclass
class GrayskullClientConfiguration:
    """Settings for GrayskullClientImpl; times are milliseconds unless the name says seconds."""

    host: str
    connection_timeout: int = 10_000
    read_timeout: int = 30_000
    max_retries: int = 3
    min_retry_delay: int = 100
    metrics_enabled: bool = True
    polling_interval_seconds: float = 60
    heartbeat_enabled: bool = True
    heartbeat_interval_seconds: float = DEFAULT_HEARTBEAT_INTERVAL_SECONDS
    workload_identity_resolver: WorkloadIdentityResolver = field(default_factory=DefaultWorkloadIdentityResolver)
    _default_headers: Dict[str, str] = field(default_factory=dict, repr=False)

    def __post_init__(self) -> None:
        self.validate()

    def validate(self) -> None:
        """Raise ValueError for any invalid setting; a zero heartbeat interval becomes the 24-hour default."""
        if not self.host or not self.host.strip():
            raise ValueError("Host cannot be null or empty")
        self.host = self.host.rstrip("/")
        parsed = urlparse(self.host)
        if parsed.scheme not in ("http", "https") or not parsed.netloc:
            raise ValueError(f"Invalid host URL: {self.host}")
        if self.connection_timeout <= 0:
            raise ValueError(f"Connection timeout must be positive, got: {self.connection_timeout}")
        if self.read_timeout <= 0:
            raise ValueError(f"Read timeout must be positive, got: {self.read_timeout}")
        if self.max_retries < 1:
            raise ValueError(f"Max retries cannot be less than 1, got: {self.max_retries}")
        if self.max_retries > 10:
            raise ValueError(f"Max retries cannot be greater than 10, got: {self.max_retries}")
        if self.min_retry_delay < 50:
            raise ValueError(f"Min retry delay must be at least 50ms, got: {self.min_retry_delay}")
        if self.polling_interval_seconds <= 0:
            raise ValueError(f"Polling interval must be positive, got: {self.polling_interval_seconds}")
        if self.heartbeat_interval_seconds < 0:
            raise ValueError(f"Heartbeat interval cannot be negative, got: {self.heartbeat_interval_seconds}")
        if self.heartbeat_interval_seconds == 0:
            self.heartbeat_interval_seconds = DEFAULT_HEARTBEAT_INTERVAL_SECONDS
        if self.workload_identity_resolver is None:
            self.workload_identity_resolver = DefaultWorkloadIdentityResolver()

    def add_default_header(self, key: str, value: str) -> None:
        """Send this header on every request; SDK headers win on conflict."""
        if key and value:
            self._default_headers[key] = value

    def get_default_headers(self) -> Dict[str, str]:
        """Return a copy of the headers sent on every request."""
        return dict(self._default_headers)
