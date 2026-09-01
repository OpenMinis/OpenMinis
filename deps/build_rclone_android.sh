#!/usr/bin/env bash
# Build rclone as an Android .aar via gomobile.
#
# Android is the EASIER of the two platforms here: rclone ships an official
# gomobile binding (librclone/gomobile) and upstream documents this exact
# command. iOS is the one upstream has not tested.
#
# One deviation from upstream's command: their gomobile package hardcodes
# `backend/all` (all 70 backends). We bind ./gomobile instead, which re-exports
# the same API against the trimmed backend list in backends/backends.go.
#
# Output: deps/build/rclone/rclone.aar
# Consume it by copying the .so into src/android/app/src/main/jniLibs/arm64-v8a/
# (the same place libproot.so lives), or by importing the .aar directly.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT/deps/rclone-mobile"
BUILD="$ROOT/deps/build/rclone"

command -v go >/dev/null || { echo "error: go toolchain not found" >&2; exit 1; }
command -v gomobile >/dev/null || {
  echo "error: gomobile not installed. Run:" >&2
  echo "  go install golang.org/x/mobile/cmd/gomobile@latest" >&2
  echo "  gomobile init" >&2
  exit 1
}
[ -n "${ANDROID_NDK_HOME:-}${ANDROID_HOME:-}" ] || {
  echo "error: set ANDROID_NDK_HOME (or ANDROID_HOME) first" >&2; exit 1; }

mkdir -p "$BUILD"
cd "$SRC"

# arm64-v8a only, matching the app's existing ndk abiFilters.
gomobile bind -v \
  -target=android/arm64 \
  -androidapi 24 \
  -javapkg=com.openminis.rclone \
  -ldflags="-s -w" \
  -o "$BUILD/rclone.aar" \
  ./gomobile

echo "==> $BUILD/rclone.aar"
du -sh "$BUILD/rclone.aar" | awk '{print "    size:", $1}'
