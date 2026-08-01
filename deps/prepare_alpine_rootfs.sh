#!/bin/bash
set -e

# ============================================================================
# Alpine Linux aarch64 Rootfs Preparation Script for iSH-AOK
# ============================================================================
# This script downloads Alpine Linux minirootfs (aarch64) and converts it to
# iSH's fakefs format for use as a sandboxed ARM64 Linux environment.
#
# Repository: https://github.com/emkey1/ish-AOK (default branch)
#
# Prerequisites:
#   - Python 3 with meson (pip3 install meson)
#   - Ninja (brew install ninja)
#   - libarchive (brew install libarchive)
#
# Usage:
#   ./prepare_alpine_rootfs.sh [version]
#
# Output:
#   deps/resources/alpine-rootfs/  - fakefs formatted rootfs
# ============================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ISH_DIR="$SCRIPT_DIR/ish"
OUTPUT_DIR="$SCRIPT_DIR/resources"
CACHE_DIR="$SCRIPT_DIR/.cache"

# Alpine configuration - aarch64 for ARM64 emulation
ALPINE_VERSION="${1:-3.21}"
ALPINE_MINOR="0"
ALPINE_ARCH="aarch64"  # ARM64 for iSH-ARM64 emulation
ALPINE_MIRROR="https://dl-cdn.alpinelinux.org/alpine"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log_info() {
    echo -e "${BLUE}ℹ️  $1${NC}"
}

log_success() {
    echo -e "${GREEN}✅ $1${NC}"
}

log_warning() {
    echo -e "${YELLOW}⚠️  $1${NC}"
}

log_error() {
    echo -e "${RED}❌ $1${NC}"
    exit 1
}

# ============================================================================
# Check Prerequisites
# ============================================================================
check_prerequisites() {
    log_info "Checking prerequisites..."

    if ! command -v curl &> /dev/null; then
        log_error "curl is required"
    fi

    if ! command -v meson &> /dev/null; then
        log_error "Meson is required. Install with: pip3 install meson"
    fi

    if ! command -v ninja &> /dev/null; then
        log_error "Ninja is required. Install with: brew install ninja"
    fi

    # Check for libarchive
    LIBARCHIVE_FOUND=0
    for dir in "/opt/homebrew/opt/libarchive" "/usr/local/opt/libarchive" "/usr"; do
        if [ -f "$dir/lib/libarchive.dylib" ] || [ -f "$dir/lib/libarchive.a" ] || [ -f "$dir/lib/libarchive.so" ]; then
            LIBARCHIVE_FOUND=1
            break
        fi
    done

    if [ $LIBARCHIVE_FOUND -eq 0 ]; then
        log_error "libarchive is required. Install with: brew install libarchive"
    fi

    log_success "Prerequisites check passed"
}

# ============================================================================
# Download Alpine minirootfs
# ============================================================================
download_alpine() {
    log_info "Downloading Alpine Linux $ALPINE_VERSION.$ALPINE_MINOR for $ALPINE_ARCH..."

    mkdir -p "$CACHE_DIR"

    local ROOTFS_FILE="alpine-minirootfs-${ALPINE_VERSION}.${ALPINE_MINOR}-${ALPINE_ARCH}.tar.gz"
    local ROOTFS_PATH="$CACHE_DIR/$ROOTFS_FILE"
    local ROOTFS_URL="${ALPINE_MIRROR}/v${ALPINE_VERSION}/releases/${ALPINE_ARCH}/${ROOTFS_FILE}"

    if [ -f "$ROOTFS_PATH" ]; then
        log_info "Using cached rootfs: $ROOTFS_FILE"
    else
        log_info "Downloading from $ROOTFS_URL"
        curl -L -o "$ROOTFS_PATH" "$ROOTFS_URL" --progress-bar

        if [ ! -f "$ROOTFS_PATH" ]; then
            log_error "Failed to download rootfs"
        fi
    fi

    log_success "Alpine rootfs ready: $(du -h "$ROOTFS_PATH" | cut -f1)"
}

