#!/usr/bin/env bash
# build-codefactory-android.sh -- Build the codefactory-android Rust crate,
# copy libcodefactory.so to termux-app jniLibs, build APK, and optionally
# deploy to phone via ADB.
#
# Usage:
#   bash scripts/build-codefactory-android.sh [--debug] [--no-apk] [--no-deploy]
#
# Options:
#   --debug      Build debug instead of release
#   --no-apk     Skip Gradle APK build (just compile Rust + copy .so)
#   --no-deploy  Skip ADB install to phone
#
# Prerequisites:
#   - Rust aarch64-linux-android target installed
#   - NDK r27c at ~/Android/Sdk/android-ndk-r27c
#   - ~/.cargo/config.toml has the linker configured
#   - For deploy: ADB connected (wireless or USB)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
CRATE_DIR="${REPO_ROOT}/native/codefactory"
TARGET="aarch64-linux-android"
JNILIBS_DIR="${REPO_ROOT}/app/src/main/jniLibs/arm64-v8a"
ADB="${HOME}/Android/Sdk/platform-tools/adb"
JAVA_HOME="${HOME}/jdk/jdk-21.0.10+7"

# Parse flags
PROFILE="release"
CARGO_FLAGS="--release"
BUILD_APK=true
DEPLOY=true

for arg in "$@"; do
    case "$arg" in
        --debug)   PROFILE="debug"; CARGO_FLAGS="" ;;
        --no-apk)  BUILD_APK=false ;;
        --no-deploy) DEPLOY=false ;;
    esac
done

echo "=== Building codefactory-android (${PROFILE}) ==="
echo "  Target: ${TARGET}"
echo "  Crate:  ${CRATE_DIR}"
echo ""

# Step 1: Cross-compile Rust
source "${SCRIPT_DIR}/cross-env.sh"
cd "${CRATE_DIR}"
cargo build --target "${TARGET}" ${CARGO_FLAGS}

# Step 2: Copy .so to jniLibs
SO_PATH="${CRATE_DIR}/target/${TARGET}/${PROFILE}/libcodefactory.so"

if [[ ! -f "$SO_PATH" ]]; then
    echo "ERROR: ${SO_PATH} not found"
    exit 1
fi

mkdir -p "${JNILIBS_DIR}"
cp -v "${SO_PATH}" "${JNILIBS_DIR}/libcodefactory.so"

SO_SIZE=$(stat -c%s "${JNILIBS_DIR}/libcodefactory.so")
echo ""
echo "  .so: ${JNILIBS_DIR}/libcodefactory.so ($(numfmt --to=iec ${SO_SIZE}))"

# Step 3: Build APK
if [[ "$BUILD_APK" == true ]]; then
    echo ""
    echo "=== Building APK ==="
    JAVA_HOME="${JAVA_HOME}" "${REPO_ROOT}/gradlew" -p "${REPO_ROOT}" assembleDebug
    APK_PATH="${REPO_ROOT}/app/build/outputs/apk/debug/termux-app_apt-android-7-debug_arm64-v8a.apk"
    if [[ -f "$APK_PATH" ]]; then
        APK_SIZE=$(stat -c%s "$APK_PATH")
        echo "  APK: ${APK_PATH} ($(numfmt --to=iec ${APK_SIZE}))"
    fi
fi

# Step 4: Deploy to phone via ADB
if [[ "$DEPLOY" == true && "$BUILD_APK" == true ]]; then
    echo ""
    echo "=== Deploying to phone ==="
    APK_PATH="${REPO_ROOT}/app/build/outputs/apk/debug/termux-app_apt-android-7-debug_arm64-v8a.apk"

    if ! "${ADB}" devices | grep -q "device$"; then
        echo "  No ADB device connected. Falling back to SCP..."
        PHONE_IP=$(tailscale status 2>/dev/null | grep samsung | awk '{print $1}')
        if [[ -n "$PHONE_IP" ]]; then
            scp -P 8022 "${APK_PATH}" "${PHONE_IP}:/sdcard/codefactory.apk" && \
                echo "  Pushed to phone via SCP. Install from file manager." || \
                echo "  SCP failed. Push manually."
        else
            echo "  No Tailscale connection either. Copy APK manually."
        fi
    else
        "${ADB}" install -r "${APK_PATH}" && \
            echo "  Installed on phone via ADB. App will restart." || \
            echo "  ADB install failed."
    fi
fi

echo ""
echo "=== Done ==="
