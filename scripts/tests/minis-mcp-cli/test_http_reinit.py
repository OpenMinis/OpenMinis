#!/usr/bin/env python3
"""[T-mcp-http-reinit] Tests for the duplicate-initialize regression.

Run: python3 test_http_reinit.py   (stdlib unittest + httpx MockTransport)

A stateful streamable-HTTP MCP server returns an `Mcp-Session-Id` on
`initialize` and rejects any SECOND `initialize` on that session with
HTTP 400 "Server already initialized". The old transport called
`initialize()` before EVERY call_tool / list_tools, so the 2nd call in a
daemon lifetime always 400'd. These tests pin:

  * one handshake per transport lifetime (2nd call_tool does NOT re-init);
  * self-heal — if the server forgets the session, the transport drops it,
    re-handshakes, and retries once, transparently;
  * ping() uses a fresh anonymous session and never poisons the cached one;
  * the stateless server (no session id) path still works and is unaffected.

Timing/sockets are faked with httpx.MockTransport, so the suite is instant
and hermetic.
"""

import json
import os
import sys
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

import httpx  # noqa: E402

import transport.http as http_mod  # noqa: E402
from transport.http import HTTPTransport, MCPError  # noqa: E402


class StatefulServer:
    """A minimal stateful streamable-HTTP MCP server.

    - `initialize` on a session-less request mints a session id and returns it
      in the Mcp-Session-Id header.
    - a SECOND `initialize` carrying that session id → 400 "already
      initialized" (the real FastMCP behaviour that exposed the bug).
    - other methods require the current session id; an unknown/absent one →
      the configured session-error response.
    """

    def __init__(self, session_error_status=400,
                 session_error_msg="Bad Request: No valid session ID provided"):
        self.session_id = "sess-abc-123"
        self.initialized = False
        self.calls = []  # (method, session_id_seen)
        self.session_error_status = session_error_status
        self.session_error_msg = session_error_msg
        # When set, the server pretends it forgot the session exactly once, to
        # exercise the self-heal path.
        self.forget_session_once = False

    def handler(self, request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content.decode("utf-8"))
        method = body.get("method")
        seen_sid = request.headers.get("mcp-session-id")
        self.calls.append((method, seen_sid))

        if method == "notifications/initialized":
            return httpx.Response(202, text="")

        if method == "initialize":
            if seen_sid and self.initialized:
                return httpx.Response(
                    400,
                    text="Bad Request: Server already initialized",
                )
            self.initialized = True
            return httpx.Response(
                200,
                headers={"Mcp-Session-Id": self.session_id,
                         "content-type": "application/json"},
                text=json.dumps({"jsonrpc": "2.0", "id": body.get("id"),
                                 "result": {"protocolVersion": "2025-06-18"}}),
            )

        # Non-init method: validate the session.
        if self.forget_session_once:
            self.forget_session_once = False
            # Server evicted the session: it no longer knows this id.
            self.initialized = False
            return httpx.Response(self.session_error_status,
                                  text=self.session_error_msg)
        if seen_sid != self.session_id:
            return httpx.Response(self.session_error_status,
                                  text=self.session_error_msg)

        if method == "tools/list":
            return httpx.Response(
                200, headers={"content-type": "application/json"},
                text=json.dumps({"jsonrpc": "2.0", "id": body.get("id"),
                                 "result": {"tools": [{"name": "echo"}]}}),
            )
        if method == "tools/call":
            return httpx.Response(
                200, headers={"content-type": "application/json"},
                text=json.dumps({"jsonrpc": "2.0", "id": body.get("id"),
                                 "result": {"ok": True,
                                            "args": body["params"]["arguments"]}}),
            )
        return httpx.Response(200, headers={"content-type": "application/json"},
                              text=json.dumps({"jsonrpc": "2.0",
                                               "id": body.get("id"),
                                               "result": {}}))


class ShiftingSessionServer:
    """Hands back a session id on initialize, then validates against a
    different one — so no handshake ever produces a usable session. Exercises
    the "give up after one retry" bound."""

    def __init__(self):
        self.session_id = "sess-0"
        self.calls = []

    def handler(self, request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content.decode("utf-8"))
        method = body.get("method")
        seen_sid = request.headers.get("mcp-session-id")
        self.calls.append((method, seen_sid))
        if method == "notifications/initialized":
            return httpx.Response(202, text="")
        if method == "initialize":
            resp = httpx.Response(
                200,
                headers={"Mcp-Session-Id": self.session_id,
                         "content-type": "application/json"},
                text=json.dumps({"jsonrpc": "2.0", "id": body.get("id"),
                                 "result": {}}),
            )
            # Shift AFTER responding, so the id the client caches is never the
            # one the next non-init call is validated against.
            self.session_id = "sess-%d" % len(self.calls)
            return resp
        if seen_sid != self.session_id:
            return httpx.Response(400, text="Bad Request: No valid session ID provided")
        return httpx.Response(200, headers={"content-type": "application/json"},
                              text=json.dumps({"jsonrpc": "2.0",
                                               "id": body.get("id"),
                                               "result": {"ok": True}}))


