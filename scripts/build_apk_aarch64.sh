#!/usr/bin/env bash
# One-click aarch64 APK: PRoot + libunwind.a + Gradle assembleRelease.
# Signing: Gradle uses src/android/release.keystore via signing.properties
# by default. MINIS_UPLOAD_* env overrides. Debug keystore is last-resort
# (UpdateChecker cannot replace a differently-signed install).
set -euo pipefail
[ -d "${TMPDIR:-}" ] || export TMPDIR=/tmp
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [[ -z "${ANDROID_HOME:-}${ANDROID_SDK_ROOT:-}" ]]; then
  echo "Set ANDROID_HOME or ANDROID_SDK_ROOT" >&2
  exit 1
fi
sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"

# deps/proot is a git submodule and build_proot.sh needs its sources. A plain
# `git clone` leaves it empty and the failure only surfaces deep inside that
# script, so resolve it here where "one-click" is the promise.
if [[ -d "$ROOT/.git" && ! -f "$ROOT/deps/proot/src/GNUmakefile" ]]; then
  echo "==> initialising git submodules (deps/proot)"
  git -C "$ROOT" submodule update --init --recursive deps/proot
fi

# Extra -P flags for the Gradle invocation, collected by the host-arch checks
# below. Kept as an array so paths with spaces survive.
GRADLE_EXTRA_ARGS=()

# Invoke the helpers with `bash` and gate on -f, NOT -x. A checkout that lost
# the executable bit (restrictive umask, an archive extracted without
# permissions, an editor that rewrites the file) used to make both guards fail
# silently: proot was never built and the sandbox assets were never prepared,
# yet the build still produced an APK. It installs, the sandbox even starts —
# and then every guest command comes back "[Shell not running] (exit code: -1)".
# A missing helper should be loud, not skippable.
if [[ -f "$ROOT/deps/build_proot.sh" ]]; then
  bash "$ROOT/deps/build_proot.sh"
else
  echo "ERROR: missing $ROOT/deps/build_proot.sh (is the deps/proot submodule initialised?)" >&2
  exit 1
fi

# The sandbox assets are embedded in the APK, so an incomplete set is a build
# failure, not a warning: swallowing it here is exactly how APKs shipped with no
# working on-device aapt2. prepare_android_sandbox.sh validates every blob and
# repairs a vendored archive whose offsets were shifted upstream.
# Set MINIS_REQUIRE_SDK_ASSETS=0 to build anyway (offline, Kotlin-only work).
if [[ -f "$ROOT/scripts/prepare_android_sandbox.sh" ]]; then
  MINIS_REQUIRE_SDK_ASSETS="${MINIS_REQUIRE_SDK_ASSETS:-1}" \
    bash "$ROOT/scripts/prepare_android_sandbox.sh"
else
  echo "ERROR: missing $ROOT/scripts/prepare_android_sandbox.sh" >&2
  exit 1
fi

# Resolve the pinned NDK before CMake/Gradle so crash_handler can link _Unwind_*.
# Do not pick an existing r28 tree or "the highest" side-by-side install.
PINNED_NDK_REV="29.0.14206865"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "$NDK" ]]; then
  if [[ -d "$sdk_root/ndk/$PINNED_NDK_REV" ]]; then
    NDK="$sdk_root/ndk/$PINNED_NDK_REV"
  fi
fi
if [[ -z "$NDK" || ! -d "$NDK" ]]; then
  echo "ERROR: Android NDK $PINNED_NDK_REV not found (set ANDROID_NDK_HOME or sdkmanager \"ndk;$PINNED_NDK_REV\")" >&2
  exit 1
fi
ndk_rev=""
if [[ -f "$NDK/source.properties" ]]; then
  ndk_rev=$(awk -F= '/Pkg.Revision/ { gsub(/[ \t]/, "", $2); print $2; exit }' "$NDK/source.properties")
fi
[[ -n "$ndk_rev" ]] || ndk_rev=$(basename "$NDK")
if [[ "$ndk_rev" != "$PINNED_NDK_REV" ]]; then
  echo "ERROR: refusing NDK $NDK (revision ${ndk_rev:-unknown}); need $PINNED_NDK_REV" >&2
  exit 1
fi

if [[ ! -f "$ROOT/scripts/build_libunwind_aarch64.sh" ]]; then
  echo "ERROR: missing $ROOT/scripts/build_libunwind_aarch64.sh" >&2
  exit 1
fi
echo "==> 交叉编译并安装 libunwind.a -> $NDK sysroot"
bash "$ROOT/scripts/build_libunwind_aarch64.sh" "$NDK"

