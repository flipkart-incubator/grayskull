"""The Grayskull read client (the Python counterpart of ``client-impl``)."""

from __future__ import annotations

import logging
import os
import threading
import time
import weakref
from types import TracebackType
from typing import Dict, List, Optional, Type
from urllib.parse import quote

import httpx

from grayskull import headers as H
from grayskull._internal.heartbeat import Heartbeat, HeldSecret
from grayskull._internal.hooks import Registry
from grayskull._internal.poller import Poller
from grayskull._version import __version__
from grayskull.api import AuthHeaderProvider, RefreshHandlerRef, SecretRef, SecretRefreshHook, SecretValue
from grayskull.config import GrayskullClientConfig
from grayskull.errors import GrayskullError, InvalidArgumentError
from grayskull.metrics import MetricsRecorder, default_metrics_recorder
from grayskull.observability import request_scope, secret_scope
from grayskull.transport import HttpTransport
from grayskull.workload import HostnameWorkloadIdentityResolver

__all__ = ["GrayskullClientImpl", "USER_AGENT"]

USER_AGENT = f"grayskull-python/{__version__}"
GET_SECRET_ROUTE = "/v1/projects/{projectId}/secrets/{secretName}/data"

_log = logging.getLogger("grayskull.client")


def _segment(value: str) -> str:
    return quote(value, safe="")


