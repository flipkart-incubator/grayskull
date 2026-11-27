"""Sends the usage-ledger snapshot to POST /v1/client/heartbeat on an interval; a failed beat is retried once after about an hour."""

import json
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
from .request_context import get_logger, request_id_scope

HEARTBEAT_PATH = "/v1/client/heartbeat"
MAX_BATCH_SECRETS = 50
RETRY_DELAY_SECONDS = 60 * 60.0
_UNSUPPORTED_STATUS_CODES = frozenset({404, 405, 501})
_log = get_logger(__name__)


class HeartbeatOutcome(Enum):
    """Result of one heartbeat round."""

    SENT = "SENT"
    FAILED = "FAILED"
    UNSUPPORTED = "UNSUPPORTED"


def next_failures(failures: int) -> int:
    """1 after a failed beat (retry pending), 2 after the retry also failed (wait for the next interval)."""
    return 2 if failures == 1 else 1


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
        self._scheduler = self._new_thread()
        self._unsupported_logged = False

    def start(self) -> None:
        """Start the schedule; the first heartbeat goes out after one interval."""
        self._scheduler.start()

    def close(self, timeout_seconds: float = 10.0) -> None:
        """Stop the schedule; waits up to timeout_seconds for an in-flight heartbeat."""
        self._stopped.set()
        if self._scheduler.is_alive() and self._scheduler is not threading.current_thread():
            self._scheduler.join(timeout_seconds)
            if self._scheduler.is_alive():
                _log.warning("Heartbeat thread did not stop within %.0fs", timeout_seconds)

    def restart_after_fork(self) -> None:
        """In a forked child: the scheduler thread did not survive the fork, so start a new one."""
        if self._stopped.is_set():
            return
        self._stopped = threading.Event()
        self._scheduler = self._new_thread()
        self._scheduler.start()

    def _new_thread(self) -> threading.Thread:
        return threading.Thread(target=self._run, name="grayskull-heartbeat", daemon=True)

    def _run(self) -> None:
        stopped = self._stopped
        failures = 0
        while not stopped.wait(self._next_delay_seconds(failures)):
            try:
                outcome = self._beat_once()
            except Exception:  # a broken round must not end the schedule
                _log.warning("Heartbeat failed", exc_info=True)
                outcome = HeartbeatOutcome.FAILED
            failures = next_failures(failures) if outcome is HeartbeatOutcome.FAILED else 0

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
        request_id = str(uuid.uuid4())
        start = time.monotonic()
        status_code = 500
        with request_id_scope(request_id):
            try:
                body = json.dumps(HeartbeatRequest(chunk).to_dict()).encode("utf-8")
                status_code = self._http_client.do_post(self._heartbeat_url, body, request_id).status_code
                return HeartbeatOutcome.SENT
            except Exception as exc:  # any failure only affects this chunk
                if isinstance(exc, (GrayskullException, RetryableException)):
                    status_code = exc.status_code
                if status_code in _UNSUPPORTED_STATUS_CODES:
                    if not self._unsupported_logged:
                        self._unsupported_logged = True
                        _log.info("Grayskull server does not support client heartbeats yet (status %s); "
                                  "retrying after the full interval", status_code)
                    return HeartbeatOutcome.UNSUPPORTED
                _log.warning("Heartbeat for %d secret(s) failed (status %s); retrying once in about an hour: %s",
                             len(chunk), status_code, exc)
                return HeartbeatOutcome.FAILED
            finally:
                self._metrics_recorder.record_request(
                    "heartbeat", status_code, int((time.monotonic() - start) * 1000))

    def _next_delay_seconds(self, failures: int) -> float:
        """One retry after about an hour (± 10%) following a failure; otherwise the interval ± 1/24."""
        retry = failures == 1
        delay = min(RETRY_DELAY_SECONDS, self._interval_seconds) if retry else self._interval_seconds
        spread = delay / 10 if retry else delay / 24
        return max(delay + random.uniform(-spread, spread), 0.001)
