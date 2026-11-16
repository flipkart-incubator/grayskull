"""Request correlation for logs.

Every SDK call runs inside a request scope whose id is sent as ``X-Request-Id`` and
exposed to logging. Hooks run inside the scope of the poll cycle that observed the
update, so application logs emitted from a hook correlate with the SDK's own logs.

Attach :class:`RequestIdLogFilter` to a handler and use its attributes in the format
string. They match the MDC keys of the Java SDK and the slog keys of the Go SDK:

* ``%(grayskullRequestId)s``: the per-call correlation id
* ``%(projectId)s`` / ``%(secretName)s``: the secret being operated on

Each attribute is ``"-"`` outside a scope. To propagate your own id, wrap calls in
:func:`request_scope`.
"""

from __future__ import annotations

import logging
import uuid
from contextlib import contextmanager
from contextvars import ContextVar
from typing import Iterator, Optional, Tuple

__all__ = ["RequestIdLogFilter", "current_request_id", "request_scope", "secret_scope"]

_REQUEST_ID: ContextVar[Optional[str]] = ContextVar("grayskull_request_id", default=None)
_SECRET: ContextVar[Optional[Tuple[str, str]]] = ContextVar("grayskull_secret", default=None)

logging.getLogger("grayskull").addHandler(logging.NullHandler())


def current_request_id() -> Optional[str]:
    return _REQUEST_ID.get()


@contextmanager
def request_scope(request_id: Optional[str] = None, *, inherit: bool = True) -> Iterator[str]:
    """Bind a request id for the duration of the block.

    With ``inherit`` (default) an id already bound by an outer scope is reused,
    so callers can propagate their own correlation id into SDK calls.
    """
    rid = request_id or (_REQUEST_ID.get() if inherit else None) or str(uuid.uuid4())
    token = _REQUEST_ID.set(rid)
    try:
        yield rid
    finally:
        _REQUEST_ID.reset(token)


@contextmanager
def secret_scope(project_id: str, secret_name: str) -> Iterator[None]:
    """Bind ``projectId``/``secretName`` log attributes for the duration of the block."""
    token = _SECRET.set((project_id, secret_name))
    try:
        yield
    finally:
        _SECRET.reset(token)


class RequestIdLogFilter(logging.Filter):
    """Adds ``grayskullRequestId``, ``projectId`` and ``secretName`` to every record."""

    def filter(self, record: logging.LogRecord) -> bool:
        record.grayskullRequestId = _REQUEST_ID.get() or "-"
        project_id, secret_name = _SECRET.get() or ("-", "-")
        record.projectId = project_id
        record.secretName = secret_name
        return True
