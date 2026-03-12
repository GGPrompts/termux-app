# PocketForge

## Project Overview

Custom Termux fork that serves as a standalone Android dev workstation app called "PocketForge". Combines a Termux-based terminal with a native Rust GPU renderer (wgpu + alacritty_terminal) and the codefactory Axum backend.

## Repository Structure

- `app/` -- Android app source (Java)
- `native/codefactory/` -- Rust crate for GPU-accelerated terminal renderer (wgpu/Vulkan + alacritty_terminal), compiled to .so via JNI
- `scripts/` -- Build, cross-compile, and deployment scripts
- `termux-shared/` -- Termux shared library (includes consolidated terminal emulator and PTY session code)

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

## Cross-Compilation

- Target: `aarch64-linux-android` (bionic libc, NOT glibc)
- `scripts/cross-env.sh` sets CC/CXX/AR for the `cc` crate

## Termux/Android Constraints

- Package name is `com.termux` -- cannot change without rebuilding ALL bootstrap binaries
- Targets API 28 -- Android 10+ breaks exec from app data dirs
- Bootstrap is embedded in APK as native .so (not downloaded at runtime)
- Play Store requires API 35+ -- distribution via GitHub Releases and self-hosted F-Droid only

## Architecture

Unified Rust stack: wgpu/Vulkan renderer + alacritty_terminal parser + codefactory Axum backend, loaded as a single .so via JNI. WebView only for dashboard pages (git, kanban, files), not terminals.

## Key Decisions

- **Keep com.termux package name** -- branding only as "PocketForge"
- **wgpu + alacritty_terminal** replaces both Termux's Java renderer and xterm.js WebGL
- **GitHub Releases primary** distribution, self-hosted F-Droid secondary

## Commit Message Convention

Follow [Conventional Commits](https://www.conventionalcommits.org) per upstream Termux rules. Types must be capitalized: `Added`, `Changed`, `Fixed`, `Removed`, `Deprecated`, `Security`. First letter of description must also be capital.
