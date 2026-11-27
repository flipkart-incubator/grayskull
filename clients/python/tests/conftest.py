"""Shared fixtures for the Grayskull client tests."""

import pytest
from fake_grayskull_server import FakeGrayskullServer


@pytest.fixture
def server():
    fake = FakeGrayskullServer()
    running = fake.start()
    fake.url = running.url
    yield fake
    running.stop()


# The configuration only accepts whole seconds. Client tests run the poller and heartbeat with
# one configured second lasting TEST_SECOND real seconds, so polling_interval_seconds=1 polls every 50ms.
TEST_SECOND = 0.05


@pytest.fixture(autouse=True)
def fast_intervals(monkeypatch):
    import grayskull.client_impl.grayskull_client_impl as impl

    def scaled(original):
        def build(http_client, base_url, interval_seconds, *rest):
            return original(http_client, base_url, interval_seconds * TEST_SECOND, *rest)
        return build

    monkeypatch.setattr(impl, "HookRefreshPoller", scaled(impl.HookRefreshPoller))
    monkeypatch.setattr(impl, "HeartbeatScheduler", scaled(impl.HeartbeatScheduler))
