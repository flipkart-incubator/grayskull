"""Background batch-refresh loop for refresh hooks.

One fetcher thread polls ``POST /v1/secrets/batch`` every interval, in chunks of
:data:`MAX_BATCH_SIZE`, sending each watched secret's ``lastKnownVersion``. Updates
are staged on their :class:`SecretState` and drained by a small worker pool, so a
slow hook on one secret never stalls delivery for the others.
"""

from __future__ import annotations

import logging
import queue
import threading
import time
from typing import Any, Iterator, List, Optional, Sequence, Tuple, TypeVar

from grayskull._internal.hooks import Registry, SecretState
from grayskull.api import SecretRefreshHook, SecretValue
from grayskull.errors import GrayskullError
from grayskull.metrics import MetricsRecorder
from grayskull.observability import request_scope, secret_scope
from grayskull.transport import HttpTransport

MAX_BATCH_SIZE = 50
DISPATCHER_WORKERS = 5
SHUTDOWN_TIMEOUT = 10.0
BATCH_ROUTE = "/v1/secrets/batch"

T = TypeVar("T")


def chunked(items: Sequence[T], size: int = MAX_BATCH_SIZE) -> Iterator[Sequence[T]]:
    for start in range(0, len(items), size):
        yield items[start : start + size]


class Poller:
    def __init__(
        self,
        transport: HttpTransport,
        registry: Registry,
        interval: float,
        metrics: MetricsRecorder,
        logger: Optional[logging.Logger] = None,
        workers: int = DISPATCHER_WORKERS,
    ) -> None:
        self._transport = transport
        self._registry = registry
        self._interval = interval
        self._metrics = metrics
        self._log = logger or logging.getLogger("grayskull.poller")
        self._stop = threading.Event()
        self._thread = threading.Thread(target=self._run, name="grayskull-poller", daemon=True)
        # Daemon workers, not ThreadPoolExecutor: since 3.9 its workers are joined at
        # interpreter exit, so a hook stuck on I/O would keep the process alive forever.
        self._jobs: "queue.SimpleQueue[Optional[Tuple[SecretState, str]]]" = queue.SimpleQueue()
        self._workers = [
            threading.Thread(target=self._worker, name=f"grayskull-hook-{i}", daemon=True) for i in range(workers)
        ]
        self._pending_jobs = 0  # queued + running drains
        self._idle = threading.Condition()

    def start(self) -> None:
        for worker in self._workers:
            worker.start()
        self._thread.start()

    def close(self, timeout: float = SHUTDOWN_TIMEOUT) -> bool:
        """Stop polling and wait (bounded) for running hooks. True if fully drained in time."""
        self._stop.set()
        deadline = time.monotonic() + timeout
        current = threading.current_thread()
        if self._thread.is_alive() and self._thread is not current:
            self._thread.join(timeout)
        for _ in self._workers:
            self._jobs.put(None)
        if current in self._workers:  # close() called from inside a hook: don't wait on ourselves
            return False
        if self._thread.is_alive() or not self.wait_idle(max(0.0, deadline - time.monotonic())):
            self._log.warning("poller did not stop within %.1fs; abandoning running hook(s)", timeout)
            return False
        return True

    def wait_idle(self, timeout: Optional[float] = None) -> bool:
        """Block until no drain is queued or running."""
        with self._idle:
            return self._idle.wait_for(lambda: self._pending_jobs == 0, timeout)

    # -- fetch ---------------------------------------------------------------

    def _run(self) -> None:
        while not self._stop.wait(self._interval):
            try:
                self.poll_once()
            except Exception:  # never let one bad cycle kill the thread
                self._log.exception("unexpected error in poll cycle; continuing")

    def poll_once(self) -> None:
        states = self._registry.snapshot()
        for chunk in chunked(states):
            if self._stop.is_set():
                return
            self._poll_chunk(chunk)

    def _poll_chunk(self, chunk: Sequence[SecretState]) -> None:
        body = {
            "secrets": [
                {"projectId": s.project_id, "secretName": s.secret_name, "lastKnownVersion": s.last_known_version}
                for s in chunk
            ]
        }
        with request_scope(inherit=False) as request_id:
            start = time.perf_counter()
            status = 500
            try:
                response = self._transport.request("POST", BATCH_ROUTE, route=BATCH_ROUTE, json_body=body, cancel=self._stop)
                status = response.status_code
            except GrayskullError as exc:
                status = exc.status_code or 500
                if not self._stop.is_set():
                    self._log.error("batch refresh failed: %s", exc)
                return
            finally:
                self._metrics.record_request("batchGetSecrets", status, time.perf_counter() - start)

            data = (response.body or {}).get("data") or {}
            updated: List[Any] = data.get("updatedSecrets") or []
            for item in updated:
                self._stage(item, request_id)

    def _stage(self, item: Any, request_id: str) -> None:
        state = self._registry.get(f"{item.get('projectId')}:{item.get('secretName')}")
        if state is None:  # unregistered while the request was in flight
            return
        if state.stage(SecretValue.from_wire(item)):
            self._submit(state, request_id)

    # -- dispatch ------------------------------------------------------------

    def _submit(self, state: SecretState, request_id: str) -> None:
        if self._stop.is_set():
            state.release()
            return
        with self._idle:
            self._pending_jobs += 1
        self._jobs.put((state, request_id))

    def _worker(self) -> None:
        while True:
            job = self._jobs.get()
            if job is None:
                return
            try:
                self._drain(*job)
            except Exception:  # defensive: _drain already isolates hook failures
                self._log.exception("hook dispatcher failed")
            finally:
                with self._idle:
                    self._pending_jobs -= 1
                    self._idle.notify_all()

    def _drain(self, state: SecretState, request_id: str) -> None:
        with request_scope(request_id, inherit=False), secret_scope(state.project_id, state.secret_name):
            while not self._stop.is_set():
                value = state.take_next()
                if value is None:
                    return
                for hook in state.hooks():
                    self._invoke(state.secret_ref, hook, value)
            state.release()

    def _invoke(self, secret_ref: str, hook: SecretRefreshHook, value: SecretValue) -> None:
        start = time.perf_counter()
        status = 200
        try:
            hook(value)
        except Exception:
            status = 500
            self._log.exception("refresh hook for %s raised", secret_ref)
        finally:
            # Not "hook.execute.<secretRef>" as in Go/Java: that label is unbounded.
            self._metrics.record_request("hook.execute", status, time.perf_counter() - start)