# 本机 NDK 预编译目录名仍是 linux-x86_64，但 clang++ 可能是包装脚本
# （调用系统 clang-18）。包装脚本若未带 -lunwind，链接期找不到刚装的 .a。
ensure_clangxx_lunwind() {
  local ndk="$1"
  local pre="$ndk/toolchains/llvm/prebuilt"
  [[ -d "$pre" ]] || return 0
  local f
  while IFS= read -r -d '' f; do
    [[ -f "$f" && ! -L "$f" ]] || continue
    if ! head -n 1 "$f" | grep -q bash; then
      continue
    fi
    if grep -q -- '-lunwind' "$f"; then
      continue
    fi
    if ! grep -q -- '-rtlib=compiler-rt' "$f"; then
      continue
    fi
    echo "==> 给包装脚本追加 -lunwind: $f"
    sed -i 's/-rtlib=compiler-rt/-rtlib=compiler-rt -lunwind/g' "$f"
  done < <(find "$pre" \( -name 'clang++' -o -name '*-clang++' \) -print0 2>/dev/null)
}
ensure_clangxx_lunwind "$NDK"

GRADLEW="$ROOT/src/android/gradlew"
if [[ ! -f "$GRADLEW" ]]; then
  echo "missing $GRADLEW" >&2
  exit 1
fi

host_arch="$(uname -m)"

# ---------------------------------------------------------------------------
# local.properties: sdk.dir (+ cmake.dir, resolved below). Deliberately NOT
# ndk.dir.
#
# ndk.dir is deprecated — AGP emits CXX5106 for it and intends to remove it.
# The project already pins ndkVersion and AGP resolves $sdk/ndk/<rev> from that
# revision's own source.properties, so ndk.dir buys nothing and only adds noise.
# A stale one is dropped. What the NDK does need is to be reachable *inside* the
# SDK, so when ANDROID_NDK_HOME points elsewhere we link it in rather than
# resurrecting the deprecated property.
#
# local.properties is gitignored, so writing it is expected; only these keys are
# touched and any other content is preserved.
# ---------------------------------------------------------------------------
LOCAL_PROPS="$ROOT/src/android/local.properties"
set_local_prop() {
  local key="$1" val="$2" tmp
  touch "$LOCAL_PROPS"
  tmp="$(mktemp)"
  grep -v "^${key}=" "$LOCAL_PROPS" > "$tmp" || true
  printf '%s=%s\n' "$key" "$val" >> "$tmp"
  mv "$tmp" "$LOCAL_PROPS"
}
drop_local_prop() {
  local key="$1" tmp
  [[ -f "$LOCAL_PROPS" ]] || return 0
  tmp="$(mktemp)"
  grep -v "^${key}=" "$LOCAL_PROPS" > "$tmp" || true
  mv "$tmp" "$LOCAL_PROPS"
}
set_local_prop sdk.dir "$sdk_root"
drop_local_prop ndk.dir
if [[ "$NDK" != "$sdk_root/ndk/"* ]]; then
  ndk_link="$sdk_root/ndk/$PINNED_NDK_REV"
  if [[ ! -e "$ndk_link" ]]; then
    echo "==> NDK lives outside the SDK ($NDK); linking it to $ndk_link"
    mkdir -p "$sdk_root/ndk"
    ln -sfn "$NDK" "$ndk_link"
  fi
fi

