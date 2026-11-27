"""Port of Java UsageLedgerTest."""

import socket
import threading

from grayskull.client_impl.usage_ledger import UsageLedger


def _only(ledger):
    snapshot = ledger.snapshot()
    assert len(snapshot) == 1
    return snapshot[0]


def test_empty_ledger_has_empty_snapshot():
    ledger = UsageLedger()
    assert ledger.snapshot() == []
    assert ledger.version("p", "s") == 0


def test_fetched_secret_without_hook_has_no_applied_version():
    ledger = UsageLedger()
    ledger.record_fetch("p", "s", 3)
    entry = _only(ledger)
    assert (entry.project_id, entry.secret_name, entry.version) == ("p", "s", 3)
    assert entry.refresh_hook_applied_version is None


def test_version_is_the_newest_held_from_fetch_or_poller():
    ledger = UsageLedger()
    ledger.record_fetch("p", "s", 5)
    ledger.record_version("p", "s", 8)
    ledger.record_fetch("p", "s", 6)  # a stale read never lowers the held version
    assert ledger.version("p", "s") == 8
    assert _only(ledger).version == 8


def test_hook_registered_before_any_fetch_is_not_reported():
    ledger = UsageLedger()
    ledger.set_hook_registered("p", "s", True)
    assert ledger.snapshot() == []
    assert ledger.version("p", "s") == 0


def test_applied_version_is_zero_until_a_hook_succeeds():
    ledger = UsageLedger()
    ledger.record_fetch("p", "s", 3)
    ledger.set_hook_registered("p", "s", True)
    assert _only(ledger).refresh_hook_applied_version == 0
    ledger.record_hook_applied("p", "s", 3, ledger.hook_generation("p", "s"))
    assert _only(ledger).refresh_hook_applied_version == 3


def test_hook_applied_only_advances_forward():
    ledger = UsageLedger()
    ledger.record_version("p", "s", 7)
    ledger.set_hook_registered("p", "s", True)
    ledger.record_hook_applied("p", "s", 7, ledger.hook_generation("p", "s"))
    ledger.record_hook_applied("p", "s", 6, ledger.hook_generation("p", "s"))
    assert _only(ledger).refresh_hook_applied_version == 7


def test_hook_registration_toggles_presence_and_resets_applied_version():
    ledger = UsageLedger()
    ledger.record_version("p", "s", 4)
    ledger.set_hook_registered("p", "s", True)
    ledger.record_hook_applied("p", "s", 4, ledger.hook_generation("p", "s"))
    assert _only(ledger).refresh_hook_applied_version == 4
    ledger.set_hook_registered("p", "s", False)
    assert _only(ledger).refresh_hook_applied_version is None
    ledger.set_hook_registered("p", "s", True)
    assert _only(ledger).refresh_hook_applied_version == 0


def test_secrets_are_kept_apart():
    ledger = UsageLedger()
    ledger.record_fetch("p", "a", 1)
    ledger.set_hook_registered("p", "b", True)
    ledger.record_fetch("q", "a", 9)
    assert len(ledger.snapshot()) == 2
    assert ledger.version("p", "a") == 1
    assert ledger.version("p", "b") == 0
    assert ledger.version("q", "a") == 9


def test_concurrent_recording_keeps_the_newest_version():
    ledger = UsageLedger()
    threads_count, per_thread = 8, 2000
    start = threading.Event()
    errors = []

    def work():
        try:
            start.wait()
            for i in range(per_thread):
                ledger.record_fetch("p", "s", i)
                ledger.record_hook_applied("p", "s", i, ledger.hook_generation("p", "s"))
                ledger.snapshot()
        except Exception as exc:  # pragma: no cover - reported below
            errors.append(exc)

    threads = [threading.Thread(target=work) for _ in range(threads_count)]
    for t in threads:
        t.start()
    start.set()
    for t in threads:
        t.join(30)
    assert not errors
    ledger.set_hook_registered("p", "s", True)
    entry = _only(ledger)
    assert entry.version == per_thread - 1
    assert entry.refresh_hook_applied_version == per_thread - 1


def test_adding_a_hook_clears_the_applied_version_until_it_runs():
    ledger = UsageLedger()
    ledger.record_version("p", "s", 7)
    ledger.record_hook_added("p", "s")
    ledger.record_hook_applied("p", "s", 7, ledger.hook_generation("p", "s"))
    assert _only(ledger).refresh_hook_applied_version == 7
    ledger.record_hook_added("p", "s")
    assert _only(ledger).refresh_hook_applied_version == 0


def test_hook_applied_from_an_older_generation_is_ignored():
    ledger = UsageLedger()
    ledger.record_version("p", "s", 5)
    ledger.record_hook_added("p", "s")
    before = ledger.hook_generation("p", "s")
    ledger.record_hook_added("p", "s")  # added while a delivery of v5 was running
    ledger.record_hook_applied("p", "s", 5, before)
    assert _only(ledger).refresh_hook_applied_version == 0
    ledger.record_hook_applied("p", "s", 5, ledger.hook_generation("p", "s"))
    assert _only(ledger).refresh_hook_applied_version == 5


def test_hook_generation_is_zero_for_an_unknown_secret():
    assert UsageLedger().hook_generation("p", "unknown") == 0


def test_stale_delivery_cannot_mark_a_version_applied_after_hooks_changed():
    ledger = UsageLedger()
    ledger.record_version("p", "s", 5)
    ledger.record_hook_added("p", "s")
    stale = ledger.hook_generation("p", "s")
    ledger.record_hook_added("p", "s")
    ledger.record_hook_applied("p", "s", 5, stale)
    assert _only(ledger).refresh_hook_applied_version == 0
