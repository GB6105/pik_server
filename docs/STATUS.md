# Pik Server 현황

> 프로젝트 기록의 진입점. 작업이 끝날 때마다 이 파일을 최신 상태로 덮어쓴다.
> 결정 근거는 `adr/`, 날짜별 진행 기록은 `devlog/`에 둔다.

- 최종 갱신: 2026-10-25
- 현재 단계: **1. 프로젝트 골격 완료** → 단계 2 착수 전 (도메인 코드 없음)

## 기록 체계
| 문서 | 경로 | 용도 | 갱신 시점 |
|---|---|---|---|
| 현황 (이 파일) | `docs/STATUS.md` | 지금 어디까지 왔는지, 다음에 할 일 | 작업 종료 시 덮어쓰기 |
| ADR | `docs/adr/NNNN-<slug>.md` | 결정과 근거 (변경 이력) | 결정이 생기거나 바뀔 때 추가 |
| 일일 기록 | `docs/devlog/YYYY-MM-DD.md` | 그날 한 일·막힌 점·회고 메모 | 매일 작업 종료 시 |
| 요구사항 (SSOT) | `docs/spec/` | 기능 요구사항·시나리오 | 읽기 전용 |

## 단계별 진행
| 단계 | 내용 | 상태 |
|---|---|---|
| 0 | 협업 기반: SSOT 배치, CLAUDE.md, 보조 문서, 권한·훅, 기록 체계, Git Flow | 완료 |
| 1 | 프로젝트 골격: Gradle·Spring Boot 초기화, `.env.example`, CI | 완료 |
| 2 | 계정 (FR-A-01~05) | 대기 |
| 3 | 무료 체험·구독 (FR-B-01~05), 동의 기록 (FR-O-02) | 대기 |
| 4 | 분석 작업·자동 생성 (FR-I, FR-G) | 대기 |
| 5 | 동기화·이벤트·알림 (FR-L-05, FR-E, FR-N-02~04) | 대기 |

> 단계 2 이후 순서는 잠정안이다. `.claude/docs/fr-scope.md`의 우선 검토 대상을 반영해 확정한다.

## 완료
- 요구사항 문서를 `docs/spec/`에 읽기 전용 SSOT로 배치 ([ADR-0001](adr/0001-spec-as-read-only-ssot.md))
- CLAUDE.md 협업 계약과 `.claude/docs/` 보조 문서 6종 작성 ([ADR-0002](adr/0002-claude-md-split-and-size-limit.md))
- CLAUDE.md 200줄 제한 훅 추가 ([ADR-0002](adr/0002-claude-md-split-and-size-limit.md))
- 프로젝트 기록 체계(STATUS·ADR·devlog) 도입 ([ADR-0007](adr/0007-project-record-system.md))
- Git Flow 도입: `develop` 생성, 보호 브랜치 직접 커밋 차단 훅 ([ADR-0008](adr/0008-git-flow-branching.md))
- 작업 시작·종료 결정 보고 규칙과 미결 결정 주입 훅 ([ADR-0009](adr/0009-decision-reporting-per-task.md))
- Spring Boot 4.1.2 + PostgreSQL 18 + Flyway 골격, `.env.example` ([ADR-0010](adr/0010-project-skeleton.md))
- GitHub Actions CI: `develop` 첫 실행 성공 ([run 40183429153](https://github.com/GB6105/pik_server/actions/runs/40183429153)) ([ADR-0011](adr/0011-ci-github-actions.md))
- 원격 보호: 기본 브랜치 `develop`, `main`·`develop` PR·`build` 체크 필수 (D-04 해소, [ADR-0013](adr/0013-branch-protection-pr-required.md))
- `develop` 원격 push
- Lombok 제한적 사용: 허용 4종 외 `lombok.config`·Gradle task로 빌드 차단 ([ADR-0012](adr/0012-lombok-restricted-usage.md))

## 다음 할 일
- [ ] 첫 구현 FR 순서 확정
- [ ] 첫 Flyway 마이그레이션 (첫 엔티티와 함께)

## 미결 결정
> SessionStart 훅이 이 표를 읽어 작업 시작 보고에 사용한다. 해소되면 행을 지우고 완료에 ADR 링크를 남긴다.

| ID | 결정 사항 | 선택지 | 필요 시점 | 막는 작업 |
|---|---|---|---|---|
| D-01 | 미정 정책 P01~P11 확정 | `.claude/docs/open-policies.md` 초안 참고 | 각 FR 구현 전 | FR-B, FR-I, FR-E, FR-N 인수 조건 |
| D-02 | 검색 실행 위치 | 단말 / 서버 / 혼합 | FR-S 구현 전 | FR-S-01~04 (확정 전 인터페이스만) |
| D-03 | 기술 스택·골격 선택 근거 보완 (Spring Boot, PostgreSQL, JPA, MVC, 좌표) | - | 단계 2 착수 전 | ADR-0003·0010 완결 |
| D-05 | 배포 비밀 값 주입 경로 | AWS SSM / GitHub Actions secrets | 배포 환경 구성 전 | 배포 (CI 빌드·테스트는 무관) |
| D-06 | CI 액션 버전 고정 방식 | 메이저 태그(현재) / 커밋 SHA + Dependabot | 저장소 공개·외부 기여 전 | 공급망 보안 강화 |

## 위험·제약
- 미정 정책이 많아 FR 인수 조건이 불완전하다. 구현 시 하드코딩하지 않고 설정값으로 분리해야 한다.
