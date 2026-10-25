# ADR (Architecture Decision Records)

결정과 그 근거를 남긴다. 결정이 바뀌면 기존 ADR을 고치지 않고 새 ADR을 추가한 뒤, 기존 ADR의 상태를 `대체됨 (ADR-NNNN)`으로 바꾼다.

## 작성 규칙
- 파일명: `NNNN-<kebab-slug>.md` (번호는 4자리, 증가만)
- 양식: [`template.md`](template.md)
- 상태: `제안` → `채택` → (`대체됨 (ADR-NNNN)` | `폐기`)
- 근거를 모르는 결정은 추측해서 채우지 않고 `근거 미기록`으로 표시한다
- 관련 FR ID와 커밋 해시를 적는다

## 목록
| 번호 | 제목 | 상태 | 날짜 |
|---|---|---|---|
| [0001](0001-spec-as-read-only-ssot.md) | 요구사항 문서를 읽기 전용 SSOT로 둔다 | 채택 | 2026-10-25 |
| [0002](0002-claude-md-split-and-size-limit.md) | CLAUDE.md 200줄 제한과 보조 문서 분리 | 채택 | 2026-10-25 |
| [0003](0003-tech-stack-java21-spring-boot.md) | Java 21 + Spring Boot + Gradle | 채택 (근거 미기록) | 2026-10-25 |
| [0004](0004-env-and-secret-management.md) | 환경 변수·비밀 값 관리 방식 | 채택 (근거 일부 미기록) | 2026-10-25 |
| [0005](0005-server-scope-metadata-only.md) | 서버는 Pik 메타데이터만 소유한다 | 채택 | 2026-10-25 |
| [0006](0006-open-policies-as-config.md) | 미정 정책 값은 설정값으로 분리한다 | 채택 | 2026-10-25 |
| [0007](0007-project-record-system.md) | STATUS·ADR·devlog 기록 체계 | 채택 | 2026-10-25 |
| [0008](0008-git-flow-branching.md) | Git Flow 브랜치 전략 | 채택 | 2026-10-25 |
| [0009](0009-decision-reporting-per-task.md) | 작업 시작·종료 시 결정 사항 보고 | 채택 | 2026-10-25 |
| [0010](0010-project-skeleton.md) | 프로젝트 골격 구성 | 채택 (근거 일부 미기록) | 2026-10-25 |
