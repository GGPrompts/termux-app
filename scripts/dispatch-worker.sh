#!/usr/bin/env bash
# dispatch-worker.sh — Dispatch a single issue to a CLI worker
# Usage: dispatch-worker.sh <issue-id> [--cli claude|codex] [--project /path] [--dry-run]
#
# Generates a prompt from beads and runs it through the selected CLI.
# Output is captured and can be appended to beads notes.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ISSUE_ID="${1:?Usage: dispatch-worker.sh <issue-id> [--cli claude|codex] [--project /path] [--dry-run]}"
shift

CLI="claude"
PROJECT_DIR="."
DRY_RUN=false
while [[ $# -gt 0 ]]; do
    case "$1" in
        --cli) CLI="$2"; shift 2 ;;
        --project) PROJECT_DIR="$2"; shift 2 ;;
        --dry-run) DRY_RUN=true; shift ;;
        *) echo "Unknown arg: $1" >&2; exit 1 ;;
    esac
done

# Generate the prompt
PROMPT=$("$SCRIPT_DIR/worker-prompt.sh" "$ISSUE_ID" --cli "$CLI" --project "$PROJECT_DIR")

if [[ "$DRY_RUN" == "true" ]]; then
    echo "=== DRY RUN: Would dispatch $ISSUE_ID via $CLI ==="
    echo ""
    echo "$PROMPT"
    exit 0
fi

echo ">>> Dispatching $ISSUE_ID via $CLI..."

# Select the CLI command
case "$CLI" in
    claude)
        # Use claude-fast on Termux, claude elsewhere
        if command -v claude-fast &>/dev/null; then
            CMD="claude-fast"
        else
            CMD="claude"
        fi
        OUTPUT=$(cd "$PROJECT_DIR" && $CMD -p "$PROMPT" 2>&1) || true
        ;;
    codex)
        OUTPUT=$(cd "$PROJECT_DIR" && codex exec \
            --skip-git-repo-check \
            -c 'sandbox_mode="danger-full-access"' \
            "$PROMPT" 2>&1) || true
        ;;
    *)
        echo "ERROR: Unknown CLI: $CLI" >&2
        exit 1
        ;;
esac

echo ">>> Worker finished for $ISSUE_ID"
echo ""
echo "$OUTPUT"

# Optionally save output to a log file
LOG_DIR="${SCRIPT_DIR}/../logs"
mkdir -p "$LOG_DIR"
LOG_FILE="$LOG_DIR/${ISSUE_ID}_$(date +%Y%m%d_%H%M%S).log"
cat > "$LOG_FILE" <<EOF
Issue: $ISSUE_ID
CLI: $CLI
Project: $PROJECT_DIR
Timestamp: $(date -Iseconds)
---
$OUTPUT
EOF
echo ""
echo ">>> Log saved: $LOG_FILE"
