#!/usr/bin/env bash
# PostToolUse(Edit|Write): warn when a CLAUDE.md exceeds the line limit.
set -euo pipefail

MAX_LINES=200

file_path=$(jq -r '.tool_input.file_path // empty')
[[ "$(basename "${file_path:-}")" == "CLAUDE.md" ]] || exit 0
[[ -f "$file_path" ]] || exit 0

lines=$(wc -l < "$file_path" | tr -d ' ')
if (( lines > MAX_LINES )); then
  echo "$file_path: ${lines} lines (limit ${MAX_LINES}). Move details to .claude/docs/<topic>.md and add a row to the situation→path table. Do not use @import or docs/spec/." >&2
  exit 2
fi
