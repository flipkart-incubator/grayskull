import threading
import time
from typing import List

from grayskull import GrayskullException, NoopMetricsRecorder, SecretValue
from grayskull.client_impl.hook_refresh_poller import HookRefreshPoller
from grayskull.client_impl.hooks.secret_state import SecretState
from grayskull.client_impl.usage_ledger import UsageLedger


class RaisingHttpClient:
    """Fails every batch call with the given exception."""

    def __init__(self, error: Exception) -> None:
        self.error = error
        self.calls = 0

    def do_post_with_retry(self, url, body, request_id):
        self.calls += 1
        raise self.error


def new_poller(http_client, interval_seconds: float = 3600) -> HookRefreshPoller:
    return HookRefreshPoller(http_client, "http://gs", interval_seconds, UsageLedger(), NoopMetricsRecorder())


def test_start_twice_and_close_before_start_are_safe():
    never_started = new_poller(RaisingHttpClient(RuntimeError()))
    never_started.close()
    poller = new_poller(RaisingHttpClient(RuntimeError()))
    poller.start()
    thread = poller._scheduler
    poller.start()
    assert poller._scheduler is thread
    poller.close()


def test_batch_failure_is_logged_and_the_poll_cycle_ends():
    http_client = RaisingHttpClient(GrayskullException(503, "unavailable"))
    poller = new_poller(http_client)
    poller.register("p", "s", lambda v: None, 0)
    poller.poll_once()
    assert http_client.calls == 1
    poller.close()


def test_unexpected_error_does_not_stop_the_poll_loop():
    http_client = RaisingHttpClient(RuntimeError("bug"))
    poller = new_poller(http_client, interval_seconds=0.02)
    poller.register("p", "s", lambda v: None, 0)
    poller.start()
    deadline = time.monotonic() + 5
    while http_client.calls < 3 and time.monotonic() < deadline:
        time.sleep(0.01)
    assert http_client.calls >= 3, "the loop kept polling after errors"
    poller.close()


def test_unregistering_an_unknown_hook_is_a_no_op():
    poller = new_poller(RaisingHttpClient(RuntimeError()))
    poller._unregister("p:missing", lambda v: None)
    poller.register("p", "s", lambda v: None, 0)
    poller._unregister("p:s", lambda v: None)  # a different hook object
    assert poller._registry["p:s"].has_hooks()
    poller.close()


def test_update_for_a_secret_without_hooks_is_ignored():
    poller = new_poller(RaisingHttpClient(RuntimeError()))
    poller._handle_updated_secret("p", "unknown", SecretValue(1, "v"))
    poller.close()


def test_dispatch_after_close_or_dispatcher_shutdown_is_dropped():
    state = SecretState("p", "s", 0)
    stopped = new_poller(RaisingHttpClient(RuntimeError()))
    stopped.close()
    stopped._dispatch(state)
    shut_down = new_poller(RaisingHttpClient(RuntimeError()))
    shut_down._dispatcher.shutdown()
    shut_down._dispatch(state)  # RuntimeError from the executor is swallowed
    shut_down.close()


def test_a_secret_already_being_delivered_is_not_delivered_twice():
    poller = new_poller(RaisingHttpClient(RuntimeError()))
    delivered: List[int] = []
    poller.register("p", "s", lambda v: delivered.append(v.data_version), 0)
    state = poller._registry["p:s"]
    state.set_pending_update(SecretValue(2, "v2"))
    assert state.try_start_execution()
    poller._run_hooks_for(state)
    assert delivered == [] and state.has_pending_update()
    poller.close()


def test_update_staged_after_the_drain_is_dispatched_again():
    poller = new_poller(RaisingHttpClient(RuntimeError()))
    poller.register("p", "s", lambda v: None, 0)
    state = poller._registry["p:s"]
    dispatched: List[SecretState] = []
    poller._dispatch = dispatched.append  # type: ignore[method-assign]
    state.take_pending_update = lambda: None  # type: ignore[method-assign]
    state.set_pending_update(SecretValue(3, "v3"))  # arrives after the drain loop found nothing
    poller._run_hooks_for(state)
    assert dispatched == [state]
    poller.close()


def test_concurrent_execution_claim_is_exclusive():
    state = SecretState("p", "s", 0)
    claims = []
    barrier = threading.Barrier(8)

    def claim() -> None:
        barrier.wait()
        claims.append(state.try_start_execution())

    threads = [threading.Thread(target=claim) for _ in range(8)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert claims.count(True) == 1
