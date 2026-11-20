import json
import threading
from pathlib import Path

from grayskull.client_impl.usage_ledger import UsageLedger

FIXTURE = Path(__file__).resolve().parents[2] / "testdata" / "heartbeat" / "client_heartbeat_v1.json"


def _by_name(ledger: UsageLedger, name: str):
    matches = [s for s in ledger.snapshot() if s.secret_name == name]
    assert len(matches) == 1, f"expected one entry for {name}"
    return matches[0]


def test_empty_ledger_has_empty_snapshot_and_zero_version():
    ledger = UsageLedger()
    assert ledger.snapshot() == []
    assert ledger.version("p", "s") == 0


def test_fetched_secret_without_hook_has_no_applied_version():
    ledger = UsageLedger()
    ledger.record_fetch("p", "s", 3)
    entry = _by_name(ledger, "s")
    assert (entry.project_id, entry.version, entry.refresh_hook_applied_version) == ("p", 3, None)


def test_version_is_newest_held_from_fetch_or_poller():
    ledger = UsageLedger()
    ledger.record_fetch("p", "s", 5)
    ledger.record_version("p", "s", 8)
    ledger.record_fetch("p", "s", 6)  # a stale read never lowers the held version
    assert ledger.version("p", "s") == 8


def test_hook_registered_before_any_fetch_is_not_reported():
    ledger = UsageLedger()
    ledger.set_hook_registered("p", "s", True)
    assert ledger.snapshot() == []
    assert ledger.version("p", "s") == 0


def test_applied_version_is_zero_until_a_hook_succeeds():
    ledger = UsageLedger()
    ledger.record_fetch("p", "s", 3)
    ledger.set_hook_registered("p", "s", True)
    assert _by_name(ledger, "s").refresh_hook_applied_version == 0
    ledger.record_hook_applied("p", "s", 3, ledger.hook_generation("p", "s"))
    assert _by_name(ledger, "s").refresh_hook_applied_version == 3


def test_hook_applied_only_advances_forward():
    ledger = UsageLedger()
    ledger.record_version("p", "s", 7)
    ledger.set_hook_registered("p", "s", True)
    ledger.record_hook_applied("p", "s", 7, ledger.hook_generation("p", "s"))
    ledger.record_hook_applied("p", "s", 6, ledger.hook_generation("p", "s"))  # an older delivery never lowers it
    assert _by_name(ledger, "s").refresh_hook_applied_version == 7


def test_hook_registration_toggles_presence_and_keeps_applied_version():
    ledger = UsageLedger()
    ledger.record_version("p", "s", 4)
    ledger.set_hook_registered("p", "s", True)
    ledger.record_hook_applied("p", "s", 4, ledger.hook_generation("p", "s"))
    assert _by_name(ledger, "s").refresh_hook_applied_version == 4
    ledger.set_hook_registered("p", "s", False)
    assert _by_name(ledger, "s").refresh_hook_applied_version is None
    ledger.set_hook_registered("p", "s", True)
    assert _by_name(ledger, "s").refresh_hook_applied_version == 0, "a new hook must apply a version itself"


def test_secrets_are_kept_apart():
    ledger = UsageLedger()
    ledger.record_fetch("p", "a", 1)
    ledger.set_hook_registered("p", "b", True)
    ledger.record_fetch("q", "a", 9)
    assert len(ledger.snapshot()) == 2  # p:b has a hook but holds no version yet
    assert ledger.version("p", "a") == 1
    assert ledger.version("p", "b") == 0
    assert ledger.version("q", "a") == 9


def test_concurrent_recording_keeps_newest_version():
    ledger = UsageLedger()
    per_thread = 2_000

    def work() -> None:
        for i in range(per_thread):
            ledger.record_fetch("p", "s", i)
            ledger.record_hook_applied("p", "s", i, ledger.hook_generation("p", "s"))
            ledger.snapshot()

    threads = [threading.Thread(target=work) for _ in range(8)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    ledger.set_hook_registered("p", "s", True)
    entry = _by_name(ledger, "s")
    assert entry.version == per_thread - 1
    assert entry.refresh_hook_applied_version == per_thread - 1


def test_snapshot_matches_shared_cross_sdk_fixture():
    """The wire payload must match the shared heartbeat fixture."""
    ledger = UsageLedger()
    ledger.record_version("p", "s", 5)
    ledger.set_hook_registered("p", "s", True)
    ledger.record_hook_applied("p", "s", 5, ledger.hook_generation("p", "s"))
    ledger.record_version("p", "hook-pending", 3)
    ledger.set_hook_registered("p", "hook-pending", True)
    ledger.record_version("p", "no-hook", 2)

    produced = sorted((s.to_dict() for s in ledger.snapshot()), key=lambda d: d["secretName"])
    expected = sorted(json.loads(FIXTURE.read_text())["secrets"], key=lambda d: d["secretName"])
    assert produced == expected


def test_adding_a_hook_clears_the_applied_version_until_it_runs():
    ledger = UsageLedger()
    ledger.record_version("p", "s", 7)
    ledger.record_hook_added("p", "s")
    ledger.record_hook_applied("p", "s", 7, ledger.hook_generation("p", "s"))
    assert _by_name(ledger, "s").refresh_hook_applied_version == 7
    ledger.record_hook_added("p", "s")
    assert _by_name(ledger, "s").refresh_hook_applied_version == 0, "the new hook has not applied v7"
