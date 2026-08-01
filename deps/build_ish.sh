#!/bin/bash
set -e

# ============================================================================
# iSH-AOK Build Script for iOS Static Library
# ============================================================================
# This script builds libish, libish_emu, and libfakefs as static libraries
# for iOS (arm64) integration, using the ish-AOK fork which emulates an
# ARM64 Linux guest on iOS.
#
# Repository: https://github.com/emkey1/ish-AOK (default branch)
#
# Prerequisites:
#   - Xcode with iOS SDK
#   - Python 3 with meson (pip3 install meson)
#   - Ninja (brew install ninja)
#   - LLVM/Clang (brew install llvm) - required for VDSO compilation
#   - libarchive (brew install libarchive)
#
# Usage:
#   ./build_ish.sh [clean|debug|release]
#
# Output:
#   deps/libs/      - Static libraries (.a files)
#   deps/include/   - Header files
#   deps/resources/ - VDSO and other resources
# ============================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ISH_DIR="$SCRIPT_DIR/ish"
OUTPUT_LIBS="$SCRIPT_DIR/libs"
OUTPUT_INCLUDE="$SCRIPT_DIR/include"
OUTPUT_RESOURCES="$SCRIPT_DIR/resources"

# Build configuration
BUILD_TYPE="${1:-release}"
ARCHS="arm64"
IOS_DEPLOYMENT_TARGET="14.0"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

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

    # Check for Python 3
    if ! command -v python3 &> /dev/null; then
        log_error "Python 3 is required. Install with: brew install python3"
    fi

    # Check for meson
    if ! command -v meson &> /dev/null; then
        log_error "Meson is required. Install with: pip3 install meson"
    fi

    # Check for ninja
    if ! command -v ninja &> /dev/null; then
        log_error "Ninja is required. Install with: brew install ninja"
    fi

    # Check for Xcode
    if ! xcode-select -p &> /dev/null; then
        log_error "Xcode command line tools are required. Install with: xcode-select --install"
    fi

    # Check for LLVM (needed for VDSO)
    LLVM_CLANG=""
    for clang_path in "/opt/homebrew/opt/llvm/bin/clang" "/usr/local/opt/llvm/bin/clang" "/opt/local/bin/clang"; do
        if [ -x "$clang_path" ]; then
            LLVM_CLANG="$clang_path"
            break
        fi
    done

    if [ -z "$LLVM_CLANG" ]; then
        log_warning "LLVM Clang not found. VDSO may not build correctly."
        log_warning "Install with: brew install llvm"
    else
        log_info "Found LLVM Clang: $LLVM_CLANG"
    fi

    log_success "Prerequisites check passed"
}

# ============================================================================
# Initialize Submodules
# ============================================================================
init_submodules() {
    log_info "Initializing iSH submodules..."

    cd "$ISH_DIR"

    if [ ! -d "deps/libapps/.git" ] && [ ! -f "deps/libapps/.git" ]; then
        git submodule update --init --recursive
        log_success "Submodules initialized"
    else
        log_info "Submodules already initialized"
    fi

    cd "$SCRIPT_DIR"
}

# ============================================================================
# Clean Build
# ============================================================================
clean_build() {
    log_info "Cleaning build artifacts..."

    rm -rf "$ISH_DIR/build-ios"
    rm -rf "$OUTPUT_LIBS"
    rm -rf "$OUTPUT_INCLUDE"
    rm -rf "$OUTPUT_RESOURCES"

    log_success "Clean completed"
}

# ============================================================================
# Setup Cross Compilation
# ============================================================================
setup_cross_compile() {
    log_info "Setting up iOS cross-compilation..."

    BUILD_DIR="$ISH_DIR/build-ios"
    mkdir -p "$BUILD_DIR"

    # Get iOS SDK path
    IOS_SDK=$(xcrun --sdk iphoneos --show-sdk-path)

    # Create cross-compilation file for meson
    CROSS_FILE="$BUILD_DIR/ios-cross.txt"

    cat > "$CROSS_FILE" << EOF
[binaries]
c = ['clang', '-arch', 'arm64', '-isysroot', '$IOS_SDK', '-miphoneos-version-min=$IOS_DEPLOYMENT_TARGET']
ar = 'ar'
strip = 'strip'
pkg-config = 'false'

[host_machine]
system = 'darwin'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'

[built-in options]
c_args = []
c_link_args = ['-L$IOS_SDK/usr/lib']

[properties]
needs_exe_wrapper = true
sys_root = '$IOS_SDK'
library_dirs = ['$IOS_SDK/usr/lib']
EOF

    log_success "Cross-compilation file created"
}

