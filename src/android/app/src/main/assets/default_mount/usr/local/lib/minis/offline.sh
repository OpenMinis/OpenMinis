# Shared offline-first + resumable-download + integrity helper for the
# dev-suite setup scripts. Source this file:
#   . /usr/local/lib/minis/offline.sh
#
# Trust model (read this before relaxing anything):
#
#   * Every payload is downloaded to execute-as-root / extract into the
#     app-private rootfs that later participates in the user's builds, so a
#     substituted byte is code execution. Where the publisher ships a digest
#     it is MANDATORY: minis_verify_payload fails closed and the caller
#     refuses to extract. Verification cannot be turned off.
#   * The URL list a caller passes to resume_fetch MUST be ordered
#     origin-first. When no published digest exists for a payload, only the
#     first URL (the publisher's own host, TLS-authenticated) is used; the
#     anonymous GitHub proxies (ghfast.top / ghproxy.net / mirror.ghproxy.com)
#     and CN mirrors are skipped, because without a digest nothing detects a
#     body they swapped.
#   * /var/minis/shared/offline/ lives in the guest's writable shared area —
#     any command the agent runs can plant a file there. An offline payload
#     is therefore used ONLY when the user explicitly opted in with
#     MINIS_ALLOW_OFFLINE=1 (Settings -> Environment Variables, which is
#     user-set state, not guest-writable) AND the file matches a known
#     published digest (catalogue, MINIS_DEVSUITE_SHA256, or the digest the
#     caller resolved from the publisher). No digest -> the offline copy is
#     rejected.
#   * The resumable cache (.part files under /var/minis/shared/cache/devsuite)
#     is deleted as soon as a payload completes AND verifies, so nothing
#     unverified is retained, and it is size-capped / aged out below.
#
# resume_fetch <dest> <name> <url> [<url>…]
#   offline copy (opt-in, digest-checked) -> resumable cache -> plain fetch.
#   Returns 0 with the payload at <dest>; 1 when every source failed.
# offline_pick <dest> <name>   — offline tier only (see rule above).
# minis_verify_payload <path> <name>
#   0 = digest matched, 1 = mismatch (caller MUST discard the payload),
#   2 = no digest known for <name> (caller decides; offline paths reject).
# minis_expected_digest <name> — "algo hex" ("" when unknown).
# minis_cache_prune           — keep the resume cache under its cap.

MINIS_OFFLINE_DIR="${MINIS_OFFLINE_DIR:-/var/minis/shared/offline}"
MINIS_CACHE_DIR="${MINIS_CACHE_DIR:-/var/minis/shared/cache/devsuite}"
# Bounded so a resumed multi-GB download can never fill the volume by itself.
MINIS_CACHE_MAX_BYTES="${MINIS_CACHE_MAX_BYTES:-2147483648}"   # 2 GiB
MINIS_CACHE_MAX_AGE_MIN=$((60 * 24 * 30))                      # 30 days

minis_sha256_file() { _ms_path=$1; sha256sum "$_ms_path" 2>/dev/null | cut -d' ' -f1; }
minis_sha1_file()   { _ms_path=$1; sha1sum "$_ms_path" 2>/dev/null | cut -d' ' -f1; }

# Digests the app itself publishes for the payloads pinned in the recipes.
# Values are taken from the publisher's own metadata:
#   * Google: dl.google.com/android/repository/repository2-3.xml <checksum type="sha1">
#     (that repository only publishes SHA-1 per archive — it is the publisher's
#     own digest, which is what matters here; the file names are versioned and
#     immutable).
#   * Flutter: flutter_infra_release/releases/releases_linux.json ships a
#     sha256 per archive; minis-setup-flutter resolves it at run time instead
#     of pinning it here, so a FLUTVER override stays covered.
#   * rustup / CMake: rustup-init.sha256 and cmake-<ver>-SHA-256.txt are
#     fetched from the origin host by the scripts that use them.
#   * lzhiyong/android-sdk-tools, lzhiyong/termux-ndk and skylot/jadx publish
#     NO digest. Those downloads run origin-only (github.com over TLS) and the
#     proxy mirrors are disabled unless the user pins a digest themselves.
minis_catalog_digest() {
  case "$1" in
    commandlinetools-linux-11076708_latest.zip)
      echo "sha1 d313adb7aedccf6cf0cfca51ec180f0059f5f8f8" ;;
    platform-36_r02.zip)
      echo "sha1 2c1a80dd4d9f7d0e6dd336ec603d9b5c55a6f576" ;;
    platform-35_r02.zip)
      echo "sha1 0bb560a90a7a2cbd0dd8348224d518b638fe7949" ;;
    *) echo "" ;;
  esac
}

