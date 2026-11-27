"""HTTP client for Grayskull: pooled keep-alive connections, single-attempt calls plus a retrying wrapper.

Built on http.client rather than urllib so that:
- redirects are never followed (urllib forwards Authorization to the redirect target, even https -> http);
- HTTP_PROXY / HTTPS_PROXY and similar environment variables are ignored, like the Go SDK;
- connect and read timeouts are separate, and each attempt also has an overall deadline;
- connections are reused, at most max_connections at a time.
"""

import http.client
import ssl
import threading
import time
from collections import deque
from typing import Callable, Deque, Dict, Optional, Tuple
from urllib.parse import SplitResult, urlsplit

from .auth.grayskull_auth_header_provider import GrayskullAuthHeaderProvider
from .metrics.metrics_recorder import MetricsRecorder
from .models.exceptions.grayskull_exception import GrayskullException
from .models.exceptions.retryable_exception import RetryableException
from .models.grayskull_client_configuration import GrayskullClientConfiguration
from .models.response.http_response import HttpResponse
from .request_context import get_logger
from .utils.retry_util import RetryUtil

_log = get_logger(__name__)
_JSON_CONTENT_TYPE = "application/json; charset=utf-8"
IDLE_CONNECTION_TTL_SECONDS = 300.0
_READ_CHUNK_BYTES = 64 * 1024
# Raised when a pooled keep-alive connection was closed by the server before our request reached it.
_STALE_CONNECTION_ERRORS = (
    http.client.RemoteDisconnected, ConnectionResetError, ConnectionAbortedError, BrokenPipeError)
# Transport failures that are worth retrying; HTTPException covers IncompleteRead, BadStatusLine, LineTooLong.
_TRANSPORT_ERRORS = (OSError, http.client.HTTPException)

_PoolKey = Tuple[str, str, Optional[int]]


def _is_retryable_status_code(status_code: int) -> bool:
    return status_code == 429 or 500 <= status_code < 600


class _DeadlineExceeded(TimeoutError):
    """The overall per-attempt deadline passed while the response was still arriving."""


class _ConnectionPool:
    """Keep-alive connections per (scheme, host, port), with at most max_connections in use at once."""

    def __init__(self, max_connections: int) -> None:
        self._max_connections = max_connections
        self._init_state()

    def _init_state(self) -> None:
        self._slots = threading.BoundedSemaphore(self._max_connections)
        self._lock = threading.Lock()
        self._idle: Dict[_PoolKey, Deque[Tuple[http.client.HTTPConnection, float]]] = {}
        self._closed = False

    def acquire_slot(self, deadline: float) -> bool:
        return self._slots.acquire(timeout=max(0.0, deadline - time.monotonic()))

    def release_slot(self) -> None:
        self._slots.release()

    def take_idle(self, key: _PoolKey) -> Optional[http.client.HTTPConnection]:
        """Return the most recently used idle connection for key, after evicting expired ones."""
        expired = []
        try:
            with self._lock:
                self._evict_expired_locked(time.monotonic(), expired)
                idle = self._idle.get(key)
                return idle.pop()[0] if idle else None
        finally:
            for conn in expired:
                conn.close()

    def put_idle(self, key: _PoolKey, conn: http.client.HTTPConnection) -> None:
        """Keep conn for reuse; closes it instead when the pool is closed or already full for key."""
        with self._lock:
            idle = self._idle.setdefault(key, deque())
            if not self._closed and len(idle) < self._max_connections:
                idle.append((conn, time.monotonic()))
                return
        conn.close()

    def close(self) -> None:
        """Close every idle connection; connections in use are closed when they are returned."""
        with self._lock:
            self._closed = True
            idle, self._idle = self._idle, {}
        for queue in idle.values():
            for conn, _ in queue:
                conn.close()

    def reset_after_fork(self) -> None:
        """In a forked child: drop sockets shared with the parent and recreate locks a parent thread may hold."""
        idle = self._idle
        self._init_state()
        for queue in idle.values():
            for conn, _ in queue:
                # closes only the child's copy of the file descriptor; the parent's connection is untouched
                conn.close()

    def _evict_expired_locked(self, now: float, expired: list) -> None:
        for queue in self._idle.values():
            while queue and now - queue[0][1] >= IDLE_CONNECTION_TTL_SECONDS:
                expired.append(queue.popleft()[0])


