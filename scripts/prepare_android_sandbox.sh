#!/usr/bin/env bash
# Prepare the Android sandbox assets:
#   Ubuntu 24.04 (noble) arm64 base tarball  →  assets/ubuntu-base.tar.gz
#
# [T-apk-size] The Android SDK host tools (aapt2/sdkmanager) are no longer
# bundled — minis-android-sdk-setup downloads them on demand in the guest.
#
# The tarball is Canonical's official ubuntu-base (glibc + apt + bash), not
# Alpine musl. arm64 packages live on ports.ubuntu.com.
#
# PRoot itself is still built by deps/build_proot.sh (NDK), not fetched here.
#
# Usage:
#   ./scripts/prepare_android_sandbox.sh
set -euo pipefail
[ -d "${TMPDIR:-}" ] || export TMPDIR=/tmp

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ASSETS="$ROOT/src/android/app/src/main/assets"
mkdir -p "$ASSETS"

UBUNTU_VERSION="${UBUNTU_VERSION:-24.04.3}"
UBUNTU_CODENAME="${UBUNTU_CODENAME:-noble}"
UBUNTU_URLS=(
  "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-${UBUNTU_VERSION}-base-arm64.tar.gz"
  "https://cdimage.ubuntu.com/ubuntu-base/releases/${UBUNTU_VERSION}/release/ubuntu-base-${UBUNTU_VERSION}-base-arm64.tar.gz"
  "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.2-base-arm64.tar.gz"
)

echo "==> Fetching Ubuntu ${UBUNTU_VERSION} (${UBUNTU_CODENAME}) arm64 base"
# Reuse an existing tarball: it is ~40 MB and the gzip integrity gate below
# rejects a truncated or corrupt one, so caching it is safe. Without this the
# one-click aarch64 build re-downloaded it on every single run.
if [ -s "$ASSETS/ubuntu-base.tar.gz" ] && [ -z "${UBUNTU_FORCE_DOWNLOAD:-}" ]; then
  echo "    reusing $ASSETS/ubuntu-base.tar.gz (set UBUNTU_FORCE_DOWNLOAD=1 to refetch)"
else
  TMP_UBUNTU="$(mktemp)"
  downloaded=0
  for url in "${UBUNTU_URLS[@]}"; do
    echo "    trying $url"
    if curl -fL --retry 3 -o "$TMP_UBUNTU" "$url"; then
      downloaded=1
      echo "    got $url"
      break
    fi
  done
  if [ "$downloaded" -ne 1 ]; then
    echo "ERROR: failed to download ubuntu-base arm64 tarball" >&2
    rm -f "$TMP_UBUNTU"
    exit 1
  fi
  mv "$TMP_UBUNTU" "$ASSETS/ubuntu-base.tar.gz"
fi
# Drop the previous Alpine asset so a dirty tree cannot ship both.
rm -f "$ASSETS/alpine-minirootfs.tar.gz" "$ASSETS/alpine-minirootfs.tar"

ls -lh "$ASSETS/ubuntu-base.tar.gz"

# ── Preinstall the runtime essentials INTO the base rootfs ──
#
# Stock ubuntu-base ships ONLY glibc + apt + bash. Everything else (python3,
# curl, ...) has been installed on-device by RootfsManager.seedNetworkTools at
# first boot — an apt run on whatever network the phone happens to have,
# racing the agent's own package-manager commands for the apt mutex. That is
# the reported failure chain: slow/regional mirror → seed stalls or fails →
# "python3: command not found" → agent retries apt → WAITING_RESOURCE queue
# messages for minutes. Baking the essentials into the tarball makes python3
# work out of the box and cuts first-boot apt traffic to just git/node.
#
# arm64-on-x86 needs binfmt; GitHub ubuntu runners ship qemu-user-static but
# install it explicitly anyway (idempotent). A failed preinstall must NOT
# fail the build — seedNetworkTools stays as the runtime fallback — but it
# must be LOUD (the review's lesson: silent runCatching hid dead features
# for weeks).
ROOTFS_PREINSTALL=(ca-certificates curl wget python3 python3-pip python3-venv python3-httpx unzip psmisc)
if [ "${ROOTFS_PREINSTALL_DISABLE:-}" = "1" ]; then
  echo "==> ROOTFS_PREINSTALL_DISABLE=1 — keeping the stock ubuntu-base"