# ============================================================================
# Build fakefsify tool
# ============================================================================
build_fakefsify() {
    log_info "Building fakefsify tool..."

    local BUILD_DIR="$ISH_DIR/build-native"

    # Check if already built
    if [ -x "$BUILD_DIR/tools/fakefsify" ]; then
        log_info "fakefsify already built"
        return
    fi

    mkdir -p "$BUILD_DIR"
    cd "$ISH_DIR"

    # Configure for native build (not cross-compile)
    if [ ! -f "$BUILD_DIR/build.ninja" ]; then
        log_info "Configuring native meson build..."
        meson setup "$BUILD_DIR" \
            --buildtype=release \
            -Dlog="" \
            -Dkernel=ish \
            -Dengine=jit \
            -Dguest_archs=arm64
    fi

    # Build only fakefsify
    log_info "Building fakefsify..."
    ninja -C "$BUILD_DIR" tools/fakefsify

    if [ ! -x "$BUILD_DIR/tools/fakefsify" ]; then
        log_error "Failed to build fakefsify"
    fi

    cd "$SCRIPT_DIR"
    log_success "fakefsify built successfully"
}

# ============================================================================
# Create fakefs rootfs
# ============================================================================
create_fakefs() {
    log_info "Creating fakefs rootfs..."

    local ROOTFS_FILE="alpine-minirootfs-${ALPINE_VERSION}.${ALPINE_MINOR}-${ALPINE_ARCH}.tar.gz"
    local ROOTFS_PATH="$CACHE_DIR/$ROOTFS_FILE"
    local FAKEFSIFY="$ISH_DIR/build-native/tools/fakefsify"
    local OUTPUT_ROOTFS="$OUTPUT_DIR/alpine-rootfs"

    # Remove existing rootfs
    if [ -d "$OUTPUT_ROOTFS" ]; then
        log_info "Removing existing rootfs..."
        rm -rf "$OUTPUT_ROOTFS"
    fi

    mkdir -p "$OUTPUT_DIR"

    # Convert to fakefs format
    log_info "Converting rootfs to fakefs format..."
    "$FAKEFSIFY" "$ROOTFS_PATH" "$OUTPUT_ROOTFS"

    if [ ! -d "$OUTPUT_ROOTFS/data" ] || [ ! -f "$OUTPUT_ROOTFS/meta.db" ]; then
        log_error "Failed to create fakefs rootfs"
    fi

    log_success "Fakefs rootfs created"
}

# ============================================================================
# Configure rootfs for iSH
# ============================================================================
configure_rootfs() {
    log_info "Configuring rootfs for iSH..."

    local ROOTFS_DATA="$OUTPUT_DIR/alpine-rootfs/data"

    # Create necessary directories
    mkdir -p "$ROOTFS_DATA/dev"
    mkdir -p "$ROOTFS_DATA/proc"
    mkdir -p "$ROOTFS_DATA/sys"
    mkdir -p "$ROOTFS_DATA/tmp"
    mkdir -p "$ROOTFS_DATA/run"
    mkdir -p "$ROOTFS_DATA/root"
    mkdir -p "$ROOTFS_DATA/home"

    # Configure /etc/passwd - set root shell
    if [ -f "$ROOTFS_DATA/etc/passwd" ]; then
        # Ensure root has /bin/sh as shell
        sed -i.bak 's|^root:.*|root:x:0:0:root:/root:/bin/sh|' "$ROOTFS_DATA/etc/passwd"
        rm -f "$ROOTFS_DATA/etc/passwd.bak"
    fi

    # Configure /etc/profile for better shell experience
    cat >> "$ROOTFS_DATA/etc/profile" << 'EOF'

# MinisApp iSH Configuration
export PS1='\u@minis:\w\$ '
export TERM=xterm-256color
export HOME=/root
export LANG=C.UTF-8
export CHARSET=UTF-8
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/opt/bin

# Aliases
alias ll='ls -la'
alias la='ls -A'
alias l='ls -CF'
alias grep='grep --color=auto'

cd ~
EOF

    # Create /etc/motd
    cat > "$ROOTFS_DATA/etc/motd" << 'EOF'

  __  __ _       _        _    _ _
 |  \/  (_)_ __ (_)___   | |  (_) |_ ___
 | |\/| | | '_ \| / __|  | |  | | __/ _ \
 | |  | | | | | | \__ \  | |__| | ||  __/
 |_|  |_|_|_| |_|_|___/  |____|_|\__\___|

 Welcome to MinisApp Linux Shell
 Alpine Linux aarch64 on iSH-ARM64 Emulator

EOF

    # Configure /etc/inittab for single user mode (iSH handles tty)
    cat > "$ROOTFS_DATA/etc/inittab" << 'EOF'
::sysinit:/sbin/openrc sysinit
::sysinit:/sbin/openrc boot
::wait:/sbin/openrc default
::ctrlaltdel:/sbin/reboot
::shutdown:/sbin/openrc shutdown
EOF

    # Configure resolv.conf for DNS
    cat > "$ROOTFS_DATA/etc/resolv.conf" << 'EOF'
nameserver 8.8.8.8
nameserver 8.8.4.4
EOF

    # Configure APK repositories
    cat > "$ROOTFS_DATA/etc/apk/repositories" << EOF
https://dl-cdn.alpinelinux.org/alpine/v${ALPINE_VERSION}/main
https://dl-cdn.alpinelinux.org/alpine/v${ALPINE_VERSION}/community
EOF

    log_success "Rootfs configured"
}