class StatelessServer:
    """Never returns a session id and tolerates repeated initialize."""

    def __init__(self):
        self.calls = []

    def handler(self, request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content.decode("utf-8"))
        method = body.get("method")
        self.calls.append(method)
        if method == "notifications/initialized":
            return httpx.Response(202, text="")
        if method == "initialize":
            return httpx.Response(
                200, headers={"content-type": "application/json"},
                text=json.dumps({"jsonrpc": "2.0", "id": body.get("id"),
                                 "result": {"protocolVersion": "2025-06-18"}}),
            )
        if method == "tools/list":
            return httpx.Response(
                200, headers={"content-type": "application/json"},
                text=json.dumps({"jsonrpc": "2.0", "id": body.get("id"),
                                 "result": {"tools": []}}),
            )
        return httpx.Response(200, headers={"content-type": "application/json"},
                              text=json.dumps({"jsonrpc": "2.0",
                                               "id": body.get("id"),
                                               "result": {"ok": True}}))


class _PatchedTransport:
    """Context manager: route httpx.post through a MockTransport so the
    HTTPTransport talks to `server.handler` instead of a real socket.

    transport.http calls the module-level `httpx.post`, so we swap that for a
    closure over a Client bound to the mock transport."""

    def __init__(self, server):
        self._client = httpx.Client(transport=httpx.MockTransport(server.handler))
        self._orig_post = http_mod.httpx.post

    def __enter__(self):
        def _post(url, **kwargs):
            return self._client.post(url, **kwargs)
        http_mod.httpx.post = _post
        return self

    def __exit__(self, *exc):
        http_mod.httpx.post = self._orig_post
        self._client.close()
        return False


def _make_transport():
    return HTTPTransport({"url": "https://example.test/mcp"}, "stateful-test")


class DuplicateInitializeTests(unittest.TestCase):
    def test_second_call_does_not_reinitialize(self):
        server = StatefulServer()
        with _PatchedTransport(server):
            t = _make_transport()
            r1 = t.call_tool("echo", {"n": 1})
            r2 = t.call_tool("echo", {"n": 2})
        self.assertTrue(r1["ok"])
        self.assertTrue(r2["ok"])
        self.assertEqual(r2["args"], {"n": 2})
        # Exactly ONE initialize across both calls — the whole point.
        inits = [m for (m, _sid) in server.calls if m == "initialize"]
        self.assertEqual(len(inits), 1,
                         "expected a single handshake, got %d" % len(inits))

    def test_list_then_call_share_one_session(self):
        server = StatefulServer()
        with _PatchedTransport(server):
            t = _make_transport()
            tools = t.list_tools()
            t.call_tool("echo", {"x": 9})
        self.assertEqual(tools, [{"name": "echo"}])
        inits = [m for (m, _sid) in server.calls if m == "initialize"]
        self.assertEqual(len(inits), 1)

    def test_self_heal_when_server_forgets_session(self):
        server = StatefulServer()
        with _PatchedTransport(server):
            t = _make_transport()
            t.call_tool("echo", {"n": 1})          # establishes session
            server.forget_session_once = True      # next non-init 400s once
            r = t.call_tool("echo", {"n": 2})       # must self-heal
        self.assertTrue(r["ok"])
        self.assertEqual(r["args"], {"n": 2})
        # Two handshakes total: the initial one + the recovery one.
        inits = [m for (m, _sid) in server.calls if m == "initialize"]
        self.assertEqual(len(inits), 2)

    def test_self_heal_gives_up_after_one_retry(self):
        # A server that hands back one session id on initialize but validates
        # against a DIFFERENT (shifted) one, so every non-init call fails the
        # session check no matter how often we re-handshake. The transport must
        # retry exactly ONCE and then surface the real error, not loop.
        server = ShiftingSessionServer()
        with _PatchedTransport(server):
            t = _make_transport()
            with self.assertRaises(MCPError):
                t.call_tool("echo", {"n": 1})
        # Bounded: initial handshake + exactly one recovery handshake.
        inits = [m for (m, _sid) in server.calls if m == "initialize"]
        self.assertEqual(len(inits), 2)

    def test_ping_uses_fresh_session_and_does_not_poison_cache(self):
        server = StatefulServer()
        with _PatchedTransport(server):
            t = _make_transport()
            t.call_tool("echo", {"n": 1})  # cache a session
            self.assertTrue(t.ping())      # fresh anonymous handshake
            # ping reset+re-inited, so the cached session is the one ping made;
            # the next call must still succeed (no duplicate-init 400).
            r = t.call_tool("echo", {"n": 2})
        self.assertTrue(r["ok"])

    def test_stateless_server_unaffected(self):
        server = StatelessServer()
        with _PatchedTransport(server):
            t = _make_transport()
            t.call_tool("echo", {"n": 1})
            r = t.call_tool("echo", {"n": 2})
        self.assertTrue(r["ok"])
        # Stateless server never handed back a session id, but we still must not
        # re-initialize (idempotent flag), so a single handshake here too.
        inits = [m for m in server.calls if m == "initialize"]
        self.assertEqual(len(inits), 1)


if __name__ == "__main__":
    unittest.main(verbosity=2)
