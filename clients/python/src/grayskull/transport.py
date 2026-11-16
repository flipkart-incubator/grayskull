"""Pooled HTTP transport with retries, auth, correlation ids and metrics.

Public so that companion SDKs (e.g. admin/secret-manager clients) can share one
battle-tested transport instead of re-implementing retry semantics.
"""

from __future__ import annotations

import json
import logging
import random
import threading
import time
from dataclasses import dataclass
from typing import Any, Callable, Mapping, Optional, Protocol

import httpx

from grayskull import headers as H
from grayskull.errors import GrayskullError, RetryableError
from grayskull.metrics import MetricsRecorder, NoopMetricsRecorder
from grayskull.observability import current_request_id

__all__ = ["HttpTransport", "Response", "TransportSettings", "backoff_delay", "is_retryable_status"]

_log = logging.getLogger("grayskull.transport")

_JSON_CONTENT_TYPE = "application/json; charset=utf-8"
_MAX_ERROR_BODY = 2048


class TransportSettings(Protocol):
    """Read-only attributes the transport takes from a configuration object."""

    @property
    def host(self) -> str: ...
    @property
    def connect_timeout(self) -> float: ...
    @property
    def read_timeout(self) -> float: ...
    @property
    def max_connections(self) -> int: ...
    @property
    def max_keepalive_connections(self) -> int: ...
    @property
    def keepalive_expiry(self) -> float: ...
    @property
    def max_retries(self) -> int: ...
    @property
    def min_retry_delay(self) -> float: ...
    @property
    def max_retry_delay(self) -> float: ...


@dataclass(frozen=True)
class Response:
    status_code: int
    body: Any  # decoded JSON, or None for an empty body


def is_retryable_status(status_code: int, idempotent: bool = True) -> bool:
    """429 and 5xx are transient; every other 4xx is a caller error.

    For non-idempotent requests only statuses that guarantee the request was not
    processed (429, 503) are retried; a 500/502/504 may hide a committed write.
    """
    if not idempotent:
        return status_code in (429, 503)
    return status_code == 429 or 500 <= status_code < 600


# Failures that happen before the request reaches the server: always safe to retry.
_NOT_SENT_ERRORS = (httpx.ConnectError, httpx.ConnectTimeout, httpx.PoolTimeout)


def backoff_delay(attempt: int, base: float, cap: float, rng: Callable[[], float] = random.random) -> float:
    """Delay before retry number ``attempt`` (1-based): ``min(base·2^(n-1), cap)`` plus up to 25% jitter."""
    delay = min(base * 2.0 ** (attempt - 1), cap)
    return delay + delay * 0.25 * rng()


def _parse_retry_after(value: Optional[str]) -> Optional[float]:
    if not value:
        return None
    try:
        return max(0.0, float(value))
    except ValueError:
        return None  # HTTP-date form is not worth the complexity here