else
  echo "==> Preinstalling essentials into the rootfs: ${ROOTFS_PREINSTALL[*]}"
  WORK="$(mktemp -d)"
  if ! tar -xzf "$ASSETS/ubuntu-base.tar.gz" -C "$WORK"; then
    echo "ERROR: ubuntu-base.tar.gz failed to unpack for preinstall" >&2
    rm -rf "$WORK"
    exit 1
  fi
  install_ok=0
  sudo apt-get install -y qemu-user-static binfmt-support >/dev/null 2>&1 || true
  if sudo cp /usr/bin/qemu-aarch64-static "$WORK/usr/bin/" 2>/dev/null; then
    sudo cp /etc/resolv.conf "$WORK/etc/resolv.conf"
    sudo mount --bind /dev "$WORK/dev" 2>/dev/null || true
    sudo mount -t proc proc "$WORK/proc" 2>/dev/null || true
    sudo mount --bind /sys "$WORK/sys" 2>/dev/null || true
    if sudo chroot "$WORK" /bin/sh -c '
        set -e
        export DEBIAN_FRONTEND=noninteractive
        apt-get update
        apt-get install -y --no-install-recommends '"${ROOTFS_PREINSTALL[*]}"'
        # ── [T-rootfs-slim] Runtime-useless bulk, python3 left INTACT ──
        # Every item below is dead weight the phone ships and never runs:
        #   /usr/lib/python3*/test     — the stdlib regression suite (~25 MB);
        #   idlelib / turtledemo       — graphical IDE/demos, no display here;
        #   /usr/share/{doc,man,info}  — per-package docs and manual pages;
        #   /usr/share/locale          — message catalogs beyond zh/en.
        # KEPT deliberately: __pycache__ (deleting it slows every first
        # python start into a full recompile), tzdata zoneinfo (python
        # datetime local-time lookups), lib2to3 main module (some tooling
        # imports it), all of site-packages/pip/venv/httpx.
        # Upgrade safety: for an EXISTING install the app keeps its already
        # extracted rootfs (no conflict); a re-extracted slim rootfs is
        # file-identical to what dpkg considers installed minus docs/tests,
        # which dpkg never reads at runtime — apt upgrade rewrites what a
        # package update actually needs. Fresh installs get the slim tree
        # directly.
        rm -rf /usr/lib/python3*/test /usr/lib/python3*/idlelib \
               /usr/lib/python3*/turtledemo
        rm -rf /usr/share/doc/* /usr/share/man /usr/share/info
        find /usr/share/locale -mindepth 1 -maxdepth 1 -type d \
          ! -name "zh_CN*" ! -name "en*" -exec rm -rf {} + 2>/dev/null || true
        # Smoke-assert what the runtime actually needs; a miss aborts the
        # chroot (set -e) and the build falls back to the stock base below.
        python3 -c "import httpx, ssl, venv; import sys; assert sys.version_info >= (3, 10)"
        apt-get clean
        rm -rf /var/lib/apt/lists/* /tmp/* /var/tmp/*
    '; then
      install_ok=1
    fi
    sudo umount -f "$WORK/dev" "$WORK/proc" "$WORK/sys" 2>/dev/null || true
    sudo rm -f "$WORK/etc/resolv.conf" "$WORK/usr/bin/qemu-aarch64-static"
  fi
  if [ "$install_ok" -ne 1 ]; then
    echo "WARNING: rootfs preinstall FAILED; shipping the stock ubuntu-base." >&2
    echo "         python3 will be installed on-device by seedNetworkTools as before." >&2
    sudo rm -rf "$WORK"
  else
    # The chroot's apt wrote root-owned files (etc/ssl/private is 0700 root);
    # a runner-user tar cannot read them — re-pack as root and hand the
    # artifact back so the later gzip integrity gate can read it.
    sudo tar -czf "$ASSETS/ubuntu-base.tar.gz" -C "$WORK" .
    sudo chown "$(id -un):$(id -gn)" "$ASSETS/ubuntu-base.tar.gz"
    sudo rm -rf "$WORK"
    echo "==> rootfs now carries the essentials out of the box:"
    ls -lh "$ASSETS/ubuntu-base.tar.gz"
  fi
fi

# Integrity gate for vendored blobs.
#
# These archives are committed to git verbatim from third-party releases and
# consumed at runtime by ZipInputStream. Nothing else in the pipeline checks
# them, and the failure is silent: RootfsManager catches the extraction error,
# deletes the partial output and retries once, then logs one line and carries on
# — shipping an APK with no on-device SDK tools at all. The 35.0.2 upstream
# asset really is corrupt this way (512 KiB inserted mid-file, EOCD never fixed
# up), so this is not hypothetical.
#
# Verdicts:
#   0 = usable as-is
#   1 = damaged but deterministically repairable -> repair in place, re-verify
#   2 = unrecoverable -> fatal when MINIS_REQUIRE_SDK_ASSETS=1
validate_zip_asset() {
  local zip="$1"; shift
  local required=("$@")

  if [ ! -s "$zip" ]; then
    echo "ERROR: $zip is missing or empty" >&2
    return 1
  fi

  local rc=0
  python3 "$ROOT/scripts/repair_vendor_zip.py" --verify --quiet "$zip" || rc=$?
  if [ "$rc" -eq 1 ]; then
    echo "==> $zip has shifted offsets; rebuilding it canonically"
    if ! python3 "$ROOT/scripts/repair_vendor_zip.py" --in-place "$zip"; then
      echo "ERROR: $zip could not be repaired" >&2
      return 1
    fi
    rc=0
    python3 "$ROOT/scripts/repair_vendor_zip.py" --verify --quiet "$zip" || rc=$?
  fi
  if [ "$rc" -ne 0 ]; then
    echo "ERROR: $zip failed integrity verification (rc=$rc)" >&2
    return 1
  fi

  # Readable is necessary but not sufficient — confirm the tools are in there,
  # and that a strict random-access reader agrees with our streaming one.
  local missing
  missing="$(python3 - "$zip" "${required[@]}" <<'PY'
import sys, zipfile
path, required = sys.argv[1], sys.argv[2:]
try:
    with zipfile.ZipFile(path) as zf:
        have = set(zf.namelist())
        bad = zf.testzip()
except Exception as exc:
    print("unopenable: %s" % exc); sys.exit(0)
if bad is not None:
    print("corrupt member: %s" % bad)
for name in required:
    if name not in have:
        print("missing member: %s" % name)
PY
)"
  if [ -n "$missing" ]; then
    echo "ERROR: $zip is not usable:" >&2
    echo "$missing" | sed 's/^/    /' >&2
    return 1
  fi
  return 0
}

ASSETS_OK=1

if [ -s "$ASSETS/ubuntu-base.tar.gz" ]; then
  # Same class of defect: an unvalidated blob that only fails at unpack time,
  # inside the guest, long after the build that embedded it succeeded.
  if ! gzip -t "$ASSETS/ubuntu-base.tar.gz" 2>/dev/null; then
    ASSETS_OK=0
    echo "ERROR: $ASSETS/ubuntu-base.tar.gz is not a valid gzip stream" >&2
  fi
else
  ASSETS_OK=0
  echo "WARNING: ubuntu-base.tar.gz is missing or empty" >&2
fi

if [ "$ASSETS_OK" -ne 1 ]; then
  if [ "${MINIS_REQUIRE_SDK_ASSETS:-0}" = "1" ]; then
    echo "ERROR: sandbox assets are incomplete and MINIS_REQUIRE_SDK_ASSETS=1." >&2
    echo "       The resulting APK would have no working on-device build tools." >&2
    exit 1
  fi
  echo "WARNING: sandbox assets incomplete; continuing because" \
    "MINIS_REQUIRE_SDK_ASSETS!=1. The APK will not be able to build on device." >&2
fi

echo "==> Android sandbox assets ready (Ubuntu ${UBUNTU_CODENAME})"
