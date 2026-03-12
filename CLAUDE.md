# PocketForge

## Project Overview

Custom Termux fork that serves as a standalone Android dev workstation app called "PocketForge". Combines a Termux-based terminal with a native Rust GPU renderer (wgpu + alacritty_terminal) and the codefactory Axum backend.

## Repository Structure

- `app/` -- Android app source (Java)
- `native/codefactory/` -- Rust crate for GPU-accelerated terminal renderer (wgpu/Vulkan + alacritty_terminal), compiled to .so via JNI
- `scripts/` -- Build, cross-compile, and deployment scripts
- `terminal-emulator/`, `terminal-view/`, `termux-shared/` -- Termux library modules

## Building

### Rust native library (.so)
```bash
source scripts/cross-env.sh
cd native/codefactory
cargo build --target aarch64-linux-android --release
```

### Android APK
```bash
JAVA_HOME=$HOME/jdk/jdk-21.0.10+7 ./gradlew assembleDebug
```

### Full build (Rust + APK + optional deploy)
```bash
bash scripts/build-codefactory-android.sh [--debug] [--no-apk] [--no-deploy]
```

## Phone Access

- Samsung Galaxy S22 Ultra, Android 12, Termux from F-Droid
- SSH: `ssh $(tailscale status | grep samsung | awk '{print $1}') -p 8022`
- Run `tailscale status` to see all device names and IPs
- Stock Termux is currently installed (do NOT uninstall without asking)

## Cross-Compilation

- NDK r27c: `~/Android/Sdk/android-ndk-r27c`
- Target: `aarch64-linux-android` (bionic libc, NOT glibc)
- Linker configured in `~/.cargo/config.toml`
- `scripts/cross-env.sh` sets CC/CXX/AR for the `cc` crate
- `scripts/deploy.sh` pushes binaries to phone via SSH/Tailscale

## Termux/Android Constraints

- Package name is `com.termux` -- cannot change without rebuilding ALL bootstrap binaries
- Targets API 28 -- Android 10+ breaks exec from app data dirs
- Bootstrap is embedded in APK as native .so (not downloaded at runtime)
- Play Store requires API 35+ -- distribution via GitHub Releases and self-hosted F-Droid only

## Architecture

Unified Rust stack: wgpu/Vulkan renderer + alacritty_terminal parser + codefactory Axum backend, loaded as a single .so via JNI. WebView only for dashboard pages (git, kanban, files), not terminals.

## Sensitive Info

- README contains SSH port details -- review before making repo public
- Debug signing key (`testkey_untrusted.jks`) is in `app/`
- `.beads/` contains database connection info -- gitignored but check before sharing

## Issue Tracking

Uses beads with prefix `txc`. Run `bd ready` or `mcp__beads__ready(prefix='txc')` to see actionable tasks.

## Codefactory Backend (separate repo)

- Repo: `~/projects/codefactory` (Rust/Axum backend + vanilla JS frontend)
- Serves on port 3001, config at `~/.config/codefactory/profiles.json`
- This repo does NOT contain the backend -- only the native renderer and Android shell

## Key Decisions

- **Keep com.termux package name** -- branding only as "PocketForge"
- **wgpu + alacritty_terminal** replaces both Termux's Java renderer and xterm.js WebGL
- **GitHub Releases primary** distribution, self-hosted F-Droid secondary
- **RSA 4096-bit signing key** with APK Signature Scheme v3 for future rotation

## Commit Message Convention

Follow [Conventional Commits](https://www.conventionalcommits.org) per upstream Termux rules. Types must be capitalized: `Added`, `Changed`, `Fixed`, `Removed`, `Deprecated`, `Security`. First letter of description must also be capital.