# User-supplied digests: MINIS_DEVSUITE_SHA256="name=hex;name=hex" (set in
# Settings -> Environment Variables). Lets an offline-only user attest a
# payload the catalogue does not cover, without opening the shared drop
# directory to whatever happens to be sitting in it.
minis_user_digest() {
  [ -n "${MINIS_DEVSUITE_SHA256:-}" ] || return 0
  echo "$MINIS_DEVSUITE_SHA256" | tr ';' '\n' | grep "^$1=" | head -1 | cut -d= -f2
}

# Resolution order: catalogue -> user list -> what the caller resolved from
# the publisher (_MINIS_EXPECTED, "algo hex"; set by the script right before
# it calls resume_fetch, never read from a guest-writable file).
minis_expected_digest() {
  _me_got=$(minis_catalog_digest "$1")
  [ -n "$_me_got" ] || _me_got=$(minis_user_digest "$1")
  # A bare hex from the user list is always treated as sha256 (the catalogue
  # entries carry their own "algo hex" prefix).
  case "$_me_got" in
    [0-9a-fA-F]*) _me_got="sha256 $_me_got" ;;
  esac
  if [ -z "$_me_got" ]; then _me_got="${_MINIS_EXPECTED:-}"; fi
  printf '%s\n' "$_me_got"
}

minis_verify_payload() {
  _mv_expected=$(minis_expected_digest "$2")
  if [ -z "$_mv_expected" ]; then
    echo "  no published checksum for $2 — origin host only, mirrors disabled" >&2
    return 2
  fi
  _mv_algo=${_mv_expected%% *}
  _mv_hex=${_mv_expected##* }
  case "$_mv_algo" in
    sha256) _mv_got=$(minis_sha256_file "$1") ;;
    sha1)   _mv_got=$(minis_sha1_file "$1") ;;
    *) echo "  unknown digest algorithm $_mv_algo for $2 — refusing" >&2; return 1 ;;
  esac
  if [ -n "$_mv_got" ] && [ "$_mv_got" = "$_mv_hex" ]; then
    echo "  checksum ok: $2 ($_mv_algo)"
    return 0
  fi
  echo "  CHECKSUM MISMATCH for $2: got ${_mv_got:-unreadable}, expected $_mv_hex — payload discarded" >&2
  return 1
}

# Cap + age the resume cache. Payload names contain no spaces (see
# minis-devsuite-offline-list), so word-splitting the listing is safe here.
minis_cache_prune() {
  [ -d "$MINIS_CACHE_DIR" ] || return 0
  find "$MINIS_CACHE_DIR" -type f -mmin "+$MINIS_CACHE_MAX_AGE_MIN" -delete 2>/dev/null || true
  _mc_total=$(du -sb "$MINIS_CACHE_DIR" 2>/dev/null | cut -f1)
  [ -n "$_mc_total" ] || return 0
  if [ "$_mc_total" -gt "$MINIS_CACHE_MAX_BYTES" ]; then
    echo "  devsuite cache $((_mc_total / 1048576)) MiB over $((MINIS_CACHE_MAX_BYTES / 1048576)) MiB — evicting oldest"
    for _mc_f in $(ls -tr "$MINIS_CACHE_DIR" 2>/dev/null); do
      _mc_total=$(du -sb "$MINIS_CACHE_DIR" 2>/dev/null | cut -f1)
      [ -z "$_mc_total" ] && break
      [ "$_mc_total" -le "$MINIS_CACHE_MAX_BYTES" ] && break
      rm -f "$MINIS_CACHE_DIR/$_mc_f"
    done
  fi
}