# ============================================================================
# Build iSH Libraries
# ============================================================================
build_ish() {
    log_info "Building iSH libraries for iOS ($BUILD_TYPE)..."

    BUILD_DIR="$ISH_DIR/build-ios"
    CROSS_FILE="$BUILD_DIR/ios-cross.txt"

    cd "$ISH_DIR"

    # Configure meson build
    MESON_BUILDTYPE="release"
    if [ "$BUILD_TYPE" == "debug" ]; then
        MESON_BUILDTYPE="debug"
    fi

    # Check if already configured
    if [ ! -f "$BUILD_DIR/build.ninja" ]; then
        log_info "Configuring meson build..."

        meson setup "$BUILD_DIR" \
            --cross-file "$CROSS_FILE" \
            --buildtype="$MESON_BUILDTYPE" \
            -Dlog="" \
            -Dlog_handler=nslog \
            -Dkernel=ish \
            -Dengine=jit \
            -Dguest_archs=arm64
    else
        log_info "Meson already configured, reconfiguring..."
        meson configure "$BUILD_DIR" --buildtype="$MESON_BUILDTYPE"
    fi

    # Build libraries
    log_info "Building with ninja..."
    ninja -C "$BUILD_DIR" libish.a libish_emu.a libfakefs.a

    # Also build VDSO (arm64 guest VDSO is at vdso/arm64/libvdso.so.elf)
    log_info "Building VDSO..."
    ninja -C "$BUILD_DIR" vdso/arm64/libvdso.so.elf || log_warning "VDSO build failed (may need LLVM)"

    cd "$SCRIPT_DIR"
    log_success "iSH libraries built successfully"
}

# ============================================================================
# Copy Output Files
# ============================================================================
copy_outputs() {
    log_info "Copying output files..."

    BUILD_DIR="$ISH_DIR/build-ios"

    # Create output directories
    mkdir -p "$OUTPUT_LIBS"
    mkdir -p "$OUTPUT_INCLUDE/ish"
    mkdir -p "$OUTPUT_INCLUDE/ish/emu"
    mkdir -p "$OUTPUT_INCLUDE/ish/kernel"
    mkdir -p "$OUTPUT_INCLUDE/ish/fs"
    mkdir -p "$OUTPUT_INCLUDE/ish/fs/proc"
    mkdir -p "$OUTPUT_INCLUDE/ish/util"
    mkdir -p "$OUTPUT_INCLUDE/ish/platform"
    mkdir -p "$OUTPUT_INCLUDE/ish/asbestos"
    mkdir -p "$OUTPUT_RESOURCES"

    # Copy static libraries
    log_info "Copying libraries..."
    cp "$BUILD_DIR/libish.a" "$OUTPUT_LIBS/"
    cp "$BUILD_DIR/libish_emu.a" "$OUTPUT_LIBS/"
    cp "$BUILD_DIR/libfakefs.a" "$OUTPUT_LIBS/"

    # Copy VDSO if built (arm64 guest VDSO path)
    if [ -f "$BUILD_DIR/vdso/arm64/libvdso.so.elf" ]; then
        cp "$BUILD_DIR/vdso/arm64/libvdso.so.elf" "$OUTPUT_RESOURCES/"
        log_success "VDSO copied"
    elif [ -f "$BUILD_DIR/vdso/libvdso.so.elf" ]; then
        cp "$BUILD_DIR/vdso/libvdso.so.elf" "$OUTPUT_RESOURCES/"
        log_success "VDSO copied"
    fi

    # Copy header files
    log_info "Copying header files..."

    # Root headers
    cp "$ISH_DIR/debug.h" "$OUTPUT_INCLUDE/ish/"
    cp "$ISH_DIR/misc.h" "$OUTPUT_INCLUDE/ish/"
    cp "$ISH_DIR/xX_main_Xx.h" "$OUTPUT_INCLUDE/ish/"

    # EMU headers
    cp "$ISH_DIR"/emu/*.h "$OUTPUT_INCLUDE/ish/emu/"

    # Kernel headers
    cp "$ISH_DIR"/kernel/*.h "$OUTPUT_INCLUDE/ish/kernel/"

    # FS headers
    cp "$ISH_DIR"/fs/*.h "$OUTPUT_INCLUDE/ish/fs/"
    if [ -d "$ISH_DIR/fs/proc" ]; then
        cp "$ISH_DIR"/fs/proc/*.h "$OUTPUT_INCLUDE/ish/fs/proc/" 2>/dev/null || true
    fi

    # Util headers
    cp "$ISH_DIR"/util/*.h "$OUTPUT_INCLUDE/ish/util/" 2>/dev/null || true

    # Platform headers
    cp "$ISH_DIR"/platform/*.h "$OUTPUT_INCLUDE/ish/platform/"

    # Asbestos headers
    cp "$ISH_DIR"/asbestos/*.h "$OUTPUT_INCLUDE/ish/asbestos/"
    # ARM64 guest gadgets
    mkdir -p "$OUTPUT_INCLUDE/ish/asbestos/guest-arm64/gadgets-aarch64"
    cp "$ISH_DIR"/asbestos/guest-arm64/gadgets-aarch64/*.h "$OUTPUT_INCLUDE/ish/asbestos/guest-arm64/gadgets-aarch64/" 2>/dev/null || true
    cp "$ISH_DIR"/asbestos/gadgets-generic.h "$OUTPUT_INCLUDE/ish/asbestos/" 2>/dev/null || true

    # ARM64 emu headers
    mkdir -p "$OUTPUT_INCLUDE/ish/emu/arch/arm64"
    cp "$ISH_DIR"/emu/arch/arm64/*.h "$OUTPUT_INCLUDE/ish/emu/arch/arm64/" 2>/dev/null || true

    # ARM64 kernel arch headers
    mkdir -p "$OUTPUT_INCLUDE/ish/kernel/arch/arm64"
    cp "$ISH_DIR"/kernel/arch/arm64/*.h "$OUTPUT_INCLUDE/ish/kernel/arch/arm64/" 2>/dev/null || true

    # Copy generated headers if exist
    if [ -f "$BUILD_DIR/cpu-offsets.h" ]; then
        cp "$BUILD_DIR/cpu-offsets.h" "$OUTPUT_INCLUDE/ish/"
    fi

    # Deps config
    if [ -f "$ISH_DIR/deps/config.h" ]; then
        mkdir -p "$OUTPUT_INCLUDE/ish/deps"
        cp "$ISH_DIR/deps/config.h" "$OUTPUT_INCLUDE/ish/deps/"
    fi

    # Copy RootfsPatch bundle (rootfs overlay patches applied on boot)
    if [ -d "$ISH_DIR/app/RootfsPatch.bundle" ]; then
        cp -r "$ISH_DIR/app/RootfsPatch.bundle" "$OUTPUT_RESOURCES/"
        log_success "RootfsPatch.bundle copied"
    fi

    log_success "Output files copied"
}

# ============================================================================
# Create Umbrella Header
# ============================================================================
create_umbrella_header() {
    log_info "Creating umbrella header..."

    cat > "$OUTPUT_INCLUDE/ish/ish.h" << 'EOF'
/*
 * iSH-ARM64 - Linux shell for iOS (ARM64 guest emulation)
 * Umbrella header for static library integration
 *
 * https://github.com/emkey1/ish-AOK
 */

#ifndef ISH_H
#define ISH_H

#include "misc.h"
#include "debug.h"

// Kernel
#include "kernel/init.h"
#include "kernel/task.h"
#include "kernel/calls.h"
#include "kernel/fs.h"
#include "kernel/memory.h"
#include "kernel/signal.h"
#include "kernel/errno.h"

// File System
#include "fs/fd.h"
#include "fs/stat.h"
#include "fs/tty.h"
#include "fs/fake.h"
#include "fs/real.h"
#include "fs/poll.h"
#include "fs/dev.h"

// EMU
#include "emu/cpu.h"
#include "emu/tlb.h"
#include "emu/mmu.h"
#if defined(GUEST_X86)
#include "emu/float80.h"
#endif

// Platform
#include "platform/platform.h"

#endif /* ISH_H */
EOF

    log_success "Umbrella header created"
}

