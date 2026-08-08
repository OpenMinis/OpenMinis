#!/bin/bash
# fetch_and_patch.sh — fetch Chromium @ Blinker Fluid base commit, overlay the
# Blinker Fluid patch set, apply the OpenMinis embed guards, and hook the
# blink_bridge into content/shell/BUILD.gn.
#
# Usage: ./fetch_and_patch.sh <work_dir> <blinker_fluid_dir>
#   work_dir         - where chromium/ will be created (needs ~80GB free)
#   blinker_fluid_dir- checkout of https://github.com/Ssabal/blinker-fluid
set -euo pipefail

WORK="${1:?work_dir required}"
BF="${2:?blinker_fluid_dir required}"

CHROMIUM_BASE="31dce68b925c2b8efc93df832a86a7c0d03e3fa2"
BRIDGE_SRC="$(cd "$(dirname "$0")/.." && pwd)/chromium-blink"

echo "==> work: $WORK"
mkdir -p "$WORK"
cd "$WORK"

if [ ! -d depot_tools ]; then
  echo "==> cloning depot_tools"
  git clone https://chromium.googlesource.com/chromium/tools/depot_tools.git
fi
export PATH="$WORK/depot_tools:$PATH"
"$WORK/depot_tools/update_depot_tools" >/dev/null 2>&1 || true
# Make depot_tools available to later workflow steps.
if [ -n "${GITHUB_PATH:-}" ]; then
  echo "$WORK/depot_tools" >> "$GITHUB_PATH"
fi

if [ ! -d chromium/src/.git ]; then
  mkdir -p chromium
  cat > chromium/.gclient <<EOF
solutions = [
  {
    "name": "src",
    "url": "https://chromium.googlesource.com/chromium/src.git@${CHROMIUM_BASE}",
    "managed": False,
    "custom_deps": {},
    "custom_vars": {},
  },
]
target_os = ["ios"]
target_os_only = True
EOF
  echo "==> gclient sync (20-40 min, shallow)"
  cd chromium
  gclient sync --no-history --nohooks -j8 --shallow
  cd ..
fi

cd chromium/src
echo "==> verifying base commit"
ACTUAL=$(git rev-parse HEAD)
echo "    HEAD=$ACTUAL (expected ${CHROMIUM_BASE})"

echo "==> gclient runhooks"
gclient runhooks

echo "==> overlaying Blinker Fluid patch set"
cp -R "$BF/src/." ./

echo "==> copying blink_bridge into content/shell/bridge/"
mkdir -p content/shell/bridge
cp "$BRIDGE_SRC/bridge/blink_bridge.h" content/shell/bridge/
cp "$BRIDGE_SRC/bridge/blink_bridge.mm" content/shell/bridge/

echo "==> applying embed guards (python, string-exact)"
python3 - <<'PYEOF'
import io, sys

def patch(path, old, new, count=1):
    with io.open(path, encoding="utf-8") as f:
        text = f.read()
    if old not in text:
        print(f"!! PATCH ANCHOR MISSING: {path}\n   looking for: {old[:80]!r}")
        sys.exit(1)
    text = text.replace(old, new, count)
    with io.open(path, "w", encoding="utf-8") as f:
        f.write(text)
    print(f"ok: {path}")

# 1. ShellBrowserMainParts: skip auto window/tab creation in embed mode.
patch(
    "content/shell/browser/shell_browser_main_parts.cc",
    """void ShellBrowserMainParts::InitializeMessageLoopContext() {
#if BUILDFLAG(IS_IOS)
  // Restore the tabs from last session if there are any (crash-guarded). The
""",
    """void ShellBrowserMainParts::InitializeMessageLoopContext() {
#if BUILDFLAG(IS_IOS)
  // Blink embed mode (host app such as OpenMinis drives shell creation via the
  // blink_bridge C API): never auto-create windows or restore tabs here.
  if (base::CommandLine::ForCurrentProcess()->HasSwitch("blink-embed")) {
    return;
  }
  // Restore the tabs from last session if there are any (crash-guarded). The
""",
)

# 2. content/shell/BUILD.gn: add blink_bridge to the framework's deps.
patch(
    "content/shell/BUILD.gn",
    """      ":content_shell_app",
      ":content_shell_lib",
""",
    """      ":content_shell_app",
      ":content_shell_lib",
      ":blink_bridge",
""",
)

# 3. content/shell/BUILD.gn: append the blink_bridge source_set.
with io.open("content/shell/BUILD.gn", encoding="utf-8") as f:
    text = f.read()
text += """
# OpenMinis embed bridge: C API exposing Blink shells to a host app.
source_set("blink_bridge") {
  testonly = true
  sources = [
    "bridge/blink_bridge.h",
    "bridge/blink_bridge.mm",
  ]
  deps = [
    ":content_shell_lib",
    "//base",
    "//content/public/browser",
    "//net",
    "//third_party/blink/public/common",
    "//ui/gfx",
    "//url",
  ]
}
"""
with io.open("content/shell/BUILD.gn", "w", encoding="utf-8") as f:
    f.write(text)
print("ok: content/shell/BUILD.gn (appended blink_bridge source_set)")

print("ALL PATCHES APPLIED")
PYEOF

echo "==> gn gen"
mkdir -p out/Release-iphoneos
cp "$BRIDGE_SRC/build_args.gn" out/Release-iphoneos/args.gn

# ccache: big speedup for CI iterations (Chromium objects cached across runs).
if command -v ccache >/dev/null 2>&1; then
  echo 'cc_wrapper = "ccache"' >> out/Release-iphoneos/args.gn
  export CCACHE_DIR="${CCACHE_DIR:-$HOME/.cache/ccache}"
  mkdir -p "$CCACHE_DIR"
  ccache -M 6G >/dev/null 2>&1 || true
  echo "==> ccache enabled (dir=$CCACHE_DIR)"
else
  echo "==> ccache not found — building without it"
fi

gn gen out/Release-iphoneos

echo "==> done. Build with: autoninja -C out/Release-iphoneos content_shell"
