#!/usr/bin/env bash
# PreToolUse(Bash): block direct commits on protected Git Flow branches.
# Checks the tree the commit runs in (git -C <dir>, a preceding cd <dir>, or the tool cwd), so worktrees are judged by their own branch.
set -euo pipefail

PROTECTED='^(main|develop)$'

input=$(cat)
command=$(jq -r '.tool_input.command // empty' <<<"$input")
grep -qE '(^|[;&|(]\s*)git(\s+-C\s+\S+)?\s+commit\b' <<<"$command" || exit 0

cwd=$(jq -r '.cwd // empty' <<<"$input")
cwd=${cwd:-$CLAUDE_PROJECT_DIR}

dir=$(sed -nE 's/.*git[[:space:]]+-C[[:space:]]+([^[:space:]]+)[[:space:]]+commit.*/\1/p' <<<"$command" | head -n1)
if [[ -z "$dir" ]]; then
  prefix="; $(tr '\n' ';' <<<"${command%%git*commit*}")"
  dir=$(sed -nE 's/.*[;&|(][[:space:]]*cd[[:space:]]+([^[:space:]&;|)]+).*/\1/p' <<<"$prefix" | head -n1)
fi
dir=${dir//\"/}
dir=${dir//\'/}
dir=${dir/#\~/$HOME}
[[ -n "$dir" ]] || dir=$cwd
[[ "$dir" == /* ]] || dir="$cwd/$dir"

if ! git -C "$dir" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  echo "Cannot resolve the commit directory '$dir'. Use a literal path (git -C <absolute-path> commit); shell variables are not expanded by this guard." >&2
  exit 2
fi

branch=$(git -C "$dir" symbolic-ref --short -q HEAD || true)
if [[ "$branch" =~ $PROTECTED ]]; then
  echo "Direct commit on '$branch' is blocked (Git Flow). Create feature/<FR-ID>-<slug> from develop, or release/hotfix. See .claude/docs/git-flow.md." >&2
  exit 2
fi
