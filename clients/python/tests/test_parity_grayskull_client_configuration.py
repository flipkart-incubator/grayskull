"""Port of Java GrayskullClientConfigurationTest.

Differences from Java, asserted as the Python behaviour:
- add_default_header ignores empty names and values as well as None. An empty header name cannot be
  sent by http.client, so storing it would only fail later, on the first request.
- Java's testHeartbeatDefaults_whenOmittedFromJson binds JSON with Jackson; Python has no JSON
  binding, so the same defaults are checked for keyword arguments.
"""

import pytest

from grayskull import DefaultWorkloadIdentityResolver, GrayskullClientConfiguration, WorkloadIdentityResolver


class Fixed(WorkloadIdentityResolver):
    def __init__(self, identity: str) -> None:
        self.identity = identity

    def resolve(self) -> str:
        return self.identity


def config(**settings) -> GrayskullClientConfiguration:
    return GrayskullClientConfiguration(host=settings.pop("host", "https://example.com"), **settings)


def test_workload_identity_resolver_default_is_default_resolver():
    assert isinstance(config().workload_identity_resolver, DefaultWorkloadIdentityResolver)


def test_set_workload_identity_resolver_overrides_default():
    c = config()
    custom = Fixed("pod")
    c.workload_identity_resolver = custom
    assert c.workload_identity_resolver is custom


def test_set_workload_identity_resolver_none_retains_previous_resolver():
    c = config()
    custom = Fixed("stay")
    c.workload_identity_resolver = custom
    c.workload_identity_resolver = None
    assert c.workload_identity_resolver is custom


def test_set_workload_identity_resolver_none_on_fresh_config_retains_default():
    c = config()
    c.workload_identity_resolver = None
    assert isinstance(c.workload_identity_resolver, DefaultWorkloadIdentityResolver)
    assert isinstance(config(workload_identity_resolver=None).workload_identity_resolver,
                      DefaultWorkloadIdentityResolver)


def test_add_default_header_stores_name_and_value():
    c = config()
    c.add_default_header("X-Team", "payments")
    assert c.get_default_headers() == {"X-Team": "payments"}


def test_add_default_header_multiple_headers_all_stored():
    c = config()
    c.add_default_header("X-A", "1")
    c.add_default_header("X-B", "2")
    assert c.get_default_headers() == {"X-A": "1", "X-B": "2"}


def test_add_default_header_same_name_overwrites_value():
    c = config()
    c.add_default_header("X-A", "1")
    c.add_default_header("X-A", "2")
    assert c.get_default_headers() == {"X-A": "2"}


@pytest.mark.parametrize("name,value", [(None, "v"), ("X-A", None), (None, None), ("", ""), ("X-A", "")])
def test_add_default_header_none_or_empty_is_ignored(name, value):
    c = config()
    c.add_default_header(name, value)
    assert c.get_default_headers() == {}


def test_default_headers_fresh_config_is_empty():
    assert config().get_default_headers() == {}


def test_get_default_headers_returns_a_copy():
    c = config()
    c.add_default_header("X-A", "1")
    c.get_default_headers()["X-A"] = "changed"
    c.get_default_headers()["X-New"] = "x"
    assert c.get_default_headers() == {"X-A": "1"}


def test_get_default_headers_reflects_subsequent_adds():
    c = config()
    c.add_default_header("X-A", "1")
    c.add_default_header("X-B", "2")
    assert set(c.get_default_headers()) == {"X-A", "X-B"}


def test_polling_interval_default_is_sixty():
    assert config().polling_interval_seconds == 60


def test_polling_interval_positive_value_stored():
    c = config()
    c.polling_interval_seconds = 5
    assert c.polling_interval_seconds == 5


@pytest.mark.parametrize("value", [0, -1])
def test_polling_interval_zero_or_negative_throws(value):
    with pytest.raises(ValueError, match="Polling interval"):
        config(polling_interval_seconds=value)
    c = config()
    with pytest.raises(ValueError):
        c.polling_interval_seconds = value


@pytest.mark.parametrize("host", [None, "", "   "])
def test_host_null_empty_or_blank_throws(host):
    with pytest.raises(ValueError, match="Host cannot be null or empty"):
        GrayskullClientConfiguration(host=host)


def test_host_strips_trailing_slash():
    assert config(host="https://example.com/path/").host == "https://example.com/path"


def test_host_without_trailing_slash_unchanged():
    assert config(host="https://example.com/path").host == "https://example.com/path"


@pytest.mark.parametrize("field,value,message", [
    ("connection_timeout", 0, "Connection timeout"), ("connection_timeout", -5, "Connection timeout"),
    ("read_timeout", 0, "Read timeout"), ("read_timeout", -5, "Read timeout"),
    ("max_connections", 0, "Max connections"), ("max_connections", -1, "Max connections"),
    ("max_retries", 0, "less than 1"), ("max_retries", 11, "greater than 10"),
    ("min_retry_delay", 49, "at least 50ms"),
])
def test_out_of_range_values_throw(field, value, message):
    with pytest.raises(ValueError, match=message):
        config(**{field: value})
    c = config()
    with pytest.raises(ValueError, match=message):
        setattr(c, field, value)


def test_setters_and_getters_round_trip():
    resolver = Fixed("pod")
    c = config()
    c.host = "http://other:8080/"
    c.connection_timeout = 1000
    c.read_timeout = 2000
    c.max_connections = 3
    c.max_retries = 4
    c.min_retry_delay = 60
    c.metrics_enabled = False
    c.polling_interval_seconds = 7
    c.heartbeat_enabled = False
    c.heartbeat_interval_seconds = 120
    c.workload_identity_resolver = resolver
    assert (c.host, c.connection_timeout, c.read_timeout, c.max_connections, c.max_retries, c.min_retry_delay,
            c.metrics_enabled, c.polling_interval_seconds, c.heartbeat_enabled, c.heartbeat_interval_seconds,
            c.workload_identity_resolver) == (
        "http://other:8080", 1000, 2000, 3, 4, 60, False, 7, False, 120, resolver)


def test_heartbeat_interval_zero_means_default_and_negative_is_rejected():
    c = config()
    c.heartbeat_interval_seconds = 0
    assert c.heartbeat_interval_seconds == 86400
    with pytest.raises(ValueError, match="cannot be negative"):
        c.heartbeat_interval_seconds = -1


def test_heartbeat_defaults_when_omitted():
    omitted = config()
    assert (omitted.heartbeat_enabled, omitted.heartbeat_interval_seconds) == (True, 86400)
    disabled = config(heartbeat_enabled=False, heartbeat_interval_seconds=0)
    assert (disabled.heartbeat_enabled, disabled.heartbeat_interval_seconds) == (False, 86400)
