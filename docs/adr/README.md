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
| [0003](0003-tech-stack-java21-spring-boot.md) | Java 21 + Spring Boot + Gradle | 채택 (사후 근거: 0014) | 2026-10-25 |
| [0004](0004-env-and-secret-management.md) | 환경 변수·비밀 값 관리 방식 | 채택 (배포 서버 주입은 0015로 대체) | 2026-10-25 |
| [0005](0005-server-scope-metadata-only.md) | 서버는 Pik 메타데이터만 소유한다 | 채택 | 2026-10-25 |
| [0006](0006-open-policies-as-config.md) | 미정 정책 값은 설정값으로 분리한다 | 채택 | 2026-10-25 |
| [0007](0007-project-record-system.md) | STATUS·ADR·devlog 기록 체계 | 채택 | 2026-10-25 |
| [0008](0008-git-flow-branching.md) | Git Flow 브랜치 전략 | 채택 (병합 방식은 0013으로 대체) | 2026-10-25 |
| [0009](0009-decision-reporting-per-task.md) | 작업 시작·종료 시 결정 사항 보고 | 채택 | 2026-10-25 |
| [0010](0010-project-skeleton.md) | 프로젝트 골격 구성 | 채택 (사후 근거: 0014) | 2026-10-25 |
| [0011](0011-ci-github-actions.md) | GitHub Actions CI (빌드·테스트) | 채택 | 2026-10-25 |
| [0012](0012-lombok-restricted-usage.md) | Lombok 제한적 사용 (허용 목록) | 채택 | 2026-10-25 |
| [0013](0013-branch-protection-pr-required.md) | 원격 브랜치 보호: PR·CI 필수 | 채택 | 2026-10-25 |
| [0014](0014-application-architecture-and-stack.md) | 서버 애플리케이션 아키텍처와 기술 스택 | 채택 | 2026-10-25 |
| [0015](0015-aws-infrastructure-and-deployment.md) | AWS 인프라와 배포·비밀 값 주입 | 채택 | 2026-10-25 |
| [0016](0016-external-ai-and-analysis-pipeline.md) | 외부 AI 연동, 사진 분석 파이프라인, 외부 전송 동의 범위 | 채택 (파이프라인 확정·접수 단계 대체: ADR-0021, 웹 탐색 모델·장소 제공자: ADR-0024) | 2026-10-25 |
| [0017](0017-search-interpret-on-server-execute-on-device.md) | 검색 구조: 자연어 해석은 서버, 실행은 단말 | 채택 (호출 제한: ADR-0024) | 2026-10-25 |
| [0018](0018-subscription-via-revenuecat.md) | 구독 검증은 RevenueCat으로 연동한다 | 채택 (제안 확정·일부 대체: ADR-0024) | 2026-10-25 |
| [0019](0019-server-centric-notification-delivery.md) | 알림은 서버 중심으로 전달한다 | 채택 (제안 일부 확정·발송 대상: ADR-0024) | 2026-10-25 |
| [0020](0020-account-and-anonymous-subject-policy.md) | 계정·비로그인 주체 정책 (P07·P08 일부) | 채택 | 2026-10-26 |
| [0021](0021-data-model-foundation-decisions.md) | 데이터 모델 기반 결정 (사진 식별·묶음·이용량·동의·원본 삭제) | 채택 (#1 기기 간 식별 일부 대체: ADR-0022, 미정 항목 일부 확정: ADR-0024) | 2026-10-26 |
| [0022](0022-schema-rules-analysis-request-and-photo-identity.md) | 스키마 공통 규칙·분석 접수 단위·기기 간 사진 식별 범위 | 채택 (D-19·벡터 보관 확정, 재접수 유니크 변경: ADR-0024) | 2026-10-26 |
| [0023](0023-stage2-foundation-details.md) | 단계 2 공통 기반 세부 결정 (API·오류·멱등·인증·로그) | 채택 (429 확정: ADR-0024, 잠금 방식: ADR-0026) | 2026-10-26 |
| [0024](0024-pre-implementation-decisions.md) | 구현 전 미결 결정 일괄 확정 (서버 우선 원칙, 체험·분석·이벤트·구독·알림·검색·장소) | 채택 | 2026-10-27 |
| [0025](0025-stage2-implementation-conventions.md) | 단계 2 구현 규약 (하위 작업 분할·오류 대응·로그·토큰 발급) | 채택 | 2026-10-28 |
| [0026](0026-idempotency-implementation.md) | 멱등 기록 구현 방식 (잠금·4xx 롤백·변경 요청 구분·내부 이벤트 완료 처리) | 채택 | 2026-10-28 |
