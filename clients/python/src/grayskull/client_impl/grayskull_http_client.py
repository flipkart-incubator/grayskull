"""HTTP client for Grayskull: single-attempt calls plus a retrying wrapper."""

import logging
import socket
import time
import urllib.error
import urllib.request
from typing import Callable, Optional

from .auth.grayskull_auth_header_provider import GrayskullAuthHeaderProvider
from .metrics.metrics_recorder import MetricsRecorder
from .models.exceptions.grayskull_exception import GrayskullException
from .models.exceptions.retryable_exception import RetryableException
from .models.grayskull_client_configuration import GrayskullClientConfiguration
from .models.response.http_response import HttpResponse
from .utils.retry_util import RetryUtil

_log = logging.getLogger(__name__)
_JSON_CONTENT_TYPE = "application/json; charset=utf-8"


def _is_retryable_status_code(status_code: int) -> bool:
    return status_code == 429 or 500 <= status_code < 600


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
        self._timeout_seconds = (configuration.connection_timeout + configuration.read_timeout) / 1000.0
        self._retry_util = RetryUtil(configuration.max_retries, configuration.min_retry_delay)
        self._metrics_recorder = metrics_recorder

    def do_get_with_retry(self, url: str, request_id: str) -> HttpResponse:
        """GET url, retrying transient failures."""
        return self._with_retry(url, lambda: self._execute("GET", url, None, request_id))

    def do_post_with_retry(self, url: str, json_body: bytes, request_id: str) -> HttpResponse:
        """POST a JSON body, retrying transient failures."""
        return self._with_retry(url, lambda: self._execute("POST", url, json_body, request_id))

    def do_post(self, url: str, json_body: bytes, request_id: str) -> HttpResponse:
        """POST a JSON body once; raises RetryableException or GrayskullException on failure."""
        return self._execute("POST", url, json_body, request_id)

    def _with_retry(self, url: str, call: Callable[[], HttpResponse]) -> HttpResponse:
        try:
            response, attempts = self._retry_util.retry(call)
        except RetryableException as exc:
            self._metrics_recorder.record_retry(url, -1, False)
            raise GrayskullException(exc.status_code, f"Request failed after retries: {exc}", exc) from exc
        if attempts > 1:
            self._metrics_recorder.record_retry(url, attempts, True)
        return response

    def _execute(self, method: str, url: str, json_body: Optional[bytes], request_id: str) -> HttpResponse:
        headers = dict(self._default_headers)
        headers["Authorization"] = self._auth_header(request_id)
        headers["X-Request-Id"] = request_id
        if json_body is not None:
            headers["Content-Type"] = _JSON_CONTENT_TYPE
        request = urllib.request.Request(url, data=json_body, headers=headers, method=method)
        start = time.monotonic()
        try:
            with urllib.request.urlopen(request, timeout=self._timeout_seconds) as response:
                body = response.read()
                status = response.status
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")
            self._record(url, exc.code, start)
            message = f"Request failed with status {exc.code}: {detail}"
            if _is_retryable_status_code(exc.code):
                raise RetryableException(exc.code, message) from exc
            raise GrayskullException(exc.code, message) from exc
        except (urllib.error.URLError, socket.timeout, ConnectionError, OSError) as exc:
            self._record(url, 0, start)
            raise RetryableException(0, f"HTTP request failed: {exc}", exc) from exc
        self._record(url, status, start)
        _log.debug("[RequestId:%s] %s %s -> %s", request_id, method, url, status)
        return HttpResponse(status, body)

    def _auth_header(self, request_id: str) -> str:
        try:
            return self._auth_header_provider.get_auth_header()
        except GrayskullException:
            raise
        except Exception as exc:  # e.g. the token service rejecting the exchange
            raise GrayskullException(getattr(exc, "code", 0) or 0, f"Failed to obtain auth header: {exc}", exc) from exc

    def _record(self, url: str, status_code: int, start: float) -> None:
        self._metrics_recorder.record_request(url, status_code, int((time.monotonic() - start) * 1000))
