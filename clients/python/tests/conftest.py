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
