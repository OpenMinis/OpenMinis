# Shared offline-first + resumable-download helper for dev-suite setup
# scripts. Source this file:
#   . /usr/local/lib/minis/offline.sh
#
# offline_pick <dest> <offline-name>
#   When /var/minis/shared/offline/<offline-name> exists and is non-empty,
#   copy it to <dest> and return 0 — the caller then skips the network
#   entirely. Returns 1 otherwise.
#
#   The offline directory is the host-visible shared folder
#   (/var/minis/shared, bind-mounted into the guest at the same path), so a
#   network-restricted user can drop payloads there from a file manager or
#   another device. See minis-devsuite-offline-list for the manifest.
#
# resume_fetch <dest> <name> <url> [<url>…]
#   Three-tier fetch with crash-resume:
#     1. offline copy (user-provided) — no network at all;
#     2. /var/minis/shared/cache/devsuite/<name>.part — curl -C - resumes
#        a download interrupted by an app kill / reboot / dead mirror.
#        The cache lives in shared storage, so it survives rootfs rebuilds
#        and app reinstalls. Mirrors serve identical bytes (they proxy the
#        same release artifacts), so a .part started on one mirror can be
#        resumed on another.
#     3. plain full download.
#   Returns 0 on success with the payload at <dest>; 1 when every mirror
#   failed (the .part is kept for the next attempt).
#
#   curl exit 33 (Range Not Satisfiable / unsupported) falls back to a
#   from-zero retry on the same mirror before moving on.
offline_pick() {
  _op_dest=$1
  _op_name=$2
  _op_src="/var/minis/shared/offline/$_op_name"
  if [ -s "$_op_src" ]; then
    echo "  using offline copy: $_op_src"
    rm -f "$_op_dest"
    cp "$_op_src" "$_op_dest"
    return 0
  fi
  return 1
}

resume_fetch() {
  _rf_dest=$1
  _rf_name=$2
  shift 2
  if offline_pick "$_rf_dest" "$_rf_name"; then
    return 0
  fi
  _rf_dir=/var/minis/shared/cache/devsuite
  _rf_part="$_rf_dir/$_rf_name.part"
  mkdir -p "$_rf_dir" 2>/dev/null || _rf_part="/tmp/$_rf_name.part"
  for _rf_url in "$@"; do
    [ -n "$_rf_url" ] || continue
    if [ -s "$_rf_part" ]; then
      echo "  resuming $_rf_name from $(( $(wc -c < "$_rf_part") )) bytes via $_rf_url"
    else
      echo "  downloading $_rf_url"
    fi
    # -C - resumes when the .part exists; on a fresh name it is a plain GET.
    if curl -fL -C - --retry 1 --connect-timeout 12 --speed-time 30 \
         --speed-limit 100 -o "$_rf_part" "$_rf_url"; then
      cp "$_rf_part" "$_rf_dest"
      return 0
    fi
    _rf_rc=$?
    if [ "$_rf_rc" = "33" ]; then
      # Server ignored the Range header — restart this mirror from zero.
      rm -f "$_rf_part"
      if curl -fL --retry 1 --connect-timeout 12 --speed-time 30 \
           --speed-limit 100 -o "$_rf_part" "$_rf_url"; then
        cp "$_rf_part" "$_rf_dest"
        return 0
      fi
    fi
    # Keep the .part: the next mirror (or the next run) resumes from it.
    echo "  failed $_rf_url (rc=$_rf_rc)" >&2
  done
  rm -f "$_rf_dest"
  return 1
}
