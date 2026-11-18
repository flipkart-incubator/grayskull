"""Sends the usage-ledger snapshot to POST /v1/client/heartbeat on an interval, with backoff on failure."""

import json
import logging
import random
import threading
import time
import uuid
from enum import Enum
from typing import Callable, List

from .grayskull_http_client import GrayskullHttpClient
from .metrics.metrics_recorder import MetricsRecorder
from .models.exceptions.grayskull_exception import GrayskullException
from .models.exceptions.retryable_exception import RetryableException
from .models.request.heartbeat_request import HeartbeatRequest, HeldSecret

HEARTBEAT_PATH = "/v1/client/heartbeat"
MAX_BATCH_SECRETS = 50
BACKOFF_BASE_SECONDS = 5 * 60.0
BACKOFF_CAP_SECONDS = 60 * 60.0
_UNSUPPORTED_STATUS_CODES = frozenset({404, 405, 501})
_log = logging.getLogger(__name__)


class HeartbeatOutcome(Enum):
    """Result of one heartbeat round."""

    SENT = "SENT"
    FAILED = "FAILED"
    UNSUPPORTED = "UNSUPPORTED"


class HeartbeatScheduler:
    """Sends one heartbeat per interval; each chunk is a single attempt, never retried inline."""

    def __init__(
        self,
        http_client: GrayskullHttpClient,
        base_url: str,
        interval_seconds: float,
        held_secrets: Callable[[], List[HeldSecret]],
        metrics_recorder: MetricsRecorder,
    ) -> None:
        self._http_client = http_client
        self._heartbeat_url = f"{base_url}{HEARTBEAT_PATH}"
        self._interval_seconds = interval_seconds
        self._held_secrets = held_secrets
        self._metrics_recorder = metrics_recorder
        self._stopped = threading.Event()
        self._scheduler = threading.Thread(target=self._run, name="grayskull-heartbeat", daemon=True)
        self._unsupported_logged = False

    def start(self) -> None:
        """Start the schedule; the first heartbeat goes out after one interval."""
        self._scheduler.start()

    def close(self, timeout_seconds: float = 10.0) -> None:
        """Stop the schedule; waits up to timeout_seconds for an in-flight heartbeat."""
        self._stopped.set()
        if self._scheduler.is_alive() and self._scheduler is not threading.current_thread():
            self._scheduler.join(timeout_seconds)

    def _run(self) -> None:
        failures = 0
        while not self._stopped.wait(self._next_delay_seconds(failures)):
            try:
                outcome = self._beat_once()
            except Exception:  # a broken round must not end the schedule
                _log.exception("Heartbeat failed")
                outcome = HeartbeatOutcome.FAILED
            failures = failures + 1 if outcome is HeartbeatOutcome.FAILED else 0

    def _beat_once(self) -> HeartbeatOutcome:
        held = self._held_secrets()
        if not held:
            return HeartbeatOutcome.SENT
        result = HeartbeatOutcome.SENT
        for start in range(0, len(held), MAX_BATCH_SECRETS):
            outcome = self._send(held[start:start + MAX_BATCH_SECRETS])
            if outcome is HeartbeatOutcome.UNSUPPORTED:
                return outcome
            if outcome is HeartbeatOutcome.FAILED:
                result = HeartbeatOutcome.FAILED
        return result

    def _send(self, chunk: List[HeldSecret]) -> HeartbeatOutcome:
        body = json.dumps(HeartbeatRequest(chunk).to_dict()).encode("utf-8")
        start = time.monotonic()
        status_code = 500
        try:
            status_code = self._http_client.do_post(self._heartbeat_url, body, str(uuid.uuid4())).status_code
            return HeartbeatOutcome.SENT
        except (GrayskullException, RetryableException) as exc:
            status_code = exc.status_code or status_code
            if exc.status_code in _UNSUPPORTED_STATUS_CODES:
                if not self._unsupported_logged:
                    self._unsupported_logged = True
                    _log.info("Grayskull server does not support client heartbeats yet (status %s); "
                              "retrying after the full interval", exc.status_code)
                return HeartbeatOutcome.UNSUPPORTED
            _log.warning("Heartbeat for %d secret(s) failed (status %s); retrying with backoff",
                         len(chunk), exc.status_code)
            return HeartbeatOutcome.FAILED
        finally:
            self._metrics_recorder.record_request("heartbeat", status_code, int((time.monotonic() - start) * 1000))

    def _next_delay_seconds(self, failures: int) -> float:
        """Interval ± 1/24 on success; 5–60 min backoff ± 10% on failure."""
        if failures == 0:
            delay, spread = self._interval_seconds, self._interval_seconds / 24
        else:
            delay = min(BACKOFF_BASE_SECONDS * (2 ** min(failures - 1, 20)), BACKOFF_CAP_SECONDS, self._interval_seconds)
            spread = delay / 10
        return max(delay + random.uniform(-spread, spread), 0.001)