# ---------------------------------------------------------------------------
# aarch64 host: stop AGP fetching an x86_64 aapt2
#
# AGP resolves aapt2 from Maven as a *host-arch* artifact and Google publishes
# linux-x86_64 only. On an arm64 host the binary cannot exec, and the failure
# surfaces as "AAPT2 Daemon startup failed" / "Bundled aapt2 not found" with no
# mention of architecture — which sends you debugging the wrong layer entirely.
# x86_64 hosts (including CI) are left completely untouched.
# ---------------------------------------------------------------------------
if [[ "$host_arch" == "aarch64" || "$host_arch" == "arm64" ]]; then
  aapt2_runs() { [[ -x "$1" ]] && "$1" version >/dev/null 2>&1; }
  AAPT2=""
  # 1. an SDK build-tools aapt2 that actually runs on this host
  while IFS= read -r cand; do
    [[ -n "$cand" ]] || continue
    if aapt2_runs "$cand"; then AAPT2="$cand"; break; fi
  done < <(ls -1d "$sdk_root"/build-tools/*/aapt2 2>/dev/null | sort -rV)
  # 2. the static aarch64 aapt2 this repo used to vendor as an APK asset
  #    ([T-apk-size] no longer bundled — kept as a local-build fallback for
  #    checkouts that still carry the zip)
  if [[ -z "$AAPT2" ]]; then
    vend="$ROOT/src/android/app/src/main/assets/android-sdk-tools-aarch64.zip"
    if [[ -f "$vend" ]]; then
      dest="${TMPDIR:-/tmp}/minis-vendored-aapt2"
      mkdir -p "$dest"
      if python3 - "$vend" "$dest" <<'PY'
import os, sys, zipfile
src, dest = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(src) as z:
    out = os.path.join(dest, "aapt2")
    with open(out, "wb") as fh:
        fh.write(z.read("build-tools/aapt2"))
    os.chmod(out, 0o755)
PY
      then :; fi
      aapt2_runs "$dest/aapt2" && AAPT2="$dest/aapt2"
    fi
  fi
  if [[ -n "$AAPT2" ]]; then
    echo "==> aarch64 host: overriding AGP's x86_64 aapt2 with $AAPT2"
    GRADLE_EXTRA_ARGS+=("-Pandroid.aapt2FromMavenOverride=$AAPT2")
  else
    echo "ERROR: no runnable aarch64 aapt2 on PATH, in $sdk_root/build-tools, or in" >&2
    echo "       the vendored asset zip. AGP would fetch an x86_64 binary and fail." >&2
    exit 1
  fi
fi

# ---------------------------------------------------------------------------
# CMake: resolve it ourselves and hand it to AGP via cmake.dir.
#
# AGP enumerates SDK CMake packages through their package.xml. A CMake unpacked
# by hand (Kitware tarball) or dropped in by minis-android-sdk-setup has no
# package.xml, so AGP cannot see it. With an *exact* `3.22.1` DSL pin AGP then
# probes $sdk/cmake/3.22.1 by directory name and — failing that — tries to
# download cmake;3.22.1, which Google publishes for x86_64 only. That is the
# trap: on an arm64 host the exact pin silently depends on one specific
# hand-built directory existing.
#
# app/build.gradle.kts therefore asks for "3.22.1+", which makes AGP accept an
# explicitly resolved CMake. Measured: with the exact pin AGP *ignores*
# cmake.dir and insists on a directory literally named 3.22.1; with "3.22.1+"
# it honours cmake.dir.
#
# AGP also wants a ninja next to the cmake it uses, and no aarch64 CMake
# distribution ships one (Kitware's cmake-3.22.1-linux-aarch64.tar.gz has no
# bin/ninja at all), so provision it from the system package rather than
# failing at configure time with a message about a missing generator.
# ---------------------------------------------------------------------------
MIN_CMAKE="3.22.1"
cmake_at_least_min() {
  [[ -n "$1" ]] || return 1
  [[ "$(printf '%s\n%s\n' "$MIN_CMAKE" "$1" | sort -V | head -1)" == "$MIN_CMAKE" ]]
}
cmake_revision() {
  "$1" --version 2>/dev/null | head -1 | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1
}
resolve_sdk_cmake() {
  local sys_ninja d rev
  sys_ninja="$(command -v ninja || true)"
  if [[ -n "${MINIS_CMAKE_DIR:-}" && -x "${MINIS_CMAKE_DIR}/bin/cmake" ]]; then
    printf '%s' "${MINIS_CMAKE_DIR%/}"; return 0
  fi
  while IFS= read -r d; do
    d="${d%/}"
    [[ -x "$d/bin/cmake" ]] || continue
    rev="$(cmake_revision "$d/bin/cmake")"
    cmake_at_least_min "$rev" || continue
    if [[ ! -e "$d/bin/ninja" ]]; then
      [[ -n "$sys_ninja" ]] || continue
      echo "==> $d has no ninja; linking $sys_ninja" >&2
      ln -sf "$sys_ninja" "$d/bin/ninja"
    fi
    if [[ ! -f "$d/source.properties" ]]; then
      printf 'Pkg.UserSrc=false\nPkg.Revision=%s\n' "$rev" > "$d/source.properties" 2>/dev/null || true
    fi
    printf '%s' "$d"; return 0
  done < <(ls -1d "$sdk_root"/cmake/*/ 2>/dev/null | sort -rV)
  return 1
}

if CMAKE_DIR="$(resolve_sdk_cmake)"; then
  echo "==> using SDK CMake at $CMAKE_DIR"
  set_local_prop cmake.dir "$CMAKE_DIR"
else
  # No SDK CMake qualifies. AGP will take cmake/ninja from PATH, which is fine
  # as long as they are new enough — just say so instead of letting the version
  # be chosen by accident.
  drop_local_prop cmake.dir
  sys_cmake="$(command -v cmake || true)"
  sys_ninja="$(command -v ninja || true)"
  if [[ -n "$sys_cmake" && -n "$sys_ninja" ]]; then
    sys_rev="$(cmake_revision "$sys_cmake")"
    if cmake_at_least_min "$sys_rev"; then
      echo "==> no SDK CMake >= $MIN_CMAKE; using system cmake $sys_rev from PATH"
    else
      echo "ERROR: system cmake $sys_rev is older than the required $MIN_CMAKE" >&2
      exit 1
    fi
  else
    echo "ERROR: no usable CMake >= $MIN_CMAKE with a ninja alongside it." >&2
    echo "       Install one (apt install cmake ninja-build), or sdkmanager \"cmake;3.22.1\"," >&2
    echo "       or set MINIS_CMAKE_DIR to a CMake root containing bin/cmake." >&2
    exit 1
  fi
fi

# Invoked through bash for the same reason as the helpers above: the executable
# bit is not guaranteed to survive checkout.
bash "$GRADLEW" -p "$ROOT/src/android" assembleRelease --no-daemon \
  ${GRADLE_EXTRA_ARGS[@]+"${GRADLE_EXTRA_ARGS[@]}"}

OUT="$ROOT/dist"
mkdir -p "$OUT"
APK=$(find "$ROOT/src/android/app/build/outputs/apk/release" -name "*.apk" | head -n 1)
if [[ -n "$APK" ]]; then
  cp -f "$APK" "$OUT/openminis-aarch64.apk"
  echo "APK: $OUT/openminis-aarch64.apk ($(wc -c < "$APK") bytes)"
fi
