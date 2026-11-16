"""HTTP header names the SDK puts on the wire."""

from typing import Final

#: Workload identity, resolved once at client construction.
WORKLOAD: Final = "Grayskull-Workload"
#: SDK product/version, e.g. ``grayskull-python/0.3.0``.
USER_AGENT: Final = "User-Agent"
#: Credentials from the configured auth header provider.
AUTHORIZATION: Final = "Authorization"
#: Per-call correlation id, also exposed to logs as ``grayskullRequestId``.
REQUEST_ID: Final = "X-Request-Id"

#: Headers the SDK always owns; custom headers with these names are overwritten.
RESERVED: Final = frozenset(h.lower() for h in (AUTHORIZATION, REQUEST_ID))
