"""Default GrayskullClient: secret reads, refresh hooks and the client heartbeat."""

import os
import threading
import time
import uuid
import weakref
from typing import Optional, Tuple
from urllib.parse import quote

from ..client_api.constants.grayskull_headers import GrayskullHeaders
from ..client_api.grayskull_client import GrayskullClient
from ..client_api.hooks.refresh_handler_ref import RefreshHandlerRef
from ..client_api.hooks.secret_refresh_hook import SecretRefreshHook
from ..client_api.models.secret_value import SecretValue
from ..version import __version__
from .auth.grayskull_auth_header_provider import GrayskullAuthHeaderProvider
from .grayskull_http_client import GrayskullHttpClient
from .heartbeat_scheduler import HeartbeatScheduler
from .hook_refresh_poller import HookRefreshPoller
from .metrics.metrics_recorder import MetricsRecorder, NoopMetricsRecorder
from .models.exceptions.grayskull_exception import GrayskullException
from .metrics.prometheus_metrics_recorder import PrometheusMetricsRecorder, prometheus_available
from .models.grayskull_client_configuration import GrayskullClientConfiguration
from .request_context import get_logger, request_id_scope
from .usage_ledger import UsageLedger

_log = get_logger(__name__)


def _default_metrics_recorder() -> MetricsRecorder:
    if prometheus_available():
        return PrometheusMetricsRecorder()
    _log.info("Metrics are enabled but prometheus_client is not installed; metrics are not recorded. "
              "Install grayskull-client[prometheus] or pass a MetricsRecorder.")
    return NoopMetricsRecorder()


def _after_fork_in_child(client_ref: "weakref.ref[GrayskullClientImpl]") -> None:
    client = client_ref()
    if client is not None:
        client._after_fork_in_child()


class GrayskullClientImpl(GrayskullClient):
    """Thread-safe Grayskull client; close() it, or use it as a context manager."""

    def __init__(
        self,
        auth_header_provider: GrayskullAuthHeaderProvider,
        configuration: GrayskullClientConfiguration,
        metrics_recorder: Optional[MetricsRecorder] = None,
    ) -> None:
        if auth_header_provider is None:
            raise ValueError("authHeaderProvider cannot be null")
        if configuration is None:
            raise ValueError("grayskullClientConfiguration cannot be null")
        configuration.validate()
        configuration.add_default_header(GrayskullHeaders.WORKLOAD, configuration.workload_identity_resolver.resolve())
        configuration.add_default_header(GrayskullHeaders.USER_AGENT, f"grayskull-python/{__version__}")
        self._base_url = configuration.host
        if not configuration.metrics_enabled:
            self._metrics_recorder: MetricsRecorder = NoopMetricsRecorder()
        else:
            self._metrics_recorder = metrics_recorder or _default_metrics_recorder()
        self._http_client = GrayskullHttpClient(auth_header_provider, configuration, self._metrics_recorder)
        self._usage_ledger = UsageLedger()
        self._closed = False
        self._close_lock = threading.Lock()

        self._refresh_poller = HookRefreshPoller(
            self._http_client, self._base_url, configuration.polling_interval_seconds,
            self._usage_ledger, self._metrics_recorder)
        self._refresh_poller.start()

        self._heartbeat_scheduler: Optional[HeartbeatScheduler] = None
        if configuration.heartbeat_enabled:
            self._heartbeat_scheduler = HeartbeatScheduler(
                self._http_client, self._base_url, configuration.heartbeat_interval_seconds,
                self._usage_ledger.snapshot, self._metrics_recorder)
            self._heartbeat_scheduler.start()

        # Threads do not survive os.fork(): restart them in the child (gunicorn/celery prefork workers).
        if hasattr(os, "register_at_fork"):
            client_ref = weakref.ref(self)
            os.register_at_fork(after_in_child=lambda: _after_fork_in_child(client_ref))

    def get_secret(self, secret_ref: str) -> SecretValue:
        """Return the current value of the secret referenced as 'projectId:secretName'."""
        project_id, secret_name = _parse_secret_ref(secret_ref)
        url = f"{self._base_url}/v1/projects/{quote(project_id, safe='')}/secrets/{quote(secret_name, safe='')}/data"
        start = time.monotonic()
        status_code = 500
        request_id = str(uuid.uuid4())
        try:
            with request_id_scope(request_id):
                http_response = self._http_client.do_get_with_retry(url, request_id)
            status_code = http_response.status_code
            data = http_response.json().get("data")
            if not isinstance(data, dict):
                raise GrayskullException(500, "No data in response")
            try:
                value = SecretValue.from_dict(data)
            except (KeyError, TypeError, ValueError) as exc:
                raise GrayskullException(500, "Failed to parse response", exc) from exc
            self._usage_ledger.record_fetch(project_id, secret_name, value.data_version)
            return value
        except GrayskullException as exc:
            status_code = exc.status_code
            raise
        finally:
            self._metrics_recorder.record_request(
                f"getSecret.{secret_ref}", status_code, int((time.monotonic() - start) * 1000))

    def register_refresh_hook(self, secret_ref: str, hook: SecretRefreshHook) -> RefreshHandlerRef:
        """Invoke hook with each newer version of secret_ref until the returned handle is unregistered."""
        if self._closed:
            raise RuntimeError("GrayskullClient has been closed; cannot register new refresh hooks")
        if hook is None or not callable(hook):
            raise ValueError("hook cannot be null")
        project_id, secret_name = _parse_secret_ref(secret_ref)
        seed = self._usage_ledger.version(project_id, secret_name)
        return self._refresh_poller.register(project_id, secret_name, hook, seed)

    def close(self) -> None:
        """Stop the heartbeat, then the poller and running hooks (up to 10s each), then close connections.

        Safe to call more than once.
        """
        with self._close_lock:
            if self._closed:
                return
            self._closed = True
        _log.info("Closing Grayskull client")
        if self._heartbeat_scheduler is not None:
            self._heartbeat_scheduler.close()
        self._refresh_poller.close()
        self._http_client.close()

    def _after_fork_in_child(self) -> None:
        """Runs in a forked child: drop the parent's sockets and restart the background threads."""
        self._close_lock = threading.Lock()
        if self._closed:
            return
        self._http_client.reset_after_fork()
        self._usage_ledger.reset_after_fork()
        self._refresh_poller.restart_after_fork()
        if self._heartbeat_scheduler is not None:
            self._heartbeat_scheduler.restart_after_fork()
        _log.info("Restarted Grayskull client threads in forked process %d", os.getpid())


def _parse_secret_ref(secret_ref: str) -> Tuple[str, str]:
    if not secret_ref:
        raise ValueError("secretRef cannot be null or empty")
    project_id, separator, secret_name = secret_ref.partition(":")
    if not separator or not project_id or not secret_name:
        raise ValueError(f"Invalid secretRef format. Expected 'projectId:secretName', got: {secret_ref}")
    return project_id, secret_name
