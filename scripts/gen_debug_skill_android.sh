#!/usr/bin/env bash
# Stage the debug-server skill + an Android-flavored reference client into the
# Android DEBUG-ONLY asset source set (src/debug/assets/debug-skill/), so the
# debug server can serve them unauthenticated over GET /skill.
#
# Why src/debug/assets and not src/main/assets: assets under src/main ship in
# RELEASE APKs too. The debug server itself is BuildConfig.DEBUG-gated, so its
# documentation must not be baked into a release artifact. AGP merges
# src/debug/assets only for debug variants — no extra Gradle config needed.
#
# NOTE ON REACHABILITY: app/build.gradle.kts currently disables every `debug`
# variant via androidComponents.beforeVariants, and CI builds release only, so
# the merge*DebugAssets tasks this script is wired to never fire in the default
# configuration. See the "调试变体已停用" note in BUILDING.md. Re-enabling a
# debug variant is what makes this generator run again.
#
# This is the only debug-skill generator in the tree. The iOS counterpart
# (scripts/gen_debug_skill.sh, which base64-embedded the same SKILL.md into a
# generated DebugSkillGenerated.swift under the non-existent src/ios) was
# removed as dead: this repo has no iOS project and no Xcode build phase to run
# it. The CLIENT differs anyway — Android's debug server uses plaintext JSON-RPC
# + an optional X-Minis-Token header, NOT the iOS protocol-v1 encrypted
# envelope, so shipping the iOS envelope clients here would actively mislead.
#
# DO NOT COMMIT the output (src/debug/ is gitignored).
set -eu

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SKILL_DIR="$REPO_ROOT/.claude/skills/debug-server"
OUT_DIR="$REPO_ROOT/src/android/app/src/debug/assets/debug-skill"

mkdir -p "$OUT_DIR/examples"

# 1. The manual itself, verbatim.
if [ -f "$SKILL_DIR/SKILL.md" ]; then
  cp -f "$SKILL_DIR/SKILL.md" "$OUT_DIR/SKILL.md"
else
  printf '# debug-server skill unavailable\n\nSKILL.md was not found at build time.\n' > "$OUT_DIR/SKILL.md"
fi

# 2. An Android-specific Python client (stdlib only). Deliberately NOT the iOS
#    envelope clients: Android needs plaintext JSON-RPC + optional token.
cat > "$OUT_DIR/examples/minis_rpc_android.py" <<'PYCLIENT'
#!/usr/bin/env python3
"""Minis ANDROID debug server client (stdlib only).

Usage:
    python3 minis_rpc_android.py [--host HOST:PORT] [--token TOKEN] <method> [params-json]

Examples:
    adb forward tcp:5321 tcp:5321
    python3 minis_rpc_android.py debug.appInfo '{}'
    python3 minis_rpc_android.py --host 10.0.0.5:5321 --token "$TOK" debug.logs.list '{}'

Transport notes (Android differs from iOS!):
  - Port is 5321 (iOS uses 8321).
  - Payloads are PLAINTEXT JSON-RPC 2.0 — there is no encrypted envelope and no
    /pair step. Do not use the iOS protocol-v1 clients against this server.
  - Auth: loopback (via `adb forward`) needs no token. NON-loopback (LAN)
    clients must send the per-install device token:
        adb shell run-as com.openminis.app cat files/debug_server_token
    Passed as X-Minis-Token (Authorization: Bearer also accepted).
    Or set MINIS_DEBUG_TOKEN in the environment.
"""

import json
import os
import sys
import urllib.error
import urllib.request


def call(host: str, method: str, params: dict, token: str | None) -> dict:
    body = json.dumps({
        "jsonrpc": "2.0", "id": 1, "method": method, "params": params,
    }).encode()
    headers = {"Content-Type": "application/json"}
    if token:
        headers["X-Minis-Token"] = token
    req = urllib.request.Request(f"http://{host}/", data=body,
                                headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            return json.loads(r.read())
    except urllib.error.HTTPError as e:
        payload = e.read().decode(errors="replace")
        if e.code == 401:
            raise SystemExit(
                "401 Unauthorized — this is a non-loopback (LAN) connection, so a token is\n"
                "required. Read it with:\n"
                "  adb shell run-as com.openminis.app cat files/debug_server_token\n"
                "then pass --token / MINIS_DEBUG_TOKEN. (Over `adb forward` no token is needed.)\n"
                f"server said: {payload}"
            )
        raise SystemExit(f"HTTP {e.code}: {payload}")


def main() -> None:
    args = sys.argv[1:]
    host = "localhost:5321"
    token = os.environ.get("MINIS_DEBUG_TOKEN")
    while args and args[0].startswith("--"):
        if args[0] == "--host":
            host = args[1]; args = args[2:]
        elif args[0] == "--token":
            token = args[1]; args = args[2:]
        else:
            raise SystemExit(f"unknown flag {args[0]}")
    if not args:
        raise SystemExit(__doc__)
    method = args[0]
    params = json.loads(args[1]) if len(args) > 1 else {}
    print(json.dumps(call(host, method, params, token), indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
PYCLIENT

# 3. A curl crib sheet — the zero-dependency path.
cat > "$OUT_DIR/examples/curl.md" <<'CURLDOC'
# Android debug server — curl quickstart

Port **5321** (iOS uses 8321). Plaintext JSON-RPC 2.0; no /pair, no envelope.

```bash
# Loopback via adb — no token needed
adb forward tcp:5321 tcp:5321
curl -s localhost:5321/ -d '{"jsonrpc":"2.0","id":1,"method":"debug.appInfo","params":{}}'

# LAN (non-loopback) — token required
TOK=$(adb shell run-as com.openminis.app cat files/debug_server_token)
curl -s http://<device-ip>:5321/ \
     -H "X-Minis-Token: $TOK" \
     -d '{"jsonrpc":"2.0","id":1,"method":"debug.appInfo","params":{}}'
```

Fetch this skill off the device (no auth on loopback):

```bash
curl -H 'Accept: text/markdown' localhost:5321/skill        # the manual
curl localhost:5321/skill                                   # JSON + inlined clients
curl localhost:5321/skill/examples/python > minis_rpc_android.py
```
CURLDOC

echo "[gen_debug_skill_android] staged skill assets → $OUT_DIR"
