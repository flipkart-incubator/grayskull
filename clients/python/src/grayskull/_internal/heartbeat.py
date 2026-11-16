"""Periodic report of the secrets (and versions) this process holds: ``POST /v1/heartbeat``.

Schedule: one interval after start, then ``interval ± interval/24`` after a successful
round; after a failed round, capped exponential backoff (5m doubling to 1h, ±10%,
never above the interval). Jitter spreads a fleet's heartbeats across the window.
"""

from __future__ import annotations

import logging
import random
import threading
import time
from typing import Callable, Dict, List, Optional

from grayskull._internal.poller import chunked
from grayskull.errors import GrayskullError
from grayskull.metrics import MetricsRecorder
from grayskull.observability import request_scope
from grayskull.transport import HttpTransport

HEARTBEAT_ROUTE = "/v1/heartbeat"
BACKOFF_BASE = 5 * 60.0
BACKOFF_CAP = 60 * 60.0

HeldSecret = Dict[str, object]  # {"projectId", "secretName", "version", "refreshHook"}


def jitter(value: float, spread: float, rng: Callable[[], float] = random.random) -> float:
    return max(0.001, value + (2 * rng() - 1) * spread)


def next_delay(interval: float, failures: int, rng: Callable[[], float] = random.random) -> float:
    if failures == 0:
        return jitter(interval, interval / 24, rng)
    backoff = min(BACKOFF_BASE * (2 ** min(failures - 1, 20)), BACKOFF_CAP, interval)
    return jitter(backoff, backoff / 10, rng)


class Heartbeat:
    def __init__(
        self,
        transport: HttpTransport,
        interval: float,
        held_secrets: Callable[[], List[HeldSecret]],
        metrics: MetricsRecorder,
        logger: Optional[logging.Logger] = None,
    ) -> None:
        self._transport = transport
        self._interval = interval
        self._held_secrets = held_secrets
        self._metrics = metrics
        self._log = logger or logging.getLogger("grayskull.heartbeat")
        self._stop = threading.Event()
        self._thread = threading.Thread(target=self._run, name="grayskull-heartbeat", daemon=True)

    def start(self) -> None:
        self._thread.start()

    def close(self, timeout: float = 5.0) -> None:
        self._stop.set()
        if self._thread.is_alive() and self._thread is not threading.current_thread():
            self._thread.join(timeout)

    def _run(self) -> None:
        failures = 0
        while not self._stop.wait(next_delay(self._interval, failures)):
            try:
                ok = self.beat()
            except Exception:
                self._log.exception("unexpected error in heartbeat; continuing")
                ok = False
            failures = 0 if ok else failures + 1

    def beat(self) -> bool:
        """Send every held secret. True when all chunks succeeded (or nothing is held)."""
        held = self._held_secrets()
        ok = True
        for chunk in chunked(held):
            ok = self._send(list(chunk)) and ok
        return ok

    def _send(self, chunk: List[HeldSecret]) -> bool:
        start = time.perf_counter()
        status = 500
        with request_scope(inherit=False):
            try:
                status = self._transport.request(
                    "POST", HEARTBEAT_ROUTE, route=HEARTBEAT_ROUTE, json_body={"secrets": chunk}, cancel=self._stop
                ).status_code
                return True
            except GrayskullError as exc:
                status = exc.status_code or 500
                if not self._stop.is_set():
                    self._log.warning("heartbeat failed for %d secret(s), backing off: %s", len(chunk), exc)
                return False
            finally:
                self._metrics.record_request("heartbeat", status, time.perf_counter() - start)
