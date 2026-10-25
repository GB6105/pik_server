# ADR-0013: 원격 브랜치 보호: PR·CI 필수

- 상태: 채택
- 날짜: 2026-10-25
- 관련: ADR-0008 (병합 방식 대체), ADR-0011, D-04, `.claude/docs/git-flow.md`

## 맥락
- 지금까지는 로컬에서 `git merge --no-ff`로 병합한 뒤 push했다. CI는 push 후에야 돌았으므로, 실패한 커밋도 `develop`에 들어갈 수 있었다.
- 에이전트는 사용자 토큰으로 push하므로, 원격에서 강제하지 않으면 규칙을 우회할 수 있다.
- 저장소는 공개 저장소라 Ruleset을 무료로 쓸 수 있다.

## 결정
- 원격 기본 브랜치를 `develop`으로 바꾼다.
- 저장소 Ruleset `protect-main-develop`을 `main`·`develop`에 적용한다.
  - PR로만 병합한다. 승인 리뷰는 0개로 둔다.
  - 필수 체크는 `build`(GitHub Actions)이고, base가 최신 상태여야 한다.
  - 병합 방식은 merge commit만 허용한다.
  - force push와 삭제를 금지한다.
  - 우회 대상은 없다 (관리자 포함).
- 병합은 사용자가 승인한 뒤 `gh pr merge --merge`로 한다.

## 근거
| 대안 | 버린 이유 |
|---|---|
| `main`만 PR 필수 | `develop`에 CI 실패 커밋이 들어갈 수 있음 |
| force push·삭제만 금지 | CI가 병합을 막지 못함 |
| 승인 리뷰 1개 | 1인 개발이라 자기 PR을 승인할 수 없어 병합이 막힘 |
| 관리자 우회 허용 | 에이전트도 같은 토큰으로 우회하게 됨 |
| squash·rebase 허용 | Git Flow의 `--no-ff` 병합 이력과 FR ID 커밋이 사라짐 |
| 기존 Branch protection | Ruleset이 여러 브랜치를 한 규칙으로 묶고 병합 방식까지 제한함 |

## 결과
- 작업 흐름: `feature/*` push → `develop` 대상 PR → `build` 통과 → 사용자 승인 → `gh pr merge --merge`.
- 로컬에서 병합한 뒤 `main`·`develop`에 push하면 원격이 거부한다.
- 릴리스·핫픽스는 `main` PR 병합 후, `main` → `develop` PR로 되돌려 병합한다.
