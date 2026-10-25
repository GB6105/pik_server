#!/usr/bin/env bash
# SessionStart: inject open decisions so they are reported at task start.
set -euo pipefail

status="$CLAUDE_PROJECT_DIR/docs/STATUS.md"
adr_index="$CLAUDE_PROJECT_DIR/docs/adr/README.md"

echo "[미결 결정 레지스터] 작업 시작 보고에 아래 항목 중 이번 작업과 관련된 것을 포함할 것 (.claude/docs/decision-reporting.md)"
echo "현재 브랜치: $(git -C "$CLAUDE_PROJECT_DIR" symbolic-ref --short -q HEAD || echo detached)"

if [[ -f "$status" ]]; then
  awk '/^## 미결 결정/{p=1; print; next} /^## /{p=0} p' "$status"
fi

if [[ -f "$adr_index" ]]; then
  pending=$(grep -E '^\| \[[0-9]{4}\]' "$adr_index" | grep -E '제안|미기록' || true)
  if [[ -n "$pending" ]]; then
    echo "## 확정·보완 대기 ADR"
    echo "$pending"
  fi
fi
