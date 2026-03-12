#!/data/data/com.termux/files/usr/bin/bash
# post-bootstrap-setup.sh — PocketForge first-run setup
#
# Runs after the stock Termux bootstrap has been extracted to $PREFIX.
# Installs packages and configuration needed for the codefactory dev
# workstation experience.
#
# This script is idempotent: running it again skips already-installed items.
#
# Usage (on-device, after bootstrap):
#   bash /data/data/com.termux/files/usr/share/pocketforge/post-bootstrap-setup.sh
#
# Can also be triggered from TermuxInstaller's whenDone callback by
# launching it as the first shell command.

set -euo pipefail

PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
HOME="${HOME:-/data/data/com.termux/files/home}"
MARKER="$HOME/.pocketforge-setup-done"
LOG_TAG="pocketforge-setup"

log() {
    echo "[$LOG_TAG] $1"
}

error() {
    echo "[$LOG_TAG] ERROR: $1" >&2
}

# Skip if already completed
if [ -f "$MARKER" ]; then
    log "Post-bootstrap setup already completed (marker: $MARKER). Skipping."
    log "To re-run, delete $MARKER and run again."
    exit 0
fi

log "Starting PocketForge post-bootstrap setup..."

# --------------------------------------------------------------------------
# 1. Update package index
# --------------------------------------------------------------------------
log "Updating package index..."
apt update -y 2>&1 || {
    error "apt update failed. Network may be unavailable."
    error "Re-run this script when connected."
    exit 1
}

# --------------------------------------------------------------------------
# 2. Install essential packages
# --------------------------------------------------------------------------
PACKAGES=(
    # Version control
    git
    # Terminal multiplexer (required by codefactory portable-pty)
    tmux
    # Editors
    vim
    # Networking
    openssh
    curl
    wget
    # Build tools
    make
    clang
    # Utilities
    jq
    tar
    zip
    unzip
    # Node.js runtime (needed for Claude Code)
    nodejs
)

log "Installing packages: ${PACKAGES[*]}"
apt install -y "${PACKAGES[@]}" 2>&1

# --------------------------------------------------------------------------
# 3. Configure git defaults (if not already set)
# --------------------------------------------------------------------------
if ! git config --global user.name >/dev/null 2>&1; then
    log "Setting default git config..."
    git config --global init.defaultBranch main
    git config --global core.editor vim
fi

# --------------------------------------------------------------------------
# 4. Create PocketForge directory structure
# --------------------------------------------------------------------------
log "Creating PocketForge directories..."
mkdir -p "$HOME/projects"
mkdir -p "$HOME/.config/codefactory"

# --------------------------------------------------------------------------
# 5. Install Bun (optional, for faster JS runtime)
# --------------------------------------------------------------------------
if ! command -v bun >/dev/null 2>&1; then
    log "Bun not found. Skipping Bun install (can be installed later with: curl -fsSL https://bun.sh/install | bash)"
else
    log "Bun already installed."
fi

# --------------------------------------------------------------------------
# 6. Set up termux-exec LD_PRELOAD workaround (Android 10+ W^X)
# --------------------------------------------------------------------------
TERMUX_EXEC_SO="$PREFIX/lib/libtermux-exec.so"
if [ -f "$TERMUX_EXEC_SO" ]; then
    if ! grep -q "LD_PRELOAD.*libtermux-exec" "$HOME/.bashrc" 2>/dev/null; then
        log "Adding termux-exec LD_PRELOAD to .bashrc..."
        echo "" >> "$HOME/.bashrc"
        echo "# termux-exec: fix shebang execution on Android 10+" >> "$HOME/.bashrc"
        echo "export LD_PRELOAD=$TERMUX_EXEC_SO" >> "$HOME/.bashrc"
    fi
fi

# --------------------------------------------------------------------------
# 7. Configure SSH server (sshd) for remote access
# --------------------------------------------------------------------------
if command -v sshd >/dev/null 2>&1; then
    log "SSH server available. Generate host keys if missing..."
    if [ ! -f "$PREFIX/etc/ssh/ssh_host_rsa_key" ]; then
        ssh-keygen -A 2>&1
    fi
    log "Start sshd with: sshd -p 8022"
fi

# --------------------------------------------------------------------------
# 8. Write marker file
# --------------------------------------------------------------------------
date -Iseconds > "$MARKER"
log "Post-bootstrap setup completed successfully."
log "Marker written to $MARKER"
log ""
log "Next steps:"
log "  - Configure git: git config --global user.name 'Your Name'"
log "  - Configure git: git config --global user.email 'you@example.com'"
log "  - Start sshd: sshd -p 8022"
log "  - Clone your projects into ~/projects/"