# ============================================================================
# Print Summary
# ============================================================================
print_summary() {
    echo ""
    echo "============================================================"
    echo -e "${GREEN}🎉 iSH-ARM64 Build Complete!${NC}"
    echo "============================================================"
    echo ""
    echo "Libraries:"
    ls -lh "$OUTPUT_LIBS"/*.a 2>/dev/null || echo "  (none)"
    echo ""
    echo "Headers:   $OUTPUT_INCLUDE/ish/"
    echo "Resources: $OUTPUT_RESOURCES/"
    echo ""
    echo "To integrate with Xcode:"
    echo "  1. Add libs/*.a to 'Link Binary With Libraries'"
    echo "  2. Add include/ to 'Header Search Paths'"
    echo "  3. Add resources/ to 'Copy Bundle Resources'"
    echo "  4. Link with: libsqlite3.tbd"
    echo ""
    echo "============================================================"
}

# ============================================================================
# Main
# ============================================================================
main() {
    echo ""
    echo "============================================================"
    echo "  iSH-ARM64 Static Library Builder for iOS"
    echo "  Guest arch: arm64 (aarch64 Linux emulation)"
    echo "============================================================"
    echo ""

    if [ "$1" == "clean" ]; then
        clean_build
        exit 0
    fi

    # Check if ish directory exists
    if [ ! -d "$ISH_DIR" ]; then
        log_error "iSH directory not found at $ISH_DIR"
    fi

    check_prerequisites
    init_submodules
    setup_cross_compile
    build_ish
    copy_outputs
    create_umbrella_header
    print_summary
}

main "$@"
