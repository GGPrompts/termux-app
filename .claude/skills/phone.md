---
name: phone
description: Manage the PocketForge phone (build, deploy, recover). Priority #1 is always getting SSH working so Claude can do everything else remotely.
user_invocable: true
---

# Phone Management

## Step 0: Always Do This First

Before anything else, verify SSH and Tailscale. If either is down, fix them BEFORE presenting options.

```bash
# 1. Check tailscaled is running
tailscale status || echo "TAILSCALE DOWN — ask user to run: sudo systemctl start tailscaled"

# 2. Get phone IP
PHONE_IP=$(tailscale status | grep samsung | awk '{print $1}')

# 3. Test SSH
ssh -p 8022 -o ConnectTimeout=5 $PHONE_IP "echo SSH_OK"
```

### If SSH fails: Bootstrap Protocol

This is the ONLY part that requires the user to type on the phone. Keep it minimal.

1. Start HTTP server on desktop with the SSH public key:
```bash
mkdir -p /tmp/phone-restore
cp ~/.ssh/id_ed25519.pub /tmp/phone-restore/authorized_keys
cp ~/.claude/.credentials.json /tmp/phone-restore/credentials.json
python3 -m http.server 9000 --directory /tmp/phone-restore --bind 0.0.0.0 &
DESKTOP_IP=$(tailscale status | grep mattlinux | awk '{print $1}')
```

2. Tell the user to type ONLY these on the phone (give one at a time — phone keyboard is painful):
```
pkg install openssh curl
```
```
ssh-keygen -A
```
```
sshd
```
```
termux-setup-storage
```
```
mkdir -p ~/.ssh
```
```
curl -o ~/.ssh/authorized_keys http://DESKTOP_IP:9000/authorized_keys
```
```
chmod 700 ~/.ssh
```
```
chmod 600 ~/.ssh/authorized_keys
```

3. Test SSH again. If host key mismatch:
```bash
ssh-keygen -f ~/.ssh/known_hosts -R '[PHONE_IP]:8022'
```

4. Once SSH works, kill the HTTP server and do everything else remotely.

## Options (present after SSH is confirmed working)

1. **Check Status** - Verify tools, services, connectivity
2. **Push Credentials** - Copy fresh Claude auth to phone
3. **Restore Backup** - Restore full Termux environment from /sdcard/
4. **Pull Repos** - Git pull all projects on phone
5. **Build & Push APK** - Cross-compile Rust, build APK, push to phone
6. **Update CLI Tools** - Update Claude Code and Codex on phone
7. **Full Recovery** - Restore backup + push credentials + pull repos (for after reinstall)

## Implementation

### Check Status
```bash
PHONE_IP=$(tailscale status | grep samsung | awk '{print $1}')
ssh -p 8022 $PHONE_IP "echo 'SSH: OK'; claude --version 2>/dev/null || echo 'claude: NOT FOUND'; node --version 2>/dev/null || echo 'node: NOT FOUND'; ls ~/.bun/bin/buno ~/.bun/bin/claude-fast 2>&1"
```

### Push Credentials
```bash
PHONE_IP=$(tailscale status | grep samsung | awk '{print $1}')
scp -P 8022 ~/.claude/.credentials.json $PHONE_IP:~/.claude/.credentials.json
```

### Restore Backup
Requires `termux-setup-storage` to have been granted on the phone.
```bash
PHONE_IP=$(tailscale status | grep samsung | awk '{print $1}')
ssh -p 8022 $PHONE_IP "ls ~/storage/shared/termux-full-backup.tar.gz" || echo "BACKUP NOT FOUND or storage permission missing — ask user to run termux-setup-storage on phone"
ssh -p 8022 $PHONE_IP "tar -xzf ~/storage/shared/termux-full-backup.tar.gz -C /data/data/com.termux/files"
```
After restore, push fresh credentials (backup has stale ones).

### Pull Repos
```bash
PHONE_IP=$(tailscale status | grep samsung | awk '{print $1}')
ssh -p 8022 $PHONE_IP "cd ~/projects/pocketforge && git pull; cd ~/projects/codefactory && git pull"
```

### Build & Push APK
```bash
# All-in-one: cross-compile Rust, build APK, deploy to phone
bash ~/projects/pocketforge/scripts/build-codefactory-android.sh

# Or step by step:
# 1. Cross-compile Rust
source ~/projects/pocketforge/scripts/cross-env.sh
cd ~/projects/pocketforge/native/codefactory
cargo build --target aarch64-linux-android --release

# 2. Build APK
JAVA_HOME=$HOME/jdk/jdk-21.0.10+7 ~/projects/pocketforge/gradlew -p ~/projects/pocketforge assembleDebug

# 3. Push to phone
PHONE_IP=$(tailscale status | grep samsung | awk '{print $1}')
scp -P 8022 ~/projects/pocketforge/app/build/outputs/apk/debug/termux-app_apt-android-7-debug_arm64-v8a.apk $PHONE_IP:/sdcard/pocketforge.apk
```
Tell user to install from file manager: My Files > Internal Storage > pocketforge.apk

### Full Recovery (after app reinstall)
Run in order:
1. SSH bootstrap (Step 0 above — needs user to type on phone)
2. Restore backup
3. Push credentials
4. Pull repos
5. Verify with Check Status

### Update CLI Tools
Termux reports `os: android` instead of `linux`, so `--force` is required.
```bash
PHONE_IP=$(tailscale status | grep samsung | awk '{print $1}')
ssh -p 8022 $PHONE_IP "npm update -g @anthropic-ai/claude-code --force"
ssh -p 8022 $PHONE_IP "npm update -g @openai/codex --force"
ssh -p 8022 $PHONE_IP "claude --version; npm list -g @openai/codex 2>/dev/null | grep codex"
```
Don't run `claude install` on phone — it installs Anthropic's custom Bun that segfaults.

## Notes

- Always use tmux when SSH'ing to phone so user can observe: `ssh -p 8022 $PHONE_IP -t "tmux new -As claude"`
- Backup at /sdcard/termux-full-backup.tar.gz survives app uninstall (7.3GB)
- Fresh bootstrap resets package DB — `pkg install openssh` is always needed first
- After restore, restart the app to pick up restored .bashrc
- Don't run `claude install` on phone — it installs Anthropic's Bun that segfaults
- npm needs `--force` on Termux — platform reports android instead of linux
