// Termux grun env fix: restore process.env from /proc/self/environ
// grun (glibc-runner) zeros the C environ pointer when running through ld.so,
// but the kernel-level environment in /proc/self/environ remains intact.
const { readFileSync } = require("fs");
try {
    if (Object.keys(process.env).length === 0) {
        const data = readFileSync("/proc/self/environ", "utf8");
        for (const entry of data.split("\0")) {
            if (!entry) continue;
            const idx = entry.indexOf("=");
            if (idx > 0) {
                process.env[entry.substring(0, idx)] = entry.substring(idx + 1);
            }
        }
    }
} catch {}

// Fix argv[0]: grun sets it to ld-linux-aarch64.so.1 instead of bun,
// which breaks Claude Code's runtime detection and argument parsing
if (process.argv[0] && process.argv[0].includes("ld-linux")) {
    process.argv[0] = "bun";
}

// Fix execPath: grun sets it to ld-linux-aarch64.so.1 instead of bun
if (process.execPath && process.execPath.includes("ld-linux")) {
    // Can't reassign process.execPath directly, but we can try
    try { Object.defineProperty(process, 'execPath', { value: 'bun', writable: true }); } catch {}
}

// Fix stderr.isTTY: Bun through grun reports undefined for stderr.isTTY
// even when stderr is connected to a terminal. This breaks Claude Code's
// mode detection (interactive vs -p one-shot). Check via fd 2.
const { isatty } = require("tty");
try {
    if (process.stderr && process.stderr.isTTY === undefined && isatty(2)) {
        process.stderr.isTTY = true;
    }
} catch {}

// Set CLAUDE_CODE_TMPDIR for Termux (Claude Code hardcodes /tmp which doesn't exist)
// Added in Claude Code v2.1.5 — overrides internal temp directory
if (!process.env.CLAUDE_CODE_TMPDIR) {
    const prefix = process.env.PREFIX || "/data/data/com.termux/files/usr";
    process.env.CLAUDE_CODE_TMPDIR = prefix + "/tmp";
}
