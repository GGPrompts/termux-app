#!/usr/bin/env bash
# cross-env.sh — Set environment variables for cross-compiling to Android/aarch64
#
# Usage (source it):
#   source scripts/cross-env.sh
#   cargo build --target aarch64-linux-android --release
#
# Or prefix a command:
#   scripts/cross-env.sh cargo build --target aarch64-linux-android --release
#
# These env vars are needed by crates that use the `cc` crate to compile C code.
# The linker is already configured in ~/.cargo/config.toml.

NDK_ROOT="/home/marci/Android/Sdk/android-ndk-r27c"
NDK_TOOLCHAIN="${NDK_ROOT}/toolchains/llvm/prebuilt/linux-x86_64"
NDK_API=28

export CC_aarch64_linux_android="${NDK_TOOLCHAIN}/bin/aarch64-linux-android${NDK_API}-clang"
export CXX_aarch64_linux_android="${NDK_TOOLCHAIN}/bin/aarch64-linux-android${NDK_API}-clang++"
export AR_aarch64_linux_android="${NDK_TOOLCHAIN}/bin/llvm-ar"
export RANLIB_aarch64_linux_android="${NDK_TOOLCHAIN}/bin/llvm-ranlib"

# Also export generic names for non-Rust tools
export ANDROID_NDK_ROOT="${NDK_ROOT}"
export ANDROID_NDK_HOME="${NDK_ROOT}"

# If invoked as a command (not sourced), execute the remaining arguments
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
    if [[ $# -gt 0 ]]; then
        exec "$@"
    else
        echo "Android NDK cross-compilation environment:"
        echo "  NDK_ROOT:    ${NDK_ROOT}"
        echo "  API level:   ${NDK_API}"
        echo "  CC:          ${CC_aarch64_linux_android}"
        echo "  CXX:         ${CXX_aarch64_linux_android}"
        echo "  AR:          ${AR_aarch64_linux_android}"
        echo ""
        echo "Usage: source $0  (to set env vars)"
        echo "       $0 <command> [args...]  (to run with env vars)"
    fi
fi
