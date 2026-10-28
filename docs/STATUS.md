# Pik Server 현황

> 프로젝트 기록의 진입점. 작업이 끝날 때마다 이 파일을 최신 상태로 덮어쓴다.
> 결정 근거는 `adr/`, 날짜별 진행 기록은 `devlog/`에 둔다.

- 최종 갱신: 2026-10-29
- 현재 단계: **2. 공통 기반 진행 중** (2-1·2-2 완료, 2-3 검토 완료·PR 대기)

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
| 2 | 공통 기반 (모듈 골격, ProblemDetail, 멱등 기록, 보안, 구조화 로그) + 익명 주체 발급 (ADR-0020) | 진행 중: 2-1 공통 기반·2-2 멱등 기록 완료, 2-3 익명 인증 (`feature/stage2-anonymous-auth`, ADR-0025~0027) |
| 3 | 동의 기록 (FR-O-02): 단일 동의 + 목적 필드, 철회 처리 | 대기 |
| 4 | 이용량 (FR-B-01): 예약·반환, 기기·계정 이중 집계, 재설치 판별 | 대기 |
| 5 | 분석 접수·묶음 판단 (FR-I-01·02) → 분석 (FR-I-03) → 액션·이벤트 생성 (FR-G-01~03, 웹 연결 제외) | 대기. 선행: 유사도 벡터 모델 클라이언트 합의, D-09 |
| 6 | 3회 클릭 집계 (FR-B-01), 웹 연결 (FR-X-06), FR-G-04 | 대기 |
| 7 | 로그인·로그아웃 (FR-A-01·03) | 대기 |
| 8 | 구독 (FR-B-02~05) | 대기. 선행: D-12 |
| 9 | 귀속·복원 (FR-A-02·04), 메타데이터 동기화 (FR-L-05), 이벤트 (FR-E), 알림 (FR-N-02~04) | 대기. 선행: FR-A-04 기술 검증 (PHCloudIdentifier·재설치 복원, D-20), Google Places 국내 품질 측정, P10 |
| 10 | 탈퇴 (FR-A-05) | 대기. 선행: 활성 구독 안내 |
| 11 | 검색 해석 (FR-S-01~04), 장소·번역 (FR-X-03·05) | 대기. 선행: D-08, D-14 검색 항목 |

> 순서 확정: 2026-10-26 사용자 결정. 원문 우선 검토 대상 (FR-G-01~04)에 가장 빨리 도달하되 주체·동의·이용량을 먼저 만든다. 근거 결정: ADR-0020·0021.

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
- 기술 스택·인프라·외부 연동 설계 확정
  - 아키텍처·스택 (D-03 해소, [ADR-0014](adr/0014-application-architecture-and-stack.md))
  - AWS 인프라·비밀 값 주입 (D-05 해소, [ADR-0015](adr/0015-aws-infrastructure-and-deployment.md))
  - 외부 AI·분석 파이프라인·외부 전송 동의 범위 ([ADR-0016](adr/0016-external-ai-and-analysis-pipeline.md))
  - 검색 구조: 자연어 해석은 서버, 실행은 단말 (D-02 해소, [ADR-0017](adr/0017-search-interpret-on-server-execute-on-device.md))
  - 구독 검증 RevenueCat ([ADR-0018](adr/0018-subscription-via-revenuecat.md))
  - 서버 중심 알림 ([ADR-0019](adr/0019-server-centric-notification-delivery.md))
