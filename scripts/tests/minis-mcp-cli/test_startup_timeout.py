#!/usr/bin/env python3
"""Unit tests for the configurable STDIO startup/handshake timeout (GH#75).

Run: python3 test_startup_timeout.py  (stdlib unittest, no deps)

Covers:
  * config.resolve_startup_timeout — default, primary field, every alias,
    priority conflicts, and invalid/boundary values (with warnings).
  * daemon.MCPServerProcess._handshake — that the resolved per-server timeout is
    actually passed to the initialize RPC, that a configured (larger) timeout
    lets a server that is slower-than-default still finish, and that a default
    timeout produces an actionable, config-suggesting error message.

Timing is injected (a fake reader that sleeps a few ms, tiny timeouts) so the
suite runs in well under a second and never actually waits 60s.
"""

import os
import sys
import threading
import time
import unittest

# The package ships inside each app's default mount. The two copies are
# kept in step, but a platform can be published ahead of the other, so the
# default is the Android copy; set MINIS_MCP_CLI_DIR to test the iOS one
# (src/ios/default_mount/usr/local/lib/minis-mcp-cli).
_PKG = os.environ.get("MINIS_MCP_CLI_DIR") or os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", "..", "..",
    "src", "android", "app", "src", "main", "assets", "default_mount",
    "usr", "local", "lib", "minis-mcp-cli")
sys.path.insert(0, os.path.abspath(_PKG))

from utils import config  # noqa: E402
from transport.http import MCPError  # noqa: E402
import daemon  # noqa: E402


class ResolveStartupTimeoutTests(unittest.TestCase):
    def test_default_when_absent(self):
        value, warning = config.resolve_startup_timeout({"command": "x"})
        self.assertEqual(value, config.DEFAULT_STARTUP_TIMEOUT)
        self.assertEqual(value, 60)
        self.assertIsNone(warning)

    def test_none_server(self):
        value, warning = config.resolve_startup_timeout(None)
        self.assertEqual(value, 60)
        self.assertIsNone(warning)

    def test_primary_field(self):
        value, warning = config.resolve_startup_timeout({"startupTimeoutSeconds": 120})
        self.assertEqual(value, 120)
        self.assertIsNone(warning)

    def test_primary_field_string_coerced(self):
        value, warning = config.resolve_startup_timeout({"startupTimeoutSeconds": "120"})
        self.assertEqual(value, 120)
        self.assertIsNone(warning)

    def test_each_alias(self):
        cases = {
            "startup_timeout_sec": 71,
            "startupTimeout": 72,
            "handshakeTimeout": 73,
            "initializeTimeout": 74,
        }
        for key, val in cases.items():
            value, warning = config.resolve_startup_timeout({key: val})
            self.assertEqual(value, val, key)
            self.assertIsNone(warning, key)

    def test_priority_primary_beats_all_aliases(self):
        server = {
            "startupTimeoutSeconds": 10,
            "startup_timeout_sec": 20,
            "startupTimeout": 30,
            "handshakeTimeout": 40,
            "initializeTimeout": 50,
        }
        value, warning = config.resolve_startup_timeout(server)
        self.assertEqual(value, 10)
        self.assertIsNone(warning)

    def test_priority_alias_order(self):
        # Drop the primary; snake_case wins over the rest.
        server = {
            "startup_timeout_sec": 20,
            "startupTimeout": 30,
            "handshakeTimeout": 40,
        }
        value, _ = config.resolve_startup_timeout(server)
        self.assertEqual(value, 20)
        # Drop snake_case too; startupTimeout wins.
        del server["startup_timeout_sec"]
        value, _ = config.resolve_startup_timeout(server)
        self.assertEqual(value, 30)

    def test_invalid_value_warns_and_falls_back(self):
        for bad in ["abc", None, 0, -5, config.MAX_STARTUP_TIMEOUT + 1, 1.5, True, False]:
            value, warning = config.resolve_startup_timeout({"startupTimeoutSeconds": bad})
            self.assertEqual(value, 60, "value=%r" % bad)
            self.assertIsNotNone(warning, "value=%r" % bad)
            # Warning must name the field, the given value, the range, and fallback.
            self.assertIn("startupTimeoutSeconds", warning)
            self.assertIn(str(config.MAX_STARTUP_TIMEOUT), warning)
            self.assertIn("60", warning)

    def test_boundary_values_accepted(self):
        for good in [1, config.MAX_STARTUP_TIMEOUT]:
            value, warning = config.resolve_startup_timeout({"startupTimeoutSeconds": good})
            self.assertEqual(value, good)
            self.assertIsNone(warning)


