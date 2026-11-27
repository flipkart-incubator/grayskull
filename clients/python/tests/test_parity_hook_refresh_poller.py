"""Port of Java HookRefreshPollerTest and HookRefreshPollerConcurrencyTest.

The poller is driven directly with a stub HTTP client so each test controls exactly what the batch
endpoint returns. Not ported: close_whenInterrupted_propagatesInterruptAndShutsDown (Python threads
have no interrupt flag).
"""

import json
import threading
import time
from typing import List

import pytest
from parity_helpers import RecordingMetrics, batch_body, wait_until

from grayskull.client_api.models.secret_value import SecretValue
from grayskull.client_impl.hook_refresh_poller import HookRefreshPoller
from grayskull.client_impl.models.response.http_response import HttpResponse
from grayskull.client_impl.usage_ledger import UsageLedger


class StubHttp:
    """Answers each batch POST with the next queued body (or an empty batch) and records the requests."""

    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.bodies: List[dict] = []
        self.replies: List[dict] = []

    def do_post_with_retry(self, url, body, request_id):
        with self.lock:
            self.bodies.append(json.loads(body))
            reply = self.replies.pop(0) if self.replies else batch_body()
        return HttpResponse(200, json.dumps(reply).encode())


@pytest.fixture
def http():
    return StubHttp()


@pytest.fixture
def ledger():
    return UsageLedger()


@pytest.fixture
def poller(http, ledger):
    p = HookRefreshPoller(http, "http://grayskull", 3600, ledger, RecordingMetrics())
    yield p
    p.close(2)


def deliver(poller, project, name, version, public="pub"):
    poller._handle_updated_secret(project, name, SecretValue(version, public))


# ---- HookRefreshPollerTest ----

def test_close_with_a_still_running_hook_warns(http, ledger, caplog):
    p = HookRefreshPoller(http, "http://grayskull", 3600, ledger, RecordingMetrics())
    release = threading.Event()
    p.register("p", "s", lambda value: release.wait(5), 0)
    http.replies.append(batch_body(("p", "s", 1, "x")))
    p.poll_once()
    time.sleep(0.1)
    with caplog.at_level("WARNING"):
        p.close(0.2)
    release.set()
    assert any("did not stop" in r.getMessage() for r in caplog.records)


def test_poll_once_empty_registry_makes_no_http_call(poller, http):
    poller.poll_once()
    assert http.bodies == []


def test_poll_once_after_all_hooks_unregistered_makes_no_http_call(poller, http):
    poller.register("p", "s", lambda value: None, 0).unregister()
    poller.poll_once()
    assert http.bodies == []


def test_second_update_arriving_while_executing_is_delivered(poller):
    seen, entered, release = [], threading.Event(), threading.Event()

    def hook(value):
        seen.append(value.data_version)
        if value.data_version == 1:
            entered.set()
            release.wait(5)

    poller.register("p", "s", hook, 0)
    deliver(poller, "p", "s", 1)
    assert entered.wait(5)
    deliver(poller, "p", "s", 2)
    release.set()
    wait_until(lambda: seen == [1, 2], message="both updates")


def test_hook_added_mid_delivery_does_not_mark_version_applied(poller, ledger):
    entered, release = threading.Event(), threading.Event()

    def slow(value):
        entered.set()
        release.wait(5)

    poller.register("p", "s", slow, 0)
    deliver(poller, "p", "s", 3)
    assert entered.wait(5)
    poller.register("p", "s", lambda value: None, 0)  # added while version 3 is being delivered
    release.set()
    time.sleep(0.2)
    entry = ledger.snapshot()[0]
    assert (entry.version, entry.refresh_hook_applied_version) == (3, 0)


def test_follow_up_is_scheduled_when_an_update_is_pending(poller):
    seen = []
    poller.register("p", "s", lambda value: seen.append(value.data_version), 0)
    state = poller._registry["p:s"]
    state.set_pending_update(SecretValue(4, "x"))
    poller._dispatch(state)
    wait_until(lambda: seen == [4])


def test_delivery_with_no_hooks_left_records_nothing_as_applied(poller, ledger):
    poller.register("p", "s", lambda value: None, 0)
    state = poller._registry["p:s"]
    state.remove_hook(state.snapshot_hooks()[0])
    poller._deliver_to_hooks(state, SecretValue(5, "x"))
    assert all(e.refresh_hook_applied_version in (None, 0) for e in ledger.snapshot())


# ---- HookRefreshPollerConcurrencyTest ----

def test_parallel_register_same_secret_all_hooks_retained(poller):
    barrier = threading.Barrier(16)

    def register():
        barrier.wait()
        poller.register("p", "s", lambda value: None, 0)

    threads = [threading.Thread(target=register) for _ in range(16)]
    for t in threads:
        t.start()
    for t in threads:
        t.join(5)
    assert len(poller._registry["p:s"].snapshot_hooks()) == 16


