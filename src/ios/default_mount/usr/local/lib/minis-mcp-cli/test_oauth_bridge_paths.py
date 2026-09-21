#!/usr/bin/env python3
"""Regression coverage for OAuth bridge paths on the iOS guest mount."""

import contextlib
import io
import json
import os
import stat
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import main as cli_main  # noqa: E402
import transport.http as http_mod  # noqa: E402


class OAuthBridgePathTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.orig_root = http_mod.OAUTH_ROOT
        self.orig_config_dir = cli_main.config.CONFIG_DIR
        self.orig_upsert = cli_main.config.upsert_server
        http_mod.OAUTH_ROOT = self.tmp.name
        cli_main.config.CONFIG_DIR = self.tmp.name
        cli_main.config.upsert_server = lambda _name, _server: None

    def tearDown(self):
        http_mod.OAUTH_ROOT = self.orig_root
        cli_main.config.CONFIG_DIR = self.orig_config_dir
        cli_main.config.upsert_server = self.orig_upsert
        self.tmp.cleanup()

    def test_token_bridge_writes_flat_file_without_nested_directory(self):
        tokens = {"access_token": "redacted", "client_id": "client"}
        http_mod._save_oauth_tokens("server", tokens)

        path = os.path.join(self.tmp.name, "server.oauth.json")
        self.assertTrue(os.path.isfile(path))
        self.assertFalse(os.path.exists(os.path.join(self.tmp.name, "oauth")))
        self.assertEqual(http_mod._load_oauth_tokens("server"), tokens)
        self.assertEqual(stat.S_IMODE(os.stat(path).st_mode), 0o600)

    def test_token_bridge_reads_legacy_nested_file(self):
        legacy_dir = os.path.join(self.tmp.name, "oauth")
        os.makedirs(legacy_dir)
        tokens = {"access_token": "legacy", "client_id": "client"}
        with open(os.path.join(legacy_dir, "server.json"), "w", encoding="utf-8") as handle:
            json.dump(tokens, handle)

        self.assertEqual(http_mod._load_oauth_tokens("server"), tokens)

    def test_cli_secret_handoff_writes_flat_file(self):
        args = [
            "--name", "server",
            "--url", "https://example.test/mcp",
            "--oauth-client-id", "client",
            "--oauth-client-secret", "redacted-secret",
            "--oauth-auth-endpoint", "https://example.test/authorize",
            "--oauth-token-endpoint", "https://example.test/token",
        ]
        with contextlib.redirect_stdout(io.StringIO()):
            cli_main.cmd_add(args, pretty=False)

        path = os.path.join(self.tmp.name, "server.oauth.secret")
        self.assertTrue(os.path.isfile(path))
        self.assertFalse(os.path.exists(os.path.join(self.tmp.name, "oauth")))
        self.assertEqual(stat.S_IMODE(os.stat(path).st_mode), 0o600)


if __name__ == "__main__":
    unittest.main()