- 계정·비로그인 주체 정책: 게스트 귀속·계정 병합·로그아웃 (P07 일부), 익명 식별·재설치 체험·탈퇴 최소 기록 (P08 일부) ([ADR-0020](adr/0020-account-and-anonymous-subject-policy.md))
- 데이터 모델 기반 결정: 사진 식별 키, 묶음 판단 위치, 이용량 차감, 파이프라인 확정, 동의 철회·단위, 원본 삭제 후 메타데이터, 체험 이중 집계 ([ADR-0021](adr/0021-data-model-foundation-decisions.md))
- 서버 ER 설계안 초안: 37개 테이블, ADR-0014~0020 반영 (`docs/erd/pik.erd.json`)
- ER 설계안을 ADR-0020·0021에 맞춤 (38개 테이블). 스키마 공통 판단 (D-15 해소)·분석 접수 단위·기기 간 사진 식별 범위 결정 ([ADR-0022](adr/0022-schema-rules-analysis-request-and-photo-identity.md))
- 단계 2 공통 기반 세부 값: 데이터 버전 위치, 오류 응답 구조·오류 코드 해석·공통 응답 필드, 토큰 수명·익명 토큰·refresh 재사용 유예, API 버전, 멱등 기록, JWT, 로그, 기기 증명 대역, 모듈 골격 ([ADR-0023](adr/0023-stage2-foundation-details.md))
- 구현 전 미결 결정 일괄 확정: 서버 우선 원칙, 체험 반환·교체 없음·3회 클릭 집계 (P01), 비로그인 이벤트 서버 판단·액션 모듈·정리 상태 동기화 (D-16 해소), 재접수·배치 귀속 (D-19 해소), 벡터 보관·수정 사진·대표 삭제 (P05), 웹 탐색 모델·RevenueCat·알림 제안 확정 (D-14 일부), 발송 대상 기기 (P09, D-18 해소), 빈 이벤트 숨김 (P11), 검색 호출 제한 (D-17 해소), 지도 연결·번역·장소 좌표 (D-10 해소). ER 41개 테이블 ([ADR-0024](adr/0024-pre-implementation-decisions.md))
- 단계 2-1 공통 기반: Modulith `shared`·`account` 모듈 골격과 경계 검증, `API-Version` 헤더, ProblemDetail 오류 형식 (`/error` 컨트롤러 포함), 구조화 로그 허용 목록·예외 메시지를 뺀 stack trace, 가상 스레드 ([ADR-0025](adr/0025-stage2-implementation-conventions.md))
- 단계 2-2 멱등 기록: V1 마이그레이션 (멱등 기록·변경 번호·outbox·ShedLock), 멱등 실행기 (advisory 잠금, 4xx = 전체 롤백 후 새 트랜잭션에 기록), `@IdempotentCommand`·키 검사, 오류 응답 `jobId`·`result`, 만료 정리 배치, 변경 번호 발급 ([ADR-0026](adr/0026-idempotency-implementation.md))
- 단계 2-3 익명 인증: V2 마이그레이션 (주체·설치·기기), ES256 access JWT 발급·검증 (JWK Set, 이전 키 검증, `kid` 필수), `POST /installs` (기기 증명 대역, 서버 발급 설치 ID)·`POST /auth/token`, 기본 거부 보안 필터 체인, 인증 실패 응답 (`WWW-Authenticate`, 변경 요청 `jobId`), 로그 `principal_id`. 대역·임시 키는 `local`·`test` 프로필에서만 기동 ([ADR-0027](adr/0027-anonymous-auth-implementation.md))

