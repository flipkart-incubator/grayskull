"""Port of Java SecretStateTest."""

from grayskull.client_impl.hooks.secret_state import SecretState


def test_new_state_stores_identity_fields():
    state = SecretState("p", "s", 0)
    assert state.project_id == "p"
    assert state.secret_name == "s"


def test_last_known_version_is_what_the_caller_passes():
    assert SecretState("p", "s", 0).last_known_version == 0
    assert SecretState("p", "s", 7).last_known_version == 7


def test_new_state_is_not_executing_and_has_no_pending_update():
    state = SecretState("p", "s", 0)
    assert state.try_start_execution() is True
    state.finish_execution()
    assert state.has_pending_update() is False
    assert state.take_pending_update() is None


def test_new_state_has_no_hooks():
    state = SecretState("p", "s", 0)
    assert state.has_hooks() is False
    assert state.snapshot_hooks() == []


def test_iterating_a_snapshot_while_hooks_are_added_and_removed_does_not_fail():
    # HookRefreshPoller delivers over a snapshot, so mutating the registry mid-delivery must be safe.
    state = SecretState("p", "s", 0)
    first = lambda value: None
    second = lambda value: None
    state.add_hook(first)
    for hook in state.snapshot_hooks():
        state.add_hook(second)
        state.remove_hook(hook)
    assert state.snapshot_hooks() == [second]  # the snapshot had one hook, so the loop ran once
