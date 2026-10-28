# pik_server

Pik(사진 정리 앱) iOS·Android 클라이언트의 백엔드. Java 21 + Spring Boot + Gradle.

## 기준 문서 (SSOT)
| 문서 | 경로 |
|---|---|
| 상세 기능 요구사항 (FR) | `docs/spec/Pik 상세 기능 요구사항.md` |
| 사용자 시나리오 | `docs/spec/Pik 사용자 시나리오.md` |

- `docs/spec/**` = 읽기 전용 SSOT. 사용자 명시 허용 없이 수정·삭제·이동·이름 변경 금지
- 요구사항과 충돌·누락 발견 시 SSOT가 아니라 사용자에게 보고
- SSOT와 보조 문서 충돌 시 SSOT 우선, 보조 문서 수정
- 구현 대상 FR ID를 커밋·PR에 명시 (예: `FR-B-03`)

## 작업 전 읽을 보조 문서
| 상황 | 경로 |
|---|---|
| 기능이 서버 구현 대상인지 판단 | `.claude/docs/fr-scope.md` |
| 엔티티·aggregate·패키지 경계 설계, 이벤트·배치 추가 | `.claude/docs/domain-boundaries.md` |
| 트랜잭션·재시도·멱등성·외부 연동 구현 | `.claude/docs/consistency-rules.md` |
| API 응답·오류 코드 정의 | `.claude/docs/api-contract.md` |
| 로그·삭제·탈퇴·보관 기간 처리 | `.claude/docs/data-retention.md` |
| 미정 정책(P01~P12) 관련 값 사용 | `.claude/docs/open-policies.md` |
| 작업 종료·결정 발생 시 기록 | `docs/STATUS.md` 갱신, `docs/devlog/YYYY-MM-DD.md` 작성, 결정은 `docs/adr/` 추가 |
| 브랜치 생성·커밋·병합·릴리스 | `.claude/docs/git-flow.md` |
| 작업 시작·종료 보고 양식 | `.claude/docs/decision-reporting.md` |

## 작업 단위 계약
- 작업(요청 하나 또는 하위 태스크 하나) 시작 = `develop`에서 `feature/*` 분기. `main`·`develop` 직접 커밋 금지
- 시작 보고: 필요한 결정(선택지·추천·근거) + 관련 미결 결정(`docs/STATUS.md` `D-NN`)
- 종료 보고: 내린 결정(근거·ADR) + 신규·잔존 미결 결정
- 병합·push·PR = 사용자 승인 후. `main`·`develop` 병합은 GitHub PR로만 (직접 push는 원격이 거부)

## CLAUDE.md 유지 규칙
- 200줄 이하 유지. 초과 시 세부 내용을 `.claude/docs/<주제>.md`로 분리, 위 상황→경로 표에 행 추가
- 이 파일에는 항상 필요한 계약만. 상황별 세부 규칙은 보조 문서
- `@import` 금지 (전량 상시 로드됨)
- 분리 대상에 `docs/spec/**` 사용 금지 (SSOT 전용)

## 명령
| 목적 | 명령 |
|---|---|
| 빌드 | `./gradlew build` |
| 테스트 | `./gradlew test` |
| 실행 | `./gradlew bootRun` |
| CI 워크플로 검사 | `docker run --rm -v "$PWD:/repo" -w /repo rhysd/actionlint:latest -no-color` |

- 성공 판정은 exit code로. `| tail` 등 파이프로 결과 가리지 않기
- Java toolchain 21 고정. 21 이후 preview 기능 사용 금지
- JDK = `mise.toml` (temurin-21). 셸에 mise 미활성 시 `mise exec -- ./gradlew ...`
- `test`·`build` = Docker 필요 (Testcontainers PostgreSQL). `bootRun` = Docker + `.env.local` 필요 (compose.yaml)
- `test` = `test` 프로필, `bootRun`·`bootTestRun` = `local` 프로필 자동 적용. 그 밖의 프로필은 기동 실패 (기기 증명 대역·임시 JWT 키, ADR-0027). IDE 실행 시 프로필 직접 지정
- 같은 프로젝트에서 Gradle 빌드 동시 실행 금지 (테스트 결과 파일 충돌로 `:test` 실패)
- 병렬 세션 = 세션마다 별도 git worktree. 같은 작업 트리 공유 금지
- DB 스키마 변경 = Flyway 마이그레이션만 (`ddl-auto: validate`)

## 환경 변수·비밀 값
| 환경 | 소스 | git |
|---|---|---|
| 로컬 개발 | `.env.local` | 제외 |
| 배포 서버 | ECS task `secrets` ← AWS SSM Parameter Store, DB 비밀번호는 RDS 관리형 Secrets Manager (CI → AWS = GitHub OIDC, ADR-0015) | 해당 없음 |
| 키 목록 공유 | `.env.example` (값 없이 키만) | 포함 |

- 새 환경 변수 추가 시 `.env.example`에 키 추가
- `.env`·`.env.local` 내용 읽기·출력 금지. 필요한 키는 사용자에게 질문

## 코드 규약
- Lombok 허용 = `@Getter`, `@NoArgsConstructor`, `@RequiredArgsConstructor`, `@Slf4j`만. 그 외는 `lombok.config`가 컴파일 오류로 차단
- 엔티티 `@NoArgsConstructor`는 `access = AccessLevel.PROTECTED` 필수 (config로 강제 불가)
- 엔티티 상태 변경 = 도메인 메서드만 (setter 금지)
- DTO·커맨드·이벤트·값 객체 = `record`

## 불변 규칙
- 사진 원본·실제 앨범 구성은 서버 소유 아님. 서버는 Pik 메타데이터만 보관
- 입력 ID만으로 소유권 인정 금지. 요청 주체 기준 소유 범위 검증
- 계정·비로그인 주체가 다른 데이터 자동 병합 금지
- OS 사진 변경·AI 요청·플랫폼 결제를 DB 트랜잭션에 포함 금지
- 변경 요청은 작업 식별자 + 데이터 버전 필수. 재전송 시 중복 처리 금지
- 장기 작업 접수 응답 = 작업 ID + 진행 상태. 완료로 표시 금지
- 사진·계정·OCR 본문 전체를 로그에 기록 금지
- 미정 정책 값 하드코딩 금지 → 설정값으로 분리

## 금지
- 요구사항에 없는 기능 임의 추가 (첫 출시 제외 범위: `open-policies.md` 하단)
- 단말 전용 FR을 서버에 구현
- 비밀 값(키·토큰·인증서)을 코드·설정 파일·커밋에 기록
- 권한·차단 규칙(`.claude/settings.json` deny, `.claude/hooks/`) 임의 완화. 막히면 중단하고 사용자 승인 요청
