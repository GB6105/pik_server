#!/usr/bin/env bash
# PreToolUse(Bash): block direct commits on protected Git Flow branches.
set -euo pipefail

PROTECTED='^(main|develop)$'

command=$(jq -r '.tool_input.command // empty')
grep -qE '(^|[;&|(]\s*)git(\s+-C\s+\S+)?\s+commit\b' <<<"$command" || exit 0

branch=$(git -C "$CLAUDE_PROJECT_DIR" symbolic-ref --short -q HEAD || true)
if [[ "$branch" =~ $PROTECTED ]]; then
  echo "Direct commit on '$branch' is blocked (Git Flow). Create feature/<FR-ID>-<slug> from develop, or release/hotfix. See .claude/docs/git-flow.md." >&2
  exit 2
fi
