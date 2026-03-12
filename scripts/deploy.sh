#!/usr/bin/env bash
# deploy.sh — Deploy a binary to the phone via SSH over Tailscale
# Usage: deploy.sh <binary-path> [remote-dest] [--run [args...]]
#
# Examples:
#   deploy.sh target/aarch64-linux-android/release/myapp
#   deploy.sh target/aarch64-linux-android/release/myapp ~/projects/codefactory/
#   deploy.sh hello-arm64 ~/bin/ --run
#   deploy.sh hello-arm64 ~/bin/ --run --some-flag
#
# Environment:
#   PHONE_HOST  — SSH host (default: 100.112.94.125)
#   PHONE_PORT  — SSH port (default: 8022)
#   PHONE_USER  — SSH user (default: current user)

set -euo pipefail

PHONE_HOST="${PHONE_HOST:-100.112.94.125}"
PHONE_PORT="${PHONE_PORT:-8022}"
PHONE_USER="${PHONE_USER:-$(whoami)}"

SSH_OPTS="-o ConnectTimeout=5 -o StrictHostKeyChecking=accept-new"
SSH_CMD="ssh ${SSH_OPTS} -p ${PHONE_PORT} ${PHONE_HOST}"
SCP_CMD="scp ${SSH_OPTS} -P ${PHONE_PORT}"

usage() {
    echo "Usage: deploy.sh <binary-path> [remote-dest] [--run [args...]]"
    echo ""
    echo "  binary-path   Local path to the binary to deploy"
    echo "  remote-dest   Remote directory (default: ~/bin/)"
    echo "  --run         Run the binary on the phone after deploying"
    echo "  args...       Arguments to pass to the binary when running"
    exit 1
}

if [[ $# -lt 1 ]]; then
    usage
fi

BINARY="$1"
shift

if [[ ! -f "$BINARY" ]]; then
    echo "Error: File not found: $BINARY"
    exit 1
fi

# Parse remote destination and --run flag
REMOTE_DIR="~/bin/"
RUN=false
RUN_ARGS=()

while [[ $# -gt 0 ]]; do
    case "$1" in
        --run)
            RUN=true
            shift
            # Everything after --run is args for the binary
            RUN_ARGS=("$@")
            break
            ;;
        *)
            REMOTE_DIR="$1"
            shift
            ;;
    esac
done

BINARY_NAME=$(basename "$BINARY")
REMOTE_PATH="${REMOTE_DIR%/}/${BINARY_NAME}"

# Verify the binary is an ARM64 ELF (basic sanity check)
if command -v file &>/dev/null; then
    FILE_INFO=$(file "$BINARY")
    if [[ "$FILE_INFO" == *"ELF"* ]] && [[ "$FILE_INFO" != *"aarch64"* ]] && [[ "$FILE_INFO" != *"ARM aarch64"* ]]; then
        echo "Warning: Binary does not appear to be aarch64:"
        echo "  $FILE_INFO"
        read -p "Continue anyway? [y/N] " -n 1 -r
        echo
        if [[ ! $REPLY =~ ^[Yy]$ ]]; then
            exit 1
        fi
    fi
fi

echo "Deploying: ${BINARY_NAME}"
echo "  From: ${BINARY}"
echo "  To:   ${PHONE_HOST}:${REMOTE_PATH}"
echo ""

# Ensure remote directory exists
${SSH_CMD} "mkdir -p ${REMOTE_DIR}" 2>/dev/null || true

# Copy the binary
echo "Uploading..."
${SCP_CMD} "$BINARY" "${PHONE_HOST}:${REMOTE_PATH}"

# Make it executable
${SSH_CMD} "chmod +x ${REMOTE_PATH}"

BINARY_SIZE=$(stat --printf="%s" "$BINARY" 2>/dev/null || stat -f%z "$BINARY" 2>/dev/null || echo "?")
if [[ "$BINARY_SIZE" != "?" ]]; then
    BINARY_SIZE_KB=$((BINARY_SIZE / 1024))
    echo "Deployed ${BINARY_NAME} (${BINARY_SIZE_KB} KB)"
else
    echo "Deployed ${BINARY_NAME}"
fi

# Optionally run on the phone
if [[ "$RUN" == true ]]; then
    echo ""
    echo "Running on phone: ${REMOTE_PATH} ${RUN_ARGS[*]:-}"
    echo "---"
    ${SSH_CMD} "${REMOTE_PATH} ${RUN_ARGS[*]:-}"
fi