def test_parallel_register_unregister_final_state_consistent(poller, ledger):
    keep = [poller.register("p", "s", lambda value: None, 0) for _ in range(10)]
    drop = [poller.register("p", "s", lambda value: None, 0) for _ in range(10)]
    threads = [threading.Thread(target=h.unregister) for h in drop]
    for t in threads:
        t.start()
    for t in threads:
        t.join(5)
    assert len(poller._registry["p:s"].snapshot_hooks()) == len(keep)
    assert ledger._entries[("p", "s")].hook_registered


def test_latest_wins_coalescing_newer_value_delivered_after_slow_hook(poller):
    seen, entered, release = [], threading.Event(), threading.Event()

    def hook(value):
        seen.append(value.data_version)
        if value.data_version == 1:
            entered.set()
            release.wait(5)

    poller.register("p", "s", hook, 0)
    deliver(poller, "p", "s", 1)
    assert entered.wait(5)
    for version in (2, 3, 4):
        deliver(poller, "p", "s", version)
    release.set()
    wait_until(lambda: seen and seen[-1] == 4, message="latest version")
    assert seen == [1, 4]  # 2 and 3 were superseded while the hook was busy


def test_wake_up_race_update_between_drain_and_release_is_delivered(poller):
    seen = []
    poller.register("p", "s", lambda value: seen.append(value.data_version), 0)
    state = poller._registry["p:s"]
    original_finish = state.finish_execution

    def finish_with_late_update():
        if not seen or seen[-1] == 1:
            state.set_pending_update(SecretValue(2, "late"))  # lands after the drain loop, before release
        original_finish()

    state.finish_execution = finish_with_late_update
    deliver(poller, "p", "s", 1)
    wait_until(lambda: 2 in seen, message="late update")


def test_per_secret_dispatch_is_non_reentrant(poller):
    active, overlap, calls = [0], [False], []
    lock = threading.Lock()

    def hook(value):
        with lock:
            active[0] += 1
            overlap[0] |= active[0] > 1
        time.sleep(0.01)
        calls.append(value.data_version)
        with lock:
            active[0] -= 1

    poller.register("p", "s", hook, 0)
    for version in range(1, 30):
        deliver(poller, "p", "s", version)
    wait_until(lambda: calls and calls[-1] == 29, message="last version")
    assert not overlap[0]


def test_unregister_during_dispatch_does_not_throw(poller):
    entered, release, errors = threading.Event(), threading.Event(), []

    def hook(value):
        entered.set()
        release.wait(5)

    handle = poller.register("p", "s", hook, 0)
    deliver(poller, "p", "s", 1)
    assert entered.wait(5)
    try:
        handle.unregister()
    except Exception as exc:  # pragma: no cover - reported below
        errors.append(exc)
    release.set()
    assert errors == []


def test_unregister_after_state_already_removed_is_noop(poller):
    handle = poller.register("p", "s", lambda value: None, 0)
    del poller._registry["p:s"]
    poller._unregister("p:s", lambda value: None)
    handle.unregister()


def test_unregister_hook_not_registered_on_existing_state_is_noop(poller, ledger):
    poller.register("p", "s", lambda value: None, 0)
    generation = ledger.hook_generation("p", "s")
    poller._unregister("p:s", lambda value: None)  # a different function object
    assert len(poller._registry["p:s"].snapshot_hooks()) == 1
    assert ledger.hook_generation("p", "s") == generation


def test_update_for_unknown_secret_ref_is_ignored(poller):
    deliver(poller, "p", "unknown", 3)
    assert "p:unknown" not in poller._registry


def test_late_update_after_drain_is_resubmitted(poller):
    seen = []
    poller.register("p", "s", lambda value: seen.append(value.data_version), 0)
    state = poller._registry["p:s"]
    assert state.try_start_execution()  # simulate another worker owning the secret
    deliver(poller, "p", "s", 7)  # this dispatch backs off
    state.finish_execution()
    poller._dispatch(state)  # the owner's follow-up after release
    wait_until(lambda: seen == [7])


def test_close_forces_shutdown_when_dispatcher_tasks_refuse_to_finish(http, ledger):
    p = HookRefreshPoller(http, "http://grayskull", 3600, ledger, RecordingMetrics())
    release = threading.Event()
    p.register("p", "s", lambda value: release.wait(10), 0)
    deliver(p, "p", "s", 1)
    time.sleep(0.05)
    start = time.monotonic()
    p.close(0.2)
    assert time.monotonic() - start < 1.0
    release.set()


def test_concurrent_register_and_unregister_keeps_the_ledger_hook_flag_consistent(poller, ledger):
    def churn():
        for _ in range(50):
            poller.register("p", "s", lambda value: None, 0).unregister()

    threads = [threading.Thread(target=churn) for _ in range(8)]
    for t in threads:
        t.start()
    for t in threads:
        t.join(10)
    assert "p:s" not in poller._registry
    assert ledger._entries[("p", "s")].hook_registered is False
