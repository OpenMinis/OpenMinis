#!/usr/bin/env python3
"""Unit tests for `call` argument parsing (GH#292).

Run: python3 test_call_args.py  (stdlib unittest, no deps)

`minis-mcp-cli call <server> <tool> '{"k":"v"}'` passes the tool arguments as a
positional JSON object. --input and key=value keep working; key=value pairs
extend / override the JSON object; positional JSON together with --input,
a non-object JSON value, malformed JSON and a bare word are rejected with a
PARSE_ERROR that says what to do instead.

The daemon is never contacted: call_daemon is replaced by a stub that records
the request, and _emit output is captured to read the error envelope.
"""

import io
import json
import os
import sys
import unittest
from contextlib import redirect_stdout

# The package ships inside each app's default mount. The two copies are
# kept in step, but a platform can be published ahead of the other, so the
# default is the Android copy; set MINIS_MCP_CLI_DIR to test the iOS one
# (src/ios/default_mount/usr/local/lib/minis-mcp-cli).
_PKG = os.environ.get("MINIS_MCP_CLI_DIR") or os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", "..", "..",
    "src", "android", "app", "src", "main", "assets", "default_mount",
    "usr", "local", "lib", "minis-mcp-cli")
sys.path.insert(0, os.path.abspath(_PKG))

import main  # noqa: E402


class CallArgsTest(unittest.TestCase):
    def setUp(self):
        self.requests = []
        self._orig_call_daemon = main.call_daemon
        self._orig_log = main._log
        main.call_daemon = lambda request, pretty: self.requests.append(request) or {"ok": True}
        main._log = lambda msg: None  # keep the tests off /var/minis

    def tearDown(self):
        main.call_daemon = self._orig_call_daemon
        main._log = self._orig_log

    def call(self, *argv):
        """Run cmd_call; return (tool args sent to the daemon, error envelope)."""
        out = io.StringIO()
        with redirect_stdout(out):
            try:
                main.cmd_call(list(argv), False)
            except SystemExit as exc:
                self.assertNotEqual(exc.code, 0)
                return None, json.loads(out.getvalue().strip().splitlines()[-1])
        self.assertEqual(len(self.requests), 1)
        req = self.requests[0]
        self.assertEqual((req["cmd"], req["server"]), ("call", argv[0]))
        return req["args"], None

    def assertParseError(self, err, *fragments):
        self.assertIsNotNone(err)
        self.assertEqual(err["code"], "PARSE_ERROR")
        for f in fragments:
            self.assertIn(f, err["error"])
        self.assertEqual(self.requests, [], "nothing may reach the daemon on a parse error")

    # --- accepted forms ----------------------------------------------------

    def test_positional_json_object(self):
        args, err = self.call("tavily", "search", '{"query":"test"}')
        self.assertIsNone(err)
        self.assertEqual(args, {"query": "test"})

    def test_positional_json_with_surrounding_whitespace(self):
        args, _ = self.call("tavily", "search", '  {"query": "test"}\n')
        self.assertEqual(args, {"query": "test"})

    def test_positional_json_keeps_types(self):
        args, _ = self.call("tavily", "search", '{"n": 5, "deep": true, "tags": ["a"], "x": null}')
        self.assertEqual(args, {"n": 5, "deep": True, "tags": ["a"], "x": None})

    def test_positional_json_containing_equals_is_not_split(self):
        # The old parser split any token with '=' as key=value.
        args, _ = self.call("fetch", "get", '{"url":"https://api.example.com/?key=val&a=b"}')
        self.assertEqual(args, {"url": "https://api.example.com/?key=val&a=b"})

    def test_input_flag_still_works(self):
        args, err = self.call("tavily", "search", "--input", '{"query":"test"}')
        self.assertIsNone(err)
        self.assertEqual(args, {"query": "test"})

    def test_key_value_pairs_only(self):
        args, _ = self.call("notion", "search", "query=meeting", "page_size=10")
        self.assertEqual(args, {"query": "meeting", "page_size": "10"})

    def test_key_value_overrides_and_extends_positional_json(self):
        args, _ = self.call("tavily", "search", '{"query":"old"}', "query=new", "limit=5")
        self.assertEqual(args, {"query": "new", "limit": "5"})

    def test_key_value_overrides_and_extends_input(self):
        args, _ = self.call("tavily", "search", "--input", '{"query":"old"}', "query=new", "limit=5")
        self.assertEqual(args, {"query": "new", "limit": "5"})

    def test_key_value_before_positional_json_still_overrides(self):
        args, _ = self.call("tavily", "search", "query=new", '{"query":"old","limit":1}')
        self.assertEqual(args, {"query": "new", "limit": 1})

    def test_value_that_is_json_stays_a_string(self):
        # key={...} is a key=value pair, not a positional JSON argument.
        args, _ = self.call("s", "t", 'filter={"a":1}')
        self.assertEqual(args, {"filter": '{"a":1}'})

    def test_no_arguments(self):
        args, _ = self.call("s", "t")
        self.assertEqual(args, {})

    def test_empty_object(self):
        args, _ = self.call("s", "t", "{}")
        self.assertEqual(args, {})

    # --- rejected forms ----------------------------------------------------

    def test_positional_json_and_input_conflict(self):
        _, err = self.call("tavily", "search", '{"a":1}', "--input", '{"b":2}')
        self.assertParseError(err, "cannot specify both positional JSON and --input")

    def test_positional_json_array_rejected(self):
        _, err = self.call("tavily", "search", "[1, 2, 3]")
        self.assertParseError(err, "must be a JSON object", "(got list)")

    def test_input_must_be_object(self):
        _, err = self.call("tavily", "search", "--input", '"just a string"')
        self.assertParseError(err, "--input JSON must be a JSON object", "(got str)")

    def test_malformed_positional_json(self):
        _, err = self.call("tavily", "search", '{"query":}')
        self.assertParseError(err, "invalid positional JSON argument '{\"query\":}'")

    def test_malformed_input_json(self):
        _, err = self.call("tavily", "search", "--input", "{bad}")
        self.assertParseError(err, "invalid --input JSON")

    def test_unknown_bare_argument_shows_example(self):
        _, err = self.call("tavily", "search", "invalid_arg")
        self.assertParseError(err, "unexpected argument: invalid_arg",
                              "Expected a JSON object", "key=value",
                              "Example: minis-mcp-cli call tavily search '{\"query\":\"test\"}'")
        self.assertEqual(err["server"], "tavily")

    def test_second_positional_json_rejected(self):
        _, err = self.call("s", "t", '{"a":1}', '{"b":2}')
        self.assertParseError(err, "unexpected argument")

    def test_missing_tool(self):
        _, err = self.call("tavily")
        self.assertParseError(err, "usage: call <server> <tool>", "--help")

    # --- help text ---------------------------------------------------------

    def test_usage_documents_positional_json(self):
        self.assertIn("""call <server> <tool> ['{"k":"v"}'] [--input '{}'] [key=value ...]""", main.USAGE)
        self.assertIn("""minis-mcp-cli call notion search '{"query":"meeting"}'""", main.USAGE)
        self.assertIn("""minis-mcp-cli call notion search '{"query":"meeting"}' page_size=20""", main.USAGE)


if __name__ == "__main__":
    unittest.main(verbosity=2)
