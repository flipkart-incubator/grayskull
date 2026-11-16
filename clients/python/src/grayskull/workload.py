"""Default workload identity resolution."""

from __future__ import annotations

import logging
import socket

__all__ = ["HostnameWorkloadIdentityResolver", "StaticWorkloadIdentityResolver"]

_log = logging.getLogger("grayskull.workload")
UNKNOWN = "UNKNOWN"


class HostnameWorkloadIdentityResolver:
    """Identifies the workload by the local hostname, resolved once at construction."""

    __slots__ = ("_identity",)

    def __init__(self) -> None:
        try:
            hostname = socket.gethostname().strip()
        except OSError as exc:
            _log.warning("could not resolve local hostname for workload identity: %s", exc)
            hostname = ""
        self._identity = hostname or UNKNOWN

    def resolve(self) -> str:
        return self._identity


class StaticWorkloadIdentityResolver:
    """Returns a fixed identity; handy for tests and non-standard platforms."""

    __slots__ = ("_identity",)

    def __init__(self, identity: str) -> None:
        self._identity = identity

    def resolve(self) -> str:
        return self._identity