class GrayskullHttpClient:
    """Sends authenticated JSON requests; retries network errors, 429 and 5xx with backoff."""

    def __init__(
        self,
        auth_header_provider: GrayskullAuthHeaderProvider,
        configuration: GrayskullClientConfiguration,
        metrics_recorder: MetricsRecorder,
    ) -> None:
        self._auth_header_provider = auth_header_provider
        self._default_headers = configuration.get_default_headers()
        self._connect_timeout_seconds = configuration.connection_timeout / 1000.0
        self._read_timeout_seconds = configuration.read_timeout / 1000.0
        # Overall budget for one attempt, so a server trickling bytes cannot hold a call open indefinitely.
        self._attempt_timeout_seconds = self._connect_timeout_seconds + self._read_timeout_seconds
        self._retry_util = RetryUtil(configuration.max_retries, configuration.min_retry_delay)
        self._metrics_recorder = metrics_recorder
        self._ssl_context = ssl.create_default_context()
        self._pool = _ConnectionPool(configuration.max_connections)

    def do_get_with_retry(self, url: str, request_id: str) -> HttpResponse:
        """GET url, retrying transient failures."""
        return self._with_retry(url, lambda: self._execute("GET", url, None, request_id))

    def do_post_with_retry(self, url: str, json_body: bytes, request_id: str) -> HttpResponse:
        """POST a JSON body, retrying transient failures."""
        return self._with_retry(url, lambda: self._execute("POST", url, json_body, request_id))

    def do_post(self, url: str, json_body: bytes, request_id: str) -> HttpResponse:
        """POST a JSON body once; raises RetryableException or GrayskullException on failure."""
        return self._execute("POST", url, json_body, request_id)

    def close(self) -> None:
        """Close pooled connections."""
        self._pool.close()

    def reset_after_fork(self) -> None:
        """Called in a forked child so it never reuses the parent's sockets."""
        self._pool.reset_after_fork()

    def _with_retry(self, url: str, call: Callable[[], HttpResponse]) -> HttpResponse:
        try:
            response, attempts = self._retry_util.retry(call)
        except RetryableException as exc:
            if exc.attempts > 1:
                self._metrics_recorder.record_retry(url, exc.attempts, False)
            raise GrayskullException(
                exc.status_code, f"Request failed after {exc.attempts} attempt(s): {exc}", exc) from exc
        if attempts > 1:
            self._metrics_recorder.record_retry(url, attempts, True)
        return response

    def _execute(self, method: str, url: str, json_body: Optional[bytes], request_id: str) -> HttpResponse:
        headers = self._build_headers(request_id, json_body is not None)
        parts = urlsplit(url)
        if parts.scheme not in ("http", "https") or not parts.hostname:
            raise GrayskullException(0, f"Unsupported URL: {url}")
        key: _PoolKey = (parts.scheme, parts.hostname, parts.port)
        path = (parts.path or "/") + (f"?{parts.query}" if parts.query else "")
        deadline = time.monotonic() + self._attempt_timeout_seconds
        if not self._pool.acquire_slot(deadline):
            raise RetryableException(0, "Timed out waiting for a free connection")
        try:
            conn = self._pool.take_idle(key)
            if conn is not None:
                try:
                    return self._send(conn, key, method, path, json_body, headers, deadline, request_id)
                except RetryableException as exc:
                    if not isinstance(exc.__cause__, _STALE_CONNECTION_ERRORS):
                        raise
                    _log.debug("Pooled connection to %s was closed by the server; reconnecting", parts.hostname)
            conn = self._connect(parts, deadline)
            return self._send(conn, key, method, path, json_body, headers, deadline, request_id)
        finally:
            self._pool.release_slot()

    def _connect(self, parts: SplitResult, deadline: float) -> http.client.HTTPConnection:
        timeout = max(0.001, min(self._connect_timeout_seconds, deadline - time.monotonic()))
        conn: http.client.HTTPConnection
        if parts.scheme == "https":
            conn = http.client.HTTPSConnection(parts.hostname, parts.port, timeout=timeout, context=self._ssl_context)
        else:
            conn = http.client.HTTPConnection(parts.hostname, parts.port, timeout=timeout)
        try:
            conn.connect()
        except _TRANSPORT_ERRORS as exc:
            conn.close()
            raise RetryableException(0, f"Connection to {parts.hostname} failed: {exc!r}", exc) from exc
        return conn

    def _send(
        self,
        conn: http.client.HTTPConnection,
        key: _PoolKey,
        method: str,
        path: str,
        json_body: Optional[bytes],
        headers: Dict[str, str],
        deadline: float,
        request_id: str,
    ) -> HttpResponse:
        reusable = False
        try:
            sock = conn.sock
            self._apply_read_timeout(sock, deadline)
            conn.request(method, path, body=json_body, headers=headers)
            response = conn.getresponse()
            payload = self._read_body(response, sock, deadline)
            status = response.status
            location = response.getheader("Location")
            reusable = not response.will_close
        except _TRANSPORT_ERRORS as exc:
            raise RetryableException(0, f"HTTP request failed: {exc!r}", exc) from exc
        finally:
            if reusable:
                self._pool.put_idle(key, conn)
            else:
                conn.close()
        _log.debug("%s %s -> %s", method, path, status)
        if 200 <= status < 300:
            return HttpResponse(status, payload)
        detail = payload.decode("utf-8", errors="replace")
        if 300 <= status < 400:
            raise GrayskullException(status, f"Redirect to {location!r} not followed (status {status})")
        message = f"Request failed with status {status}: {detail}"
        if _is_retryable_status_code(status):
            raise RetryableException(status, message)
        raise GrayskullException(status, message)

    def _apply_read_timeout(self, sock, deadline: float) -> None:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise _DeadlineExceeded(f"request exceeded its {self._attempt_timeout_seconds:.3f}s deadline")
        if sock is not None and sock.fileno() != -1:
            sock.settimeout(min(self._read_timeout_seconds, remaining))

    def _read_body(self, response: http.client.HTTPResponse, sock, deadline: float) -> bytes:
        chunks = []
        while not response.isclosed():  # closed once the whole body has arrived, or the peer hung up
            self._apply_read_timeout(sock, deadline)
            chunk = response.read1(_READ_CHUNK_BYTES)
            if not chunk:
                break
            chunks.append(chunk)
        body = b"".join(chunks)
        if response.length:  # read1() returns b"" instead of raising when the peer closes early
            raise http.client.IncompleteRead(body, response.length)
        # Before Python 3.12, read1() does not mark the response finished when the body is complete, and the
        # connection then refuses its next request with ResponseNotReady. Closing the response releases it
        # for reuse; the socket itself stays open.
        response.close()
        return body

    def _build_headers(self, request_id: str, has_body: bool) -> Dict[str, str]:
        headers = dict(self._default_headers)
        headers["Authorization"] = self._auth_header()
        if request_id:  # like Java, an empty request id sends no header rather than an empty one
            headers["X-Request-Id"] = request_id
        else:
            headers.pop("X-Request-Id", None)
        if has_body:
            headers["Content-Type"] = _JSON_CONTENT_TYPE
        for name, value in headers.items():
            # values are never echoed: Authorization must not reach logs or exception messages
            if "\r" in value or "\n" in value:
                raise GrayskullException(0, f"Header {name} contains a line break and cannot be sent")
            try:
                name.encode("ascii")
                value.encode("latin-1")
            except UnicodeEncodeError as exc:
                raise GrayskullException(
                    0, f"Header {name} contains characters outside ISO-8859-1 and cannot be sent", exc) from exc
        return headers

    def _auth_header(self) -> str:
        try:
            header = self._auth_header_provider.get_auth_header()
        except GrayskullException:
            raise
        except Exception as exc:  # e.g. the token service rejecting the exchange
            raise GrayskullException(getattr(exc, "code", 0) or 0, f"Failed to obtain auth header: {exc}", exc) from exc
        if not isinstance(header, str) or not header.strip():
            raise GrayskullException(0, "Auth header provider returned a null or empty header")
        return header