class _FakeProc:
    """Minimal stand-in for subprocess.Popen used by _read_reply's reader
    thread. `reply_after` seconds after the reader starts consuming stdout, it
    yields the initialize reply line; if `never` is set it blocks forever."""

    def __init__(self, want_id, reply_after=0.0, never=False):
        self._want_id = want_id
        self._reply_after = reply_after
        self._never = never
        self.stdin = self._Stdin()

    class _Stdin:
        def write(self, _):
            pass

        def flush(self):
            pass

    @property
    def stdout(self):
        def gen():
            if self._never:
                # Block the reader thread so join() times out.
                while True:
                    time.sleep(0.05)
            time.sleep(self._reply_after)
            yield '{"jsonrpc":"2.0","id":%d,"result":{}}\n' % self._want_id
        return gen()

    def poll(self):
        return None


class HandshakeTimeoutTests(unittest.TestCase):
    def _make_server(self, cfg):
        srv = daemon.MCPServerProcess("atlassian-mcp", cfg)
        # _handshake sends initialize (id=1) then reads the reply; wire a fake
        # proc that answers id=1.
        srv.proc = None  # set per-test after we know the want_id
        return srv

    def test_configured_timeout_is_passed_through(self):
        # Prove the resolved value (not a hardcoded 30) reaches the RPC layer.
        captured = {}
        srv = daemon.MCPServerProcess("atlassian-mcp", {"startupTimeoutSeconds": 120})

        def fake_rpc(method, params=None, timeout=None):
            captured["method"] = method
            captured["timeout"] = timeout
            return {}

        srv._rpc = fake_rpc
        srv._send = lambda *a, **k: None
        srv._handshake()
        self.assertEqual(captured["method"], "initialize")
        self.assertEqual(captured["timeout"], 120)

    def test_default_timeout_is_60(self):
        captured = {}
        srv = daemon.MCPServerProcess("atlassian-mcp", {"command": "uvx"})
        srv._rpc = lambda m, p=None, timeout=None: captured.setdefault("timeout", timeout) or {}
        srv._send = lambda *a, **k: None
        srv._handshake()
        self.assertEqual(captured["timeout"], 60)

    def test_slow_server_finishes_within_configured_timeout(self):
        # Server replies after 120ms; a configured (injected via _rpc timeout)
        # window of 2s comfortably covers it -> handshake succeeds.
        srv = daemon.MCPServerProcess("atlassian-mcp", {"startupTimeoutSeconds": 2})
        want_id = 1
        srv.proc = _FakeProc(want_id, reply_after=0.12)
        srv._id = 0  # so _next_id() -> 1 matches the fake reply id
        # Real _read_reply + _rpc, but resolve_startup_timeout returns 2s.
        srv._handshake()  # must not raise

    def test_default_timeout_message_is_actionable(self):
        # A server that never replies must, at the DEFAULT timeout, raise a
        # TIMEOUT whose message names the server, the seconds, and the exact
        # config key to set. Inject a tiny timeout so we don't wait 60s: patch
        # resolve_startup_timeout to return (0.05, None).
        srv = daemon.MCPServerProcess("atlassian-mcp", {"command": "uvx"})
        srv.proc = _FakeProc(1, never=True)
        srv._id = 0

        orig = config.resolve_startup_timeout
        config.resolve_startup_timeout = lambda cfg: (0.05, None)
        try:
            with self.assertRaises(MCPError) as ctx:
                srv._handshake()
        finally:
            config.resolve_startup_timeout = orig

        exc = ctx.exception
        self.assertEqual(exc.code, "TIMEOUT")
        msg = exc.message
        self.assertIn("atlassian-mcp", msg)
        self.assertIn("timed out", msg)
        self.assertIn("startupTimeoutSeconds", msg)


if __name__ == "__main__":
    unittest.main(verbosity=2)
