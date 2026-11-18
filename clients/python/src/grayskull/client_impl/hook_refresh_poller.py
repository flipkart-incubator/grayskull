"""Owns the refresh-hook registry and polls POST /v1/secrets/batch to deliver newer versions."""

import json
import logging
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from typing import Dict, List, Optional

from ..client_api.hooks.refresh_handler_ref import RefreshHandlerRef
from ..client_api.hooks.secret_refresh_hook import SecretRefreshHook
from ..client_api.models.secret_value import SecretValue
from .grayskull_http_client import GrayskullHttpClient
from .hooks.default_refresh_handler_ref import DefaultRefreshHandlerRef
from .hooks.secret_state import SecretState
from .metrics.metrics_recorder import MetricsRecorder
from .models.exceptions.grayskull_exception import GrayskullException
from .models.request.batch_get_secrets_request import BatchGetSecretsEntry, BatchGetSecretsRequest
from .models.response.batch_get_secrets_response import BatchGetSecretsResponse
from .usage_ledger import UsageLedger

MAX_BATCH_SECRETS = 50
DISPATCHER_THREADS = 5
_log = logging.getLogger(__name__)


class HookRefreshPoller:
    """Polls on an interval and runs hooks, at most one delivery at a time per secret."""

    def __init__(
        self,
        http_client: GrayskullHttpClient,
        base_url: str,
        interval_seconds: float,
        usage_ledger: UsageLedger,
        metrics_recorder: MetricsRecorder,
    ) -> None:
        self._http_client = http_client
        self._batch_url = f"{base_url}/v1/secrets/batch"
        self._interval_seconds = interval_seconds
        self._usage_ledger = usage_ledger
        self._metrics_recorder = metrics_recorder
        self._registry: Dict[str, SecretState] = {}
        self._registry_lock = threading.Lock()
        self._stopped = threading.Event()
        self._scheduler: Optional[threading.Thread] = None
        self._dispatcher = ThreadPoolExecutor(max_workers=DISPATCHER_THREADS, thread_name_prefix="grayskull-hook-dispatcher")

    def start(self) -> None:
        """Start polling; calling it again has no effect."""
        if self._scheduler is None:
            self._scheduler = threading.Thread(target=self._run, name="grayskull-poller", daemon=True)
            self._scheduler.start()

    def register(
        self, project_id: str, secret_name: str, hook: SecretRefreshHook, initial_known_version: int
    ) -> RefreshHandlerRef:
        """Attach hook; initial_known_version seeds a secret's state only on its first hook."""
        secret_ref = f"{project_id}:{secret_name}"
        with self._registry_lock:
            state = self._registry.get(secret_ref)
            if state is None:
                state = SecretState(project_id, secret_name, initial_known_version)
                self._registry[secret_ref] = state
            state.add_hook(hook)
            self._usage_ledger.record_hook_added(project_id, secret_name)
        return DefaultRefreshHandlerRef(secret_ref, lambda: self._unregister(secret_ref, hook))

    def poll_once(self) -> None:
        """Run one poll cycle over every secret with a hook."""
        with self._registry_lock:
            states = list(self._registry.values())
        if not states:
            return
        entries = [BatchGetSecretsEntry(s.project_id, s.secret_name, s.last_known_version) for s in states]
        for start in range(0, len(entries), MAX_BATCH_SECRETS):
            self._poll_chunk(entries[start:start + MAX_BATCH_SECRETS])

    def close(self, timeout_seconds: float = 10.0) -> None:
        """Stop polling and dispatching; waits up to timeout_seconds for the poller thread."""
        self._stopped.set()
        self._dispatcher.shutdown(wait=False, cancel_futures=True)
        if self._scheduler is not None and self._scheduler is not threading.current_thread():
            self._scheduler.join(timeout_seconds)

    def _unregister(self, secret_ref: str, hook: SecretRefreshHook) -> None:
        with self._registry_lock:
            state = self._registry.get(secret_ref)
            if state is None or not state.remove_hook(hook):
                return
            if not state.has_hooks():
                del self._registry[secret_ref]
            self._usage_ledger.set_hook_registered(state.project_id, state.secret_name, state.has_hooks())

    def _run(self) -> None:
        while not self._stopped.wait(self._interval_seconds):
            try:
                self.poll_once()
            except Exception:  # keep polling; the next cycle retries
                _log.exception("Poll cycle failed")

    def _poll_chunk(self, chunk: List[BatchGetSecretsEntry]) -> None:
        body = json.dumps(BatchGetSecretsRequest(chunk).to_dict()).encode("utf-8")
        start = time.monotonic()
        status_code = 500
        try:
            http_response = self._http_client.do_post_with_retry(self._batch_url, body, str(uuid.uuid4()))
            status_code = http_response.status_code
            response = BatchGetSecretsResponse.from_response(http_response.json())
        except GrayskullException as exc:
            status_code = exc.status_code or status_code
            _log.warning("Batch refresh failed for %d secret(s) (status %s): %s", len(chunk), exc.status_code, exc)
            return
        finally:
            self._metrics_recorder.record_request("batchGetSecrets", status_code, int((time.monotonic() - start) * 1000))
        for updated in response.updated_secrets:
            self._handle_updated_secret(updated.project_id, updated.secret_name, updated.value)

    def _handle_updated_secret(self, project_id: str, secret_name: str, value: SecretValue) -> None:
        with self._registry_lock:
            state = self._registry.get(f"{project_id}:{secret_name}")
        if state is None:
            return
        state.set_pending_update(value)
        self._dispatch(state)

    def _dispatch(self, state: SecretState) -> None:
        if self._stopped.is_set():
            return
        try:
            self._dispatcher.submit(self._run_hooks_for, state)
        except RuntimeError:  # dispatcher already shut down
            pass

    def _run_hooks_for(self, state: SecretState) -> None:
        if not state.try_start_execution():
            return  # another worker owns this secret and will pick up the staged update
        try:
            while True:
                value = state.take_pending_update()
                if value is None:
                    break
                state.last_known_version = value.data_version  # advanced before hooks run: at-most-once
                self._usage_ledger.record_version(state.project_id, state.secret_name, value.data_version)
                self._deliver_to_hooks(state, value)
        finally:
            state.finish_execution()
        if state.has_pending_update():
            self._dispatch(state)

    def _deliver_to_hooks(self, state: SecretState, value: SecretValue) -> None:
        secret_ref = f"{state.project_id}:{state.secret_name}"
        hook_generation = self._usage_ledger.hook_generation(state.project_id, state.secret_name)  # before reading hooks
        hooks = state.snapshot_hooks()
        all_succeeded = bool(hooks)
        for hook in hooks:
            start = time.monotonic()
            succeeded = True
            try:
                hook(value)
            except Exception:  # one failing hook must not block the others
                succeeded = all_succeeded = False
                _log.exception("Refresh hook failed for %s at version %s", secret_ref, value.data_version)
            finally:
                self._metrics_recorder.record_request(
                    f"hook.execute.{secret_ref}", 200 if succeeded else 500, int((time.monotonic() - start) * 1000))
        if all_succeeded:  # applied only when every registered hook succeeded for this version
            self._usage_ledger.record_hook_applied(state.project_id, state.secret_name, value.data_version, hook_generation)
