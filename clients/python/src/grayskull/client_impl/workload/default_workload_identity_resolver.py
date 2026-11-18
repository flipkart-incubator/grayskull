"""Resolves the workload identity from the local hostname."""

import logging
import socket
from typing import Callable

from ...client_api.workload.workload_identity_resolver import WorkloadIdentityResolver

UNKNOWN_HOST = "UNKNOWN"
_log = logging.getLogger(__name__)


class DefaultWorkloadIdentityResolver(WorkloadIdentityResolver):
    """Uses the hostname, resolved once at construction; falls back to UNKNOWN."""

    def __init__(self, hostname_supplier: Callable[[], str] = socket.gethostname) -> None:
        identity = UNKNOWN_HOST
        try:
            hostname = hostname_supplier()
            if hostname and hostname.strip():
                identity = hostname
        except OSError:
            _log.warning("Could not resolve local hostname for the workload identity", exc_info=True)
        self._resolved_identity = identity

    def resolve(self) -> str:
        """Return the resolved hostname."""
        return self._resolved_identity
