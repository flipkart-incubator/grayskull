"""Carries the current X-Request-Id into SDK log lines, like Java's MDC and Go's context."""

import contextvars
import logging
from contextlib import contextmanager
from typing import Iterator, Optional

_request_id: contextvars.ContextVar[Optional[str]] = contextvars.ContextVar("grayskull_request_id", default=None)


def current_request_id() -> Optional[str]:
    """Return the request id of the operation running in this context, if any."""
    return _request_id.get()


@contextmanager
def request_id_scope(request_id: str) -> Iterator[str]:
    """Make request_id the current request id until the block exits."""
    token = _request_id.set(request_id)
    try:
        yield request_id
    finally:
        _request_id.reset(token)


class RequestIdFilter(logging.Filter):
    """Sets record.request_id and prefixes the message with [RequestId:...] when one is current."""

    def filter(self, record: logging.LogRecord) -> bool:
        request_id = _request_id.get()
        record.request_id = request_id or "-"
        if request_id and not getattr(record, "_grayskull_prefixed", False):
            record.msg = f"[RequestId:{request_id}] {record.msg}"
            record._grayskull_prefixed = True
        return True


_FILTER = RequestIdFilter()


def get_logger(name: str) -> logging.Logger:
    """Return the module logger with the request id filter attached."""
    logger = logging.getLogger(name)
    if _FILTER not in logger.filters:
        logger.addFilter(_FILTER)
    return logger
