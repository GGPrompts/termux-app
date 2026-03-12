#!/usr/bin/env bash
# worker-prompt.sh — Generate a prompt for autonomous -p mode sessions
# Usage: worker-prompt.sh <issue-id> [--cli claude|codex] [--project /path]
#
# Reads issue details from beads and generates a complete prompt
# suitable for: claude-fast -p "$(worker-prompt.sh txc-1234)"

set -euo pipefail

ISSUE_ID="${1:?Usage: worker-prompt.sh <issue-id> [--cli claude|codex] [--project /path]}"
shift

CLI="claude"
PROJECT_DIR="."
while [[ $# -gt 0 ]]; do
    case "$1" in
        --cli) CLI="$2"; shift 2 ;;
        --project) PROJECT_DIR="$2"; shift 2 ;;
        *) echo "Unknown arg: $1" >&2; exit 1 ;;
    esac
done

# Resolve beads-mcp CLI path
BD="${BEADS_PATH:-$HOME/projects/ggbeads/ggbd}"
if [[ ! -x "$BD" ]]; then
    # Fallback: try ggbd in PATH
    BD="$(command -v ggbd 2>/dev/null || echo "")"
    if [[ -z "$BD" ]]; then
        echo "ERROR: beads CLI (ggbd) not found" >&2
        exit 1
    fi
fi

# Fetch issue details
ISSUE_JSON=$("$BD" show "$ISSUE_ID" --json 2>/dev/null) || {
    echo "ERROR: Failed to fetch issue $ISSUE_ID" >&2
    exit 1
}

TITLE=$(echo "$ISSUE_JSON" | jq -r '.title // "untitled"')
DESC=$(echo "$ISSUE_JSON" | jq -r '.description // ""')
ACCEPTANCE=$(echo "$ISSUE_JSON" | jq -r '.acceptance_criteria // ""')
NOTES=$(echo "$ISSUE_JSON" | jq -r '.notes // ""')
LABELS=$(echo "$ISSUE_JSON" | jq -r '(.labels // []) | join(", ")')

# Check if there's a prepared.prompt in the notes
PREPARED=""
if echo "$NOTES" | grep -q '## prepared.prompt'; then
    PREPARED=$(echo "$NOTES" | sed -n '/## prepared.prompt/,/^## [^p]/p' | head -n -1)
fi

# If there's a prepared prompt, use it directly with wrapper
if [[ -n "$PREPARED" ]]; then
    cat <<PROMPT
$PREPARED

---
Issue: $ISSUE_ID ($TITLE)
Project: $PROJECT_DIR
When complete: commit your changes with a descriptive message, then report what you did.
PROMPT
    exit 0
fi

# Generate a standard autonomous worker prompt
cat <<PROMPT
You are an autonomous worker. Complete the task below, then stop.

## Task: $TITLE
Issue: $ISSUE_ID

## Description
$DESC
PROMPT

# Add acceptance criteria if present
if [[ -n "$ACCEPTANCE" ]]; then
    cat <<PROMPT

## Acceptance Criteria
$ACCEPTANCE
PROMPT
fi

# Add relevant notes (skip debugging/investigation notes)
if [[ -n "$NOTES" && "$NOTES" != "null" ]]; then
    cat <<PROMPT

## Notes
$NOTES
PROMPT
fi

# Add CLI-specific instructions
case "$CLI" in
    claude)
        cat <<'PROMPT'

## Instructions
- Work in the current directory
- Read existing code before making changes
- Make focused, minimal changes — only what the task requires
- Commit your changes with a descriptive message
- Do not push unless explicitly told to
- If you encounter a blocker you cannot resolve, describe it clearly and stop
PROMPT
        ;;
    codex)
        cat <<'PROMPT'

## Instructions
- Work in the current directory
- Make focused, minimal changes — only what the task requires
- When done, describe what you changed
PROMPT
        ;;
esac

cat <<PROMPT

## Project Context
Working directory: $PROJECT_DIR
Labels: $LABELS
PROMPT
