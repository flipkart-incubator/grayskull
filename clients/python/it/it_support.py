"""Constants and fixture loading shared by the integration tests and their conftest."""

import json
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parents[3]
TESTDATA = REPO_ROOT / "clients" / "testdata"
ADMIN = "admin"  # simple-app's authorization rules give "admin" every action on every project


def fixture_json(name: str) -> Any:
    """Load a shared wire fixture from clients/testdata."""
    return json.loads((TESTDATA / name).read_text(encoding="utf-8"))