# ============================================================================
# Create ZIP Archive
# ============================================================================
create_zip_archive() {
    log_info "Creating ZIP archive..."

    local ROOTFS_DIR="$OUTPUT_DIR/alpine-rootfs"
    local ZIP_FILE="$OUTPUT_DIR/alpine-rootfs.zip"

    # Remove existing zip
    rm -f "$ZIP_FILE"

    # Create zip (exclude WAL files)
    cd "$OUTPUT_DIR"
    zip -r "alpine-rootfs.zip" "alpine-rootfs" \
        -x "*.db-shm" \
        -x "*.db-wal" \
        > /dev/null

    cd "$SCRIPT_DIR"

    if [ ! -f "$ZIP_FILE" ]; then
        log_error "Failed to create ZIP archive"
    fi

    log_success "ZIP archive created: $(du -h "$ZIP_FILE" | cut -f1)"
}

# ============================================================================
# Print Summary
# ============================================================================
print_summary() {
    local ROOTFS_DIR="$OUTPUT_DIR/alpine-rootfs"
    local ZIP_FILE="$OUTPUT_DIR/alpine-rootfs.zip"

    echo ""
    echo "============================================================"
    echo -e "${GREEN}🎉 Alpine Rootfs Preparation Complete!${NC}"
    echo "============================================================"
    echo ""
    echo "Output files:"
    echo "  📁 $ROOTFS_DIR"
    echo "  📦 $ZIP_FILE"
    echo ""
    echo "Sizes:"
    echo "  Data:     $(du -sh "$ROOTFS_DIR/data" | cut -f1)"
    echo "  Database: $(du -h "$ROOTFS_DIR/meta.db" | cut -f1)"
    echo "  ZIP:      $(du -h "$ZIP_FILE" | cut -f1)"
    echo ""
    echo "To use in MinisApp:"
    echo "  1. Add alpine-rootfs.zip to Xcode project resources"
    echo "  2. Extract to Documents on first launch"
    echo "  3. Use mount_root(&fakefs, path_to_data)"
    echo ""
    echo "============================================================"
}

# ============================================================================
# Clean
# ============================================================================
clean() {
    log_info "Cleaning..."

    rm -rf "$OUTPUT_DIR/alpine-rootfs"
    rm -rf "$ISH_DIR/build-native"
    rm -rf "$CACHE_DIR"

    log_success "Clean completed"
}

# ============================================================================
# Main
# ============================================================================
main() {
    echo ""
    echo "============================================================"
    echo "  Alpine Linux aarch64 Rootfs Preparation for iSH-ARM64"
    echo "  Version: $ALPINE_VERSION.$ALPINE_MINOR ($ALPINE_ARCH)"
    echo "============================================================"
    echo ""

    check_prerequisites
    download_alpine
    build_fakefsify
    create_fakefs
    configure_rootfs
    create_zip_archive
    print_summary
}

# Parse arguments
case "${1:-}" in
    clean)
        clean
        exit 0
        ;;
    --help|-h)
        echo "Usage: $0 [version|clean]"
        echo ""
        echo "Arguments:"
        echo "  version    Alpine version (default: 3.21)"
        echo "  clean      Remove all generated files"
        echo ""
        echo "Examples:"
        echo "  $0           # Use default version 3.21"
        echo "  $0 3.18      # Use Alpine 3.18"
        echo "  $0 clean     # Clean all files"
        exit 0
        ;;
    *)
        main
        ;;
esac
