"""Public contracts of the Grayskull SDK (the Python counterpart of ``client-api``).

Everything here is a structural ``Protocol``: implementations never need to
inherit from these classes, they only need the right shape.
"""

from __future__ import annotations

from types import TracebackType
from typing import Callable, Optional, Protocol, Type, runtime_checkable

from grayskull.api.models import SecretRef, SecretValue

__all__ = [
    "AuthHeaderProvider",
    "GrayskullClient",
    "RefreshHandlerRef",
    "SecretRef",
    "SecretRefreshHook",
    "SecretValue",
    "WorkloadIdentityResolver",
]

#: Callback invoked with the new value whenever a watched secret changes.
#: It runs on an SDK worker thread; raising is logged and does not affect other hooks.
SecretRefreshHook = Callable[[SecretValue], None]


@runtime_checkable
class AuthHeaderProvider(Protocol):
    """Produces the ``Authorization`` header value for each outbound request.

    Called concurrently from several threads, so implementations must be thread-safe.
    Token-based providers should cache and refresh internally; this is on the hot path.
    """

    def get_auth_header(self) -> str: ...


@runtime_checkable
class WorkloadIdentityResolver(Protocol):
    """Resolves the identity sent in the ``Grayskull-Workload`` header (once, at startup)."""

    def resolve(self) -> str: ...


@runtime_checkable
class RefreshHandlerRef(Protocol):
    """Handle returned by :meth:`GrayskullClient.register_refresh_hook`."""

    @property
    def secret_ref(self) -> str: ...

    @property
    def is_active(self) -> bool: ...

    def unregister(self) -> None:
        """Stop future invocations. Idempotent."""


@runtime_checkable
class GrayskullClient(Protocol):
    """The Grayskull read client."""

    def get_secret(self, secret_ref: str) -> SecretValue:
        """Fetch the current value of ``"projectId:secretName"``."""

    def register_refresh_hook(self, secret_ref: str, hook: SecretRefreshHook) -> RefreshHandlerRef:
        """Invoke ``hook`` whenever the server reports a newer version of ``secret_ref``."""

    def close(self) -> None:
        """Stop background work and release connections. Idempotent."""

    def __enter__(self) -> "GrayskullClient": ...

    def __exit__(
        self,
        exc_type: Optional[Type[BaseException]],
        exc: Optional[BaseException],
        tb: Optional[TracebackType],
    ) -> None: ...