offline_pick() {
  _op_dest=$1
  _op_name=$2
  _op_src="$MINIS_OFFLINE_DIR/$_op_name"
  if [ ! -s "$_op_src" ]; then
    return 1
  fi
  # [T-devsuite-offline-consent] The shared directory is writable by every
  # command the agent runs in the guest, so a file found there is attacker-
  # controlled until the user says otherwise. Two gates, both required:
  # MINIS_ALLOW_OFFLINE=1 (user-set env) and a matching published digest.
  if [ "${MINIS_ALLOW_OFFLINE:-0}" != "1" ]; then
    echo "  ignoring $_op_src: offline imports are opt-in (set MINIS_ALLOW_OFFLINE=1 in Settings -> Environment Variables)" >&2
    return 1
  fi
  minis_verify_payload "$_op_src" "$_op_name"
  _op_rc=$?
  if [ "$_op_rc" != "0" ]; then
    echo "  offline copy REJECTED ($_op_name): it must match a published checksum (catalogue or MINIS_DEVSUITE_SHA256); rc=$_op_rc" >&2
    return 1
  fi
  echo "  using offline copy: $_op_src"
  rm -f "$_op_dest"
  cp "$_op_src" "$_op_dest"
  return 0
}

resume_fetch() {
  _rf_dest=$1
  _rf_name=$2
  shift 2
  if offline_pick "$_rf_dest" "$_rf_name"; then
    return 0
  fi
  _rf_expected=$(minis_expected_digest "$_rf_name")
  _rf_dir="$MINIS_CACHE_DIR"
  _rf_part="$_rf_dir/$_rf_name.part"
  mkdir -p "$_rf_dir" "$MINIS_OFFLINE_DIR" 2>/dev/null || _rf_part="/tmp/$_rf_name.part"
  minis_cache_prune
  _rf_first=1
  for _rf_url in "$@"; do
    [ -n "$_rf_url" ] || continue
    if [ -z "$_rf_expected" ] && [ "$_rf_first" != "1" ]; then
      echo "  skipping $_rf_url: no published checksum for $_rf_name, a proxy mirror could substitute it undetected" >&2
      continue
    fi
    _rf_first=0
    if [ -s "$_rf_part" ]; then
      echo "  resuming $_rf_name from $(( $(wc -c < "$_rf_part") )) bytes via $_rf_url"
    else
      echo "  downloading $_rf_url"
    fi
    # -C - resumes when the .part exists; on a fresh name it is a plain GET.
    if curl -fL -C - --retry 1 --connect-timeout 12 --speed-time 30 \
         --speed-limit 100 -o "$_rf_part" "$_rf_url"; then
      minis_verify_payload "$_rf_part" "$_rf_name"
      _rf_vrc=$?
      if [ "$_rf_vrc" = "1" ]; then
        # Poisoned (or cross-mirror spliced) body: drop the cache so the next
        # mirror starts clean instead of resuming a substituted tail.
        rm -f "$_rf_part"
        echo "  failed $_rf_url (checksum)" >&2
        continue
      fi
      cp "$_rf_part" "$_rf_dest"
      # Completed (and, when a digest exists, verified): the payload is at
      # <dest>, so the cache copy is pure disk usage — remove it.
      rm -f "$_rf_part"
      return 0
    fi
    _rf_rc=$?
    if [ "$_rf_rc" = "33" ]; then
      # Server ignored the Range header — restart this mirror from zero.
      rm -f "$_rf_part"
      if curl -fL --retry 1 --connect-timeout 12 --speed-time 30 \
           --speed-limit 100 -o "$_rf_part" "$_rf_url"; then
        minis_verify_payload "$_rf_part" "$_rf_name"
        _rf_vrc=$?
        if [ "$_rf_vrc" = "1" ]; then
          rm -f "$_rf_part"
          echo "  failed $_rf_url (checksum)" >&2
          continue
        fi
        cp "$_rf_part" "$_rf_dest"
        rm -f "$_rf_part"
        return 0
      fi
    fi
    # Incomplete transfer: keep the .part so the next mirror (reachable only
    # when a digest exists, which catches the splice on completion) or the
    # next run resumes from it.
    echo "  failed $_rf_url (rc=$_rf_rc)" >&2
  done
  rm -f "$_rf_dest"
  return 1
}