class GrayskullClientImpl:
    """Thread-safe Grayskull client. Create one per process and share it.

    Background threads are started lazily: the heartbeat after the first successful
    :meth:`get_secret`, the poller on the first :meth:`register_refresh_hook`. Both
    are daemon threads, and both are transparently restarted in a forked child
    (gunicorn/uWSGI pre-fork), whose inherited connection pool is discarded.
    """

    def __init__(
        self,
        auth_provider: AuthHeaderProvider,
        config: GrayskullClientConfig,
        *,
        metrics_recorder: Optional[MetricsRecorder] = None,
        http_transport: Optional[httpx.BaseTransport] = None,
    ) -> None:
        if auth_provider is None or not callable(getattr(auth_provider, "get_auth_header", None)):
            raise InvalidArgumentError("auth_provider must define get_auth_header() -> str")
        if not isinstance(config, GrayskullClientConfig):
            raise InvalidArgumentError("config must be a GrayskullClientConfig")

        resolver = config.workload_identity_resolver or HostnameWorkloadIdentityResolver()
        headers = dict(config.default_headers)
        headers[H.WORKLOAD] = resolver.resolve()  # pinned once; SDK headers beat custom ones
        headers[H.USER_AGENT] = USER_AGENT

        self._auth_provider = auth_provider
        self._config = config
        self._headers = headers
        self._http_transport = http_transport
        self._metrics = metrics_recorder or default_metrics_recorder(config.metrics_enabled)
        self._registry = Registry()
        self._last_seen: Dict[str, int] = {}
        self._lock = threading.Lock()
        self._closed = False
        self._poller: Optional[Poller] = None
        self._heartbeat: Optional[Heartbeat] = None
        self._transport = self._new_transport()
        _live_clients.add(self)

    # -- public API ----------------------------------------------------------

    @property
    def config(self) -> GrayskullClientConfig:
        return self._config

    def get_secret(self, secret_ref: str) -> SecretValue:
        start = time.perf_counter()
        try:
            ref = SecretRef.parse(secret_ref)
        except InvalidArgumentError:
            self._metrics.record_request("get_secret", 400, time.perf_counter() - start)
            raise
        self._ensure_open()

        path = f"/v1/projects/{_segment(ref.project_id)}/secrets/{_segment(ref.secret_name)}/data"
        with request_scope(), secret_scope(ref.project_id, ref.secret_name):
            _log.debug("fetching secret %s", ref)
            try:
                response = self._transport.request("GET", path, route=GET_SECRET_ROUTE)
            except GrayskullError as exc:
                self._metrics.record_request("get_secret", exc.status_code, time.perf_counter() - start)
                raise GrayskullError("failed to fetch secret", exc.status_code, exc) from exc

        data = response.body.get("data") if isinstance(response.body, dict) else None
        if not data:
            self._metrics.record_request("get_secret", 500, time.perf_counter() - start)
            raise GrayskullError("no data in response", 500)
        value = SecretValue.from_wire(data)
        self._metrics.record_request("get_secret", response.status_code, time.perf_counter() - start)

        # Seeds later hook registrations (so already-seen versions are not redelivered)
        # and feeds the heartbeat.
        with self._lock:
            self._last_seen[secret_ref] = value.data_version
            self._ensure_heartbeat_locked()
        return value

    def register_refresh_hook(self, secret_ref: str, hook: SecretRefreshHook) -> RefreshHandlerRef:
        ref = SecretRef.parse(secret_ref)
        if not callable(hook):
            raise InvalidArgumentError("hook must be callable")
        with self._lock:
            if self._closed:
                raise GrayskullError("client has been closed; cannot register new refresh hooks", 400)
            handle = self._registry.register(ref.project_id, ref.secret_name, hook, self._last_seen.get(str(ref), 0))
            self._ensure_poller_locked()
        return handle

    def close(self) -> None:
        with self._lock:
            if self._closed:
                return
            self._closed = True
            heartbeat, poller = self._heartbeat, self._poller
            self._heartbeat = self._poller = None
        _live_clients.discard(self)
        if heartbeat is not None:
            heartbeat.close()
        if poller is not None:
            poller.close()
        self._transport.close()

    def __enter__(self) -> "GrayskullClientImpl":
        return self

    def __exit__(
        self,
        exc_type: Optional[Type[BaseException]],
        exc: Optional[BaseException],
        tb: Optional[TracebackType],
    ) -> None:
        self.close()

    def __repr__(self) -> str:
        return f"GrayskullClientImpl(host={self._config.host!r}, closed={self._closed})"

    # -- internals -----------------------------------------------------------

    def _ensure_open(self) -> None:
        if self._closed:
            raise GrayskullError("client has been closed")

    def _new_transport(self) -> HttpTransport:
        return HttpTransport(
            self._config,
            self._auth_provider.get_auth_header,
            headers=self._headers,
            metrics=self._metrics,
            transport=self._http_transport,
        )

    def _ensure_poller_locked(self) -> None:
        if self._poller is None and not self._closed:
            self._poller = Poller(self._transport, self._registry, self._config.polling_interval, self._metrics)
            self._poller.start()

    def _ensure_heartbeat_locked(self) -> None:
        if self._heartbeat is None and self._config.heartbeat_enabled and not self._closed:
            self._heartbeat = Heartbeat(
                self._transport, self._config.heartbeat_interval, self._held_secrets, self._metrics
            )
            self._heartbeat.start()

    def _held_secrets(self) -> List[HeldSecret]:
        """Every fetched secret with the newest version held (fetched or delivered to a hook)."""
        with self._lock:
            seen = list(self._last_seen.items())
        held: List[HeldSecret] = []
        for secret_ref, version in seen:
            ref = SecretRef.parse(secret_ref)
            state = self._registry.get(secret_ref)
            if state is not None:
                version = max(version, state.last_known_version)
            held.append(
                {
                    "projectId": ref.project_id,
                    "secretName": ref.secret_name,
                    "version": version,
                    "refreshHook": state is not None,
                }
            )
        return held

    def _after_fork_in_child(self) -> None:
        # Locks may have been held by threads that no longer exist, and the pool's
        # sockets are shared with the parent: closing them would send TLS close_notify
        # on the parent's connections, so the old pool is abandoned, not closed.
        self._lock = threading.Lock()
        self._registry.reset_after_fork()
        self._transport.abandon()
        if self._closed:
            return
        had_poller, had_heartbeat = self._poller is not None, self._heartbeat is not None
        self._poller = self._heartbeat = None
        self._transport = self._new_transport()
        if had_poller:
            self._ensure_poller_locked()
        if had_heartbeat:
            self._ensure_heartbeat_locked()


_live_clients: "weakref.WeakSet[GrayskullClientImpl]" = weakref.WeakSet()


def _reinit_clients_after_fork() -> None:
    for client in list(_live_clients):
        try:
            client._after_fork_in_child()
        except Exception:  # never break the child process over a background feature
            _log.exception("failed to reinitialise Grayskull client after fork")


if hasattr(os, "register_at_fork"):  # POSIX only
    os.register_at_fork(after_in_child=_reinit_clients_after_fork)
