# Git Flow

근거: `docs/adr/0008-git-flow-branching.md`

## 브랜치
| 브랜치 | 분기 원천 | 병합 대상 | 용도 |
|---|---|---|---|
| `main` | - | - | 배포된 버전만. 태그 `vX.Y.Z` |
| `develop` | `main` | - | 다음 릴리스 통합 |
| `feature/<FR-ID>-<slug>` | `develop` | `develop` | 기능·문서·설정 작업 단위 |
| `release/vX.Y.Z` | `develop` | `main` + `develop` | 릴리스 준비 (버그 수정·버전만) |
| `hotfix/vX.Y.Z` | `main` | `main` + `develop` | 배포 버전 긴급 수정 |

- FR 없는 작업: `feature/<slug>` (예: `feature/project-workflow`)
- 버전: SemVer. release·hotfix 병합 시 `main`에 태그

## 에이전트 규칙
- 작업 시작 = `develop`에서 `feature/*` 분기. `main`·`develop` 직접 커밋 금지 (훅 `guard-protected-branch.sh`가 차단)
- 훅 판정 기준 = 커밋이 실행되는 작업 트리의 브랜치 (`git -C <dir>` → 앞선 `cd <dir>` → 도구 cwd 순)
- worktree 커밋 = `git -C <리터럴 절대 경로> commit`. 셸 변수 경로(`"$W"`)는 훅이 해석 못 해 차단
- 커밋 단위 = 의미 단위. 메시지에 FR ID 명시
- 병합: `git merge --no-ff`. 사용자 승인 후에만
- `push`·PR 생성·원격 브랜치 삭제: 사용자 승인 후에만
- 병합 완료한 `feature/*`는 로컬에서 삭제

## 명령
| 목적 | 명령 |
|---|---|
| 기능 시작 | `git switch develop && git switch -c feature/<name>` |
| 기능 완료 | `git switch develop && git merge --no-ff feature/<name>` |
| 릴리스 시작 | `git switch -c release/vX.Y.Z develop` |
| 릴리스 완료 | `main`에 `--no-ff` 병합 → `git tag vX.Y.Z` → `develop`에 병합 |
| 핫픽스 | `git switch -c hotfix/vX.Y.Z main` → 완료는 릴리스와 동일 |
