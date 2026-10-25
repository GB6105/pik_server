# ADR-0008: Git Flow 브랜치 전략

- 상태: 채택
- 날짜: 2026-10-25
- 관련: `.claude/docs/git-flow.md`, `.claude/hooks/guard-protected-branch.sh`

## 맥락
- 지금까지 `main`에 직접 커밋했다.
- AI 에이전트가 작업을 나누어 진행하므로, 작업 단위를 격리하고 배포 가능한 상태를 보호해야 한다.

## 결정
- Git Flow를 도입한다: `main`(배포), `develop`(통합), `feature/*`, `release/*`, `hotfix/*`.
- 병합은 `--no-ff`로 하고, 버전은 SemVer로 `main`에 태그한다.
- `main`·`develop`에 직접 커밋하는 것을 PreToolUse 훅으로 차단한다.
- 병합·push·PR은 사용자가 승인한 뒤에만 한다.

## 근거
| 대안 | 버린 이유 |
|---|---|
| GitHub Flow (`main` + feature) | 사용자가 Git Flow를 지정함. 앱 클라이언트와 버전을 맞춰 릴리스하려면 release 브랜치가 유리함 |
| squash 병합 | 기능 브랜치의 의미 단위 커밋(FR ID 포함)이 사라져 회고 추적성이 떨어짐 |
| 규칙만 문서화 | ADR-0002와 같은 이유로, 강제하지 않으면 지켜지지 않음 |

## 결과
- 작업은 `develop`에서 분기한 `feature/*`에서 시작한다.
- 원격 저장소의 기본 브랜치와 브랜치 보호 규칙 설정은 미결 결정(D-04)으로 남는다.