class HttpTransport:
    """Thread-safe; one instance is shared by every thread of a client.

    Header precedence (lowest to highest): configured default headers, then
    ``Authorization`` and ``X-Request-Id``, which the SDK always owns.
    """

    def __init__(
        self,
        settings: TransportSettings,
        auth_header: Callable[[], str],
        *,
        headers: Optional[Mapping[str, str]] = None,
        metrics: Optional[MetricsRecorder] = None,
        transport: Optional[httpx.BaseTransport] = None,
    ) -> None:
        self._auth_header = auth_header
        self._metrics = metrics or NoopMetricsRecorder()
        self._max_attempts = max(1, settings.max_retries)
        self._base_delay = settings.min_retry_delay
        self._max_delay = settings.max_retry_delay
        self._client = httpx.Client(
            base_url=settings.host,
            headers=dict(headers or {}),
            timeout=httpx.Timeout(
                connect=settings.connect_timeout,
                read=settings.read_timeout,
                write=settings.read_timeout,
                pool=settings.connect_timeout,
            ),
            limits=httpx.Limits(
                max_connections=settings.max_connections,
                max_keepalive_connections=settings.max_keepalive_connections,
                keepalive_expiry=settings.keepalive_expiry,
            ),
            follow_redirects=False,
            transport=transport,
        )

    def request(
        self,
        method: str,
        path: str,
        *,
        route: str,
        json_body: Any = None,
        params: Optional[Mapping[str, str]] = None,
        auth_header: Optional[Callable[[], str]] = None,
        cancel: Optional[threading.Event] = None,
        idempotent: bool = True,
    ) -> Response:
        """Send with retries; returns the first non-error response.

        ``route`` is a low-cardinality template (e.g. ``/v1/projects/{p}/secrets``)
        used for metrics. ``cancel``, when set, aborts backoff waits promptly.
        ``idempotent=False`` (writes that must not be duplicated) restricts retries
        to failures where the server provably did not process the request.
        Raises ``GrayskullError`` carrying the last HTTP status on failure.
        """
        content = None if json_body is None else json.dumps(json_body, separators=(",", ":")).encode()
        get_auth = auth_header or self._auth_header
        attempt = 0
        while True:
            attempt += 1
            try:
                response = self._send_once(method, path, route, content, params, get_auth, idempotent)
            except RetryableError as exc:
                if attempt >= self._max_attempts:
                    self._record_retry(route, attempt, success=False)
                    raise GrayskullError(f"failed after {attempt} attempts", exc.status_code, exc) from exc
                delay = backoff_delay(attempt, self._base_delay, self._max_delay)
                if exc.retry_after is not None:
                    delay = min(max(delay, exc.retry_after), self._max_delay)
                _log.debug("transient failure on %s %s (attempt %d), retrying in %.3fs: %s",
                           method, route, attempt, delay, exc)
                if cancel is not None and cancel.wait(delay):
                    self._record_retry(route, attempt, success=False)
                    raise GrayskullError("request cancelled during retry", exc.status_code, exc) from exc
                if cancel is None:
                    time.sleep(delay)
                continue
            except GrayskullError:
                self._record_retry(route, attempt, success=False)
                raise
            self._record_retry(route, attempt, success=True)
            return response

    def _send_once(
        self,
        method: str,
        path: str,
        route: str,
        content: Optional[bytes],
        params: Optional[Mapping[str, str]],
        get_auth: Callable[[], str],
        idempotent: bool,
    ) -> Response:
        try:
            auth = get_auth()
        except GrayskullError:
            raise
        except Exception as exc:  # provider failures are not retried: they rarely heal in ms
            raise GrayskullError("failed to get auth header", cause=exc) from exc
        if not auth:
            raise GrayskullError("auth header cannot be empty")

        request_headers = {H.AUTHORIZATION: auth}
        rid = current_request_id()
        if rid:
            request_headers[H.REQUEST_ID] = rid
        if content is not None:
            request_headers["Content-Type"] = _JSON_CONTENT_TYPE

        start = time.perf_counter()
        try:
            resp = self._client.request(method, path, content=content, params=params, headers=request_headers)
        except httpx.TransportError as exc:  # connect/read timeouts, resets, DNS, protocol errors
            if idempotent or isinstance(exc, _NOT_SENT_ERRORS):
                raise RetryableError("HTTP request failed", cause=exc) from exc
            raise GrayskullError("HTTP request failed; not retried as it may have been applied", cause=exc) from exc
        status = resp.status_code
        self._metrics.record_request(route, status, time.perf_counter() - start)
        _log.debug("%s %s -> %d (%d bytes)", method, route, status, len(resp.content))

        if status >= 400:
            message = f"request failed with status {status}: {resp.text[:_MAX_ERROR_BODY]}"
            if is_retryable_status(status, idempotent):
                raise RetryableError(message, status, retry_after=_parse_retry_after(resp.headers.get("Retry-After")))
            raise GrayskullError(message, status)

        if not resp.content:
            return Response(status, None)
        try:
            return Response(status, resp.json())
        except ValueError as exc:
            raise GrayskullError("failed to decode response body", status, exc) from exc

    def _record_retry(self, route: str, attempts: int, *, success: bool) -> None:
        if attempts > 1:
            self._metrics.record_retry(route, attempts, success)

    def close(self) -> None:
        self._client.close()

    def abandon(self) -> None:
        """Drop the pool without closing it (post-fork child: sockets belong to the parent)."""
        self._client = None  # type: ignore[assignment]
