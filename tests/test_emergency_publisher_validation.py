"""BLOCK v1.0.78 — the publisher's admission rules, as pure functions.

The external deep review found two holes that let bad data (or a bad URL) reach
the artifact that ships inside the app:

1. `normalize_ip` validated IPv4 *syntax* only, so loopback / private /
   link-local / CGNAT / multicast / reserved addresses passed and were then
   health-checked (`probe_tcp` opens a real TCP connection) and could be
   published into `emergency-sources/servers.csv`.
2. `--published-raw-url` was written verbatim into
   `app/src/main/res/values/emergency_defaults.xml`, i.e. into the APK.

These tests pin the predicates that close both, plus the redirect rule that
stops a source from steering the publisher at another host or downgrading it to
plaintext.
"""

import pytest

from emergency_csv_publisher import (
    DEFAULT_RAW_URL,
    PublisherError,
    _same_origin_https_safe,
    is_public_ipv4,
    validate_published_url,
)


# ── is_public_ipv4 ───────────────────────────────────────────────────────

@pytest.mark.parametrize(
    "ip",
    ["8.8.8.8", "1.1.1.1", "118.241.169.191", "202.122.252.243"],
)
def test_public_addresses_are_accepted(ip):
    assert is_public_ipv4(ip) is True


@pytest.mark.parametrize(
    "ip",
    [
        "127.0.0.1",        # loopback
        "10.1.2.3",         # RFC1918
        "172.16.5.4",       # RFC1918
        "192.168.0.1",      # RFC1918
        "169.254.1.1",      # link-local
        "100.64.0.1",       # CGNAT (RFC6598)
        "224.0.0.1",        # multicast
        "240.0.0.1",        # reserved
        "0.0.0.0",          # unspecified
        "255.255.255.255",  # broadcast
    ],
)
def test_non_public_addresses_are_refused(ip):
    assert is_public_ipv4(ip) is False


@pytest.mark.parametrize("value", ["", "not-an-ip", "999.1.1.1", "1.2.3", "1.2.3.4.5"])
def test_malformed_values_are_refused(value):
    assert is_public_ipv4(value) is False


# ── validate_published_url ───────────────────────────────────────────────

def test_the_default_url_is_valid():
    assert validate_published_url(DEFAULT_RAW_URL) == DEFAULT_RAW_URL


def test_a_plain_http_url_is_refused():
    with pytest.raises(PublisherError, match="https"):
        validate_published_url(
            "http://raw.githubusercontent.com/morteza-taheri/SoftEthergai/main/x.csv"
        )


def test_a_foreign_host_is_refused():
    with pytest.raises(PublisherError, match="host"):
        validate_published_url("https://evil.example.com/morteza-taheri/SoftEthergai/x.csv")


def test_a_same_owner_lookalike_repository_is_refused():
    # The same trap the in-app update checker guards against (v1.0.77).
    with pytest.raises(PublisherError, match="path"):
        validate_published_url(
            "https://raw.githubusercontent.com/morteza-taheri/SoftEthergaiXYZ/main/x.csv"
        )


def test_another_owners_repository_is_refused():
    with pytest.raises(PublisherError, match="path"):
        validate_published_url("https://raw.githubusercontent.com/someone/SoftEthergai/main/x.csv")


def test_the_bare_repository_path_is_accepted():
    url = "https://github.com/morteza-taheri/SoftEthergai"
    assert validate_published_url(url) == url


# ── _same_origin_https_safe ──────────────────────────────────────────────

def test_same_host_https_redirect_is_allowed():
    assert _same_origin_https_safe(
        "https://www.vpngate.net/api/iphone/",
        "https://www.vpngate.net/api/iphone",
    ) is True


def test_cross_host_redirect_is_refused():
    assert _same_origin_https_safe(
        "https://www.vpngate.net/api/iphone/",
        "https://evil.example.com/api",
    ) is False


def test_https_to_http_downgrade_is_refused():
    assert _same_origin_https_safe(
        "https://www.vpngate.net/api/iphone/",
        "http://www.vpngate.net/api/iphone/",
    ) is False


def test_plain_http_to_http_on_the_same_host_is_allowed():
    # The VPN Gate API is only reachable over http; a same-host hop is fine.
    assert _same_origin_https_safe(
        "http://www.vpngate.net/api/iphone/",
        "http://www.vpngate.net/api/iphone",
    ) is True