## 다음 할 일
- [ ] 단계 2-3 PR 병합 (`feature/stage2-anonymous-auth`, 검토 APPROVE, ADR-0027 확정)
- [ ] 실제 기기 증명 검증 (App Attest·Play Integrity)과 challenge 발급 API 구현: 단계 2는 대역만 사용하며, 구현 전에는 `local`·`test` 밖에서 기동이 실패한다. Apple 키·Google Cloud 서비스 계정 준비 후, 클라이언트 연동·운영 배포 전 (ADR-0020·0023·0027)
- [ ] 운영 JWK Set (ES256) 생성·보관·교체 절차 (배포 환경 구성 때, D-13과 함께, ADR-0027)
- [ ] 유사도 벡터 모델을 클라이언트와 합의 (단계 5 전, ADR-0021)
- [ ] TypeSafe Jev 직접 API early access 신청, 데이터 보관·처리 국가 확인 (D-08)
- [ ] Haiku 5.5·Jev 측정용 실제 사진·검색어 표본 구성 (D-07)
- [ ] 분석 작업·접수 단위 상태명 확정 (단계 5 구현 시, ADR-0016·0022)
- [ ] Terraform 인프라 코드·CD 파이프라인 작성 (배포 환경 구성 전, D-13 선행, ADR-0015)
- [ ] ER 설계안을 Flyway 마이그레이션에 반영: 공통 인프라 4개는 V1 완료 (ADR-0026), 주체·설치·기기는 V2 완료 (ADR-0027), 나머지는 각 단계 (ADR-0022)
- [ ] FR-A-04 기술 검증: PHCloudIdentifier 신뢰성 (앨범 iCloud 식별자 지원 포함), 같은 기기 재설치 때 이전 연결 찾기 (단계 9 전, D-20, ADR-0022·0024)
- [ ] Google Places 국내 장소 품질 측정: 공연장·카페·주소 표본 (단계 9 전, D-07 표본과 함께, ADR-0024)
- [ ] 동의 안내에 Google (장소 조회) 추가, 지도 앱 URL 스킴 확인 (카카오맵·네이버 지도), RevenueCat 복원 동작 기본값 유지 확인 (ADR-0024)
- [ ] 유사도 벡터 저장 형식 (pgvector / bytea)·양자화 결정 (단계 5, ADR-0024)
- [ ] FR-G-03 "원본 변경 시 연결·표시 재검증" 규칙 정의 (단계 5 이벤트 생성 구현 때, ADR-0024 #6-1)
- [ ] Google Places 후보를 Pik 화면에 보일 때 출처 표기 의무 확인, 자동 숨김 구분 열 (`hidden_reason`) 여부 결정, 게스트 사진 귀속 때 분석 결과·차감 기록을 잇는 방법 (단계 9 전, ADR-0024)

## 미결 결정
> SessionStart 훅이 이 표를 읽어 작업 시작 보고에 사용한다. 해소되면 행을 지우고 완료에 ADR 링크를 남긴다.

| ID | 결정 사항 | 선택지 | 필요 시점 | 막는 작업 |
|---|---|---|---|---|
| D-01 | 미정 정책 P01~P11 확정 (P01 확정, P05·P07·P08·P09·P11 일부 확정, ADR-0020~0022·0024) | `.claude/docs/open-policies.md` 초안 참고 | 각 FR 구현 전 | FR-B, FR-I, FR-E, FR-N 인수 조건 |
| D-06 | CI 액션 버전 고정 방식 | 메이저 태그(현재) / 커밋 SHA + Dependabot | 저장소 공개·외부 기여 전 | 공급망 보안 강화 |
| D-07 | 분석 모델 재결정 | Haiku 5.5 유지 / 경로별 Sonnet 5.5·Opus 5.5 상향 (실제 사진 표본 측정 기반, 추론 토큰 포함 비용) | 단계 5 측정 후 | 분석 정확도·비용, P03 |
| D-08 | Jev 이용 조건 | 직접 API (early access) / Vercel AI Gateway. 데이터 보관·학습·처리 국가 확인 | 검색 구현·동의 안내 확정 전 | FR-S-01·02, FR-O-02 안내 문구 |
| D-09 | 단말 OCR 여부·축소 해상도 (묶음 판단·대표만 전송은 ADR-0021) | 측정 (D-07)과 함께 결정 | 단계 5 전 | FR-I-01·03, 클라이언트 설계 |
| D-11 | 응답 시간 목표 (SLO) | 제안: 검색 해석 Basic p95 600ms·Pro p95 1.5초, API p95는 부하 측정 후 | 부하 테스트·알람 설정 전 | 용량 산정, 알람 기준 |
| D-12 | RevenueCat 요금제·계약 | - | 단계 8 전 | FR-B-03·04 |
| D-13 | 인프라 비용 선택 | NAT Gateway AZ별 배치 (가용성) / 1개 (비용), RDS 인스턴스 크기, RDS Proxy 도입 여부 (도입하면 커넥션 초기화 SQL 대신 DB·역할 기본 격리 수준 고정, ADR-0026 #18) | 배포 환경 구성 전 | 인프라 코드 |
| D-14 | ADR-0017·0019의 `(제안)` 항목 확인 (나머지는 ADR-0024에서 확정) | 검색 구현 세부: 폴더마다 Jev `noul` 질문 / 폴더를 선택지로 한 번에 묻는 `choice`, 상대 날짜 토큰, 캐시 단위. Pro LLM 초기값 Haiku 5.5 (설정값). 알림: 분석 완료 화면 갱신 = 상태 조회 (ETag), 장소 알림 서버 확인 때 오프라인이면 마지막 동기화 상태로 판단 (ADR-0019) | 검색은 D-08 확인 후, 알림은 단계 9 전 | FR-S-01·02, FR-I-06, FR-N-02 |
| D-20 | iCloud 사진 photoId 수렴, 재설치 때 연결 이관 | 두 번째 기기가 만든 photoId를 같은 `cloud_ref`의 서버 photoId로 바꾸는 절차, 이전 설치 (`device` 행)의 `photo_device_link`와 `organize_state` DEVICE 키를 새 설치로 옮기는 방법 (iOS는 기기 식별자 없음). FR-A-04 기술 검증과 함께 결정 | 단계 9 전 | FR-A-02·04, FR-L-05 (ADR-0022) |
| D-21 | 기기 증명 challenge 방식 (실제 검증 구현 때) | 서버 발급 nonce API (유효 시간·1회 사용) / 요청 해시 기반 (App Attest `clientDataHash`, Play Integrity `requestHash`) | 실제 기기 증명 구현 전 (운영 배포 전) | 운영 배포 (`local`·`test` 밖 기동 불가, ADR-0027) |
| D-22 | 운영자용 actuator 접근 | 별도 관리 포트 (내부망) / 운영자 인증 (access token과 분리) | 지표·운영 endpoint 노출 전 | 모니터링 연동 (현재 health만 공개, 나머지 403, ADR-0027) |

## 위험·제약
- 저장소가 공개 상태라 요구사항 원문 (`docs/spec`)·ADR·`.claude/docs`가 공개된다. 사용자가 수용했다 (2026-10-26). 비공개가 필요해지면 이력에서 제거한 뒤 저장소 재생성 또는 Private 전환을 검토한다 (Free 플랜이면 Private 저장소에 Ruleset 미적용).
- 미정 정책이 많아 FR 인수 조건이 불완전하다. 구현 시 하드코딩하지 않고 설정값으로 분리해야 한다.
- Jev는 2026-09 출시된 early access 제품이다 (출시 보도, 2026-10-25 웹 검색으로 확인). 속도·정확도는 업체 발표뿐이고 데이터 보관 정책이 공식 문서에 없다 (D-08).
- 로그인 사용자의 이벤트 시간 알림을 서버가 발송하는 구조는 FR-N-02 원문("OS 예약·취소")과 다르다. 사용자 결정으로 채택했다 (ADR-0019).
- 무료 검색 100장 범위 제한은 단말이 적용하므로 앱 변조로 우회할 수 있다. 서버 비용 영향은 없다 (ADR-0017, P02).
- 장소 알림의 서버 확인에는 위치 권한 '항상 허용'이 필요하다. '앱 사용 중 허용'이면 서버 확인 없이 표시되어, 다른 기기에서 완료한 이벤트의 장소 알림이 울릴 수 있다 (수용 위험, ADR-0019).
- 권한 요청 알림은 단말이 마지막으로 보고한 권한 상태로 판단해, FR-N-04의 "발송 시 재확인"과 차이가 있다 (ADR-0019, 확정: ADR-0024).
- 수정된 사진은 재분석하지 않는다. 크롭·편집 뒤에도 이전 분석 결과가 남아 액션·이벤트가 실제 사진과 다를 수 있다 (사용자 결정, ADR-0024).
- RevenueCat 복원 동작이 "항상 새 계정으로 이전"이라, 스토어 계정 하나로 여러 Pik 계정 사이에서 활성 구독을 옮길 수 있다 (한 번에 한 계정만 Pro). RevenueCat 장애가 길면 만료된 사용자가 Pro를 계속 쓴다 (사용자 결정, ADR-0024).
- SSOT 오류 표에 호출 제한 코드가 없어 `RATE_LIMITED` (429)를 `api-contract.md`에 추가했다. SSOT 반영 여부는 사용자가 정한다 (ADR-0024).
- 장소 알림 좌표는 Google Places로 얻는다. 국내 작은 상호의 품질이 부족할 수 있고, 좌표는 30일마다 갱신해야 한다. 카카오 로컬 API는 결과 저장 금지·별도 과금 기능 금지 조항 때문에 쓰지 않는다 (ADR-0024).
- 앱이 30일 넘게 실행되지 않으면 단말에 있는 장소 좌표 (비로그인 이벤트, OS 지오펜스·위치 알림)를 갱신도 삭제도 할 수 없다. iOS는 만료를 걸 수 없어 기한이 지난 좌표가 OS에 남을 수 있고, 기한에 지우면 장소 알림이 알리지 않고 멈춘다. 지도 액션 (지도 앱 검색)과 장소 알림 (Google)이 다른 장소를 가리킬 수 있다 (ADR-0024).
- 이벤트의 사진이 모두 빠지면 원인과 관계없이 숨긴다. 정리 중에 다가올 일정의 사진을 지우면 보관 기간 뒤 이벤트가 숨겨지고 알림이 취소되며, 되살리지 않는다 (사용자 결정, ADR-0024).
- RevenueCat 장애 중 Pro 유지는 FR-B-04 예외 "일시 장애는 권한을 임의 확대하지 않는다"와 상충해, FR-B-05를 따르는 쪽으로 해석했다. 지도 액션의 후보 선택은 FR-X-03과 달리 지도 앱에 맡긴다 (ADR-0024).
- 호출 제한은 주체 단위라, 재설치로 새 익명 주체가 생기면 한도가 초기화된다 (ADR-0024).
- 요구사항 140·948행 (같은 사진의 다른 기기 ID 매칭)과 FR-A-04·시나리오 A-03 (기기 변경 후 복원)을 iCloud 동기화 사진으로 한정한다. 영향은 두 가지다. 첫째, Android 사용자와 iCloud를 쓰지 않는 iOS 사용자는 기기를 바꾸면 기존 Pik 정보가 새 기기의 사진에 연결되지 않는다. 둘째, 한 계정을 iCloud로 묶이지 않은 기기 여러 대에서 쓰면 (예: iPhone + Android, iCloud 없는 iPhone + iPad) 같은 내용의 사진이 기기마다 별도 사진이 된다. 그래서 메모·태그·이벤트 연결이 기기 사이에 이어지지 않고, 분석과 이용량 차감도 기기마다 따로 일어난다. 같은 기기 재설치는 OS 로컬 ID로 복원할 수 있으며, 방법은 D-20에서 정한다. 사용자 결정 (ADR-0022).
- SSOT 오류 표의 "FORBIDDEN 또는 PERMISSION_REQUIRED"를 서버 `FORBIDDEN`·단말 `PERMISSION_REQUIRED`로 나눠 해석하고, 다른 주체의 데이터는 `NOT_FOUND`로 응답한다. `UNAUTHENTICATED`는 "로그인 전용 호출"보다 넓게, 익명 access JWT 만료·설치 토큰 폐기에도 쓴다 (ADR-0023).
- FR-S-01의 "Jev 기초 분류"를 사용자 정의에 따라 "검색어 → 폴더 관련도"로 해석했다. 사진 단위 Jev 분류는 하지 않는다 (ADR-0017).
