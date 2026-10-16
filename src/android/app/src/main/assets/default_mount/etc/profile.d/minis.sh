# MinisApp shell configuration
# Loaded by /etc/profile via the profile.d mechanism (login shells only).

# T294: prompt parity with iOS — `root@minis:/var/minis#`. iOS bakes the
# literal "minis" into PS1 (deps/prepare_alpine_rootfs.sh) rather than
# relying on \h, so the prompt is stable regardless of what /etc/hostname
# happens to contain. We do the same on Android so a fresh install
# matches without needing a rootfs rebuild.
export PS1='\u@minis-linux:\w\$ '

# Enable bash command history with arrow keys
export HISTFILE="$HOME/.bash_history"
export SHELL=/bin/bash
export DEBIAN_FRONTEND=noninteractive
export HISTSIZE=1000

# Interactive bash reads ~/.bashrc; keep ENV for dash leftovers.
export ENV="$HOME/.bashrc"

# Default pager — less is standard on Alpine; keep explicit for scripts
# that probe $PAGER.
export PAGER=less

# URL interception: $BROWSER is also seeded directly into every process
# envp via PRootKernel.customEnvironment so non-login shells (which never
# source profile.d) still see it. The xdg-open / sensible-browser /
# www-browser / x-www-browser / gnome-open / kde-open wrappers live as
# real files in default_mount/usr/local/bin/ and are overlaid on every
# boot.
export BROWSER=/usr/local/bin/minis-open

# T222: PRoot's link2symlink extension creates .l2s.* sentinel files alongside
# every hardlinked file. uv's default `hardlink` mode tries to re-link these
# sentinels when populating site-packages, which PRoot rejects with EPERM.
# Force uv to symlink package files instead — the sentinels are then never
# touched as link sources. Reported as openminis/openminis#7.
export UV_LINK_MODE=symlink

# Android leaks TMPDIR=/data/user/0/<pkg>/cache into the guest. That path is
# not a PRoot guest directory, so dpkg/apt/curl fail creating temp files.
export TMPDIR=/tmp
export TMP=/tmp
export TEMP=/tmp

# TLS: Android-injected bundle at /etc/ssl/certs/ca-certificates.crt
export SSL_CERT_FILE="${SSL_CERT_FILE:-/etc/ssl/certs/ca-certificates.crt}"
export SSL_CERT_DIR="${SSL_CERT_DIR:-/etc/ssl/certs}"
export CURL_CA_BUNDLE="${CURL_CA_BUNDLE:-$SSL_CERT_FILE}"
export REQUESTS_CA_BUNDLE="${REQUESTS_CA_BUNDLE:-$SSL_CERT_FILE}"
export GIT_SSL_CAINFO="${GIT_SSL_CAINFO:-$SSL_CERT_FILE}"
export PIP_CERT="${PIP_CERT:-$SSL_CERT_FILE}"
export NODE_EXTRA_CA_CERTS="${NODE_EXTRA_CA_CERTS:-$SSL_CERT_FILE}"
# No address-space limit here: RLIMIT_AS belongs to the host and this is not
# fatal if it is already clamped. See profile.d/minis-limits.sh.
export NODE_OPTIONS="${NODE_OPTIONS:+$NODE_OPTIONS }--max-old-space-size=192"

# Toolchain paths (populated by minis-dev-setup / minis-android-sdk-setup).
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
# build-tools and cmake live in *versioned* subdirectories, and which versions
# exist depends on what the setup CLIs managed to fetch (cmake 3.31.6 normally,
# 3.22.1 as a fallback; build-tools 34.0.0 and/or 35.0.2). Pinning one revision
# here left a dead PATH entry — cmake/ninja/aapt2 then appeared "not installed"
# even though they were. Resolve whatever is present, highest version first.
_minis_path_add() {
  if [ -d "$1" ]; then PATH="$PATH:$1"; fi
  return 0
}
for _bt in $(ls -1d "$ANDROID_HOME"/build-tools/*/ 2>/dev/null | sort -rV); do
  _minis_path_add "${_bt%/}"
done
for _cm in $(ls -1d "$ANDROID_HOME"/cmake/*/bin/ 2>/dev/null | sort -rV); do
  _minis_path_add "${_cm%/}"
done
unset _bt _cm
export PATH="$PATH:/opt/bin:/opt/node-global/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:/opt/gradle/bin"
unset -f _minis_path_add
for _jdk in /usr/lib/jvm/java-21-openjdk-arm64 /usr/lib/jvm/java-17-openjdk-arm64 /usr/lib/jvm/default-java; do
  if [ -d "$_jdk" ]; then
    export JAVA_HOME="$_jdk"
    export PATH="$JAVA_HOME/bin:$PATH"
    break
  fi
done
unset _jdk
