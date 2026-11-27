"""Port of Java HeartbeatRequestTest. The body must match the shared fixture in clients/testdata."""

import json
from pathlib import Path

from grayskull.client_impl.models.request.heartbeat_request import HeartbeatRequest, HeldSecret

FIXTURE = Path(__file__).resolve().parents[2] / "testdata" / "heartbeat" / "client_heartbeat_v1.json"


def test_serializes_to_shared_fixture():
    assert FIXTURE.exists(), f"shared fixture missing: {FIXTURE}"
    request = HeartbeatRequest([
        HeldSecret("p", "s", 5, 5),
        HeldSecret("p", "hook-pending", 3, 0),
        HeldSecret("p", "no-hook", 2, None),
    ])
    assert request.to_dict() == json.loads(FIXTURE.read_text())


def test_no_hook_omits_refresh_hook_applied_version():
    node = HeldSecret("p", "s", 1, None).to_dict()
    assert "refreshHookAppliedVersion" not in node
    assert len(node) == 3
