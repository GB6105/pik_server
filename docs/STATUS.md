# Pik Server 현황

> 프로젝트 기록의 진입점. 작업이 끝날 때마다 이 파일을 최신 상태로 덮어쓴다.
> 결정 근거는 `adr/`, 날짜별 진행 기록은 `devlog/`에 둔다.

- 최종 갱신: 2026-10-26
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
| 2 | 공통 기반 (모듈 골격, ProblemDetail, 멱등 기록, 보안, 구조화 로그) + 익명 주체 발급 (ADR-0020) | 대기 |
| 3 | 동의 기록 (FR-O-02): 단일 동의 + 목적 필드, 철회 처리 | 대기 |
| 4 | 이용량 (FR-B-01): 예약·반환, 기기·계정 이중 집계, 재설치 판별 | 대기 |
| 5 | 분석 접수·묶음 판단 (FR-I-01·02) → 분석 (FR-I-03) → 액션·이벤트 생성 (FR-G-01~03, 웹 연결 제외) | 대기. 선행: 유사도 벡터 모델 클라이언트 합의, 벡터 보관 범위 |
| 6 | 3회 클릭 집계 (FR-B-01), 웹 연결 (FR-X-06), FR-G-04 | 대기. 선행: P01 클릭 집계 기준 |
| 7 | 로그인·로그아웃 (FR-A-01·03) | 대기 |
| 8 | 구독 (FR-B-02~05) | 대기. 선행: D-12, D-14 RevenueCat 항목 |
| 9 | 귀속·복원 (FR-A-02·04), 메타데이터 동기화 (FR-L-05), 이벤트 (FR-E), 알림 (FR-N-02~04) | 대기. 선행: FR-A-04 지문 기술 검증, P09, P10 |
| 10 | 탈퇴 (FR-A-05) | 대기. 선행: 활성 구독 안내 |
| 11 | 검색 해석 (FR-S-01~04), 장소·번역 (FR-X-03·05) | 대기. 선행: D-08, D-10, D-14 검색 항목 |

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
- 서버 ER 설계안 초안: 37개 테이블, ADR-0014~0020 반영 (`docs/erd/pik.erd.json`). 제안 단계라 ADR 없음, 미결은 D-15~D-18

## 다음 할 일
- [ ] 단계 2 착수: Modulith 모듈 골격, `ProblemDetail` 오류 형식, 멱등 기록, JWT 보안 설정, 구조화 로그 (ADR-0014), 익명 주체 발급 (ADR-0020), 첫 Flyway 마이그레이션
- [ ] ERD 초안 (`feature/erd-draft`)과 ADR-0020·0021 정합성 확인 (단계 2 엔티티 설계 전)
- [ ] 유사도 벡터 모델을 클라이언트와 합의 (단계 5 전, ADR-0021)
- [ ] TypeSafe Jev 직접 API early access 신청, 데이터 보관·처리 국가 확인 (D-08)
- [ ] Haiku 5.5·Jev 측정용 실제 사진·검색어 표본 구성 (D-07)
- [ ] 분석 작업 상태명 확정 (단계 5 구현 시, ADR-0016)
- [ ] Terraform 인프라 코드·CD 파이프라인 작성 (배포 환경 구성 전, D-13 선행, ADR-0015)
- [ ] ER 설계안 미결 결정 (D-15~D-18) 확인 후 첫 Flyway 마이그레이션에 반영

## 미결 결정
> SessionStart 훅이 이 표를 읽어 작업 시작 보고에 사용한다. 해소되면 행을 지우고 완료에 ADR 링크를 남긴다.

| ID | 결정 사항 | 선택지 | 필요 시점 | 막는 작업 |
|---|---|---|---|---|
| D-01 | 미정 정책 P01~P11 확정 (P01·P05·P07·P08·P11 일부 확정, ADR-0020·0021) | `.claude/docs/open-policies.md` 초안 참고 | 각 FR 구현 전 | FR-B, FR-I, FR-E, FR-N 인수 조건 |
| D-06 | CI 액션 버전 고정 방식 | 메이저 태그(현재) / 커밋 SHA + Dependabot | 저장소 공개·외부 기여 전 | 공급망 보안 강화 |
| D-07 | 분석 모델 재결정 | Haiku 5.5 유지 / 경로별 Sonnet 5.5·Opus 5.5 상향 (실제 사진 표본 측정 기반, 추론 토큰 포함 비용) | 단계 5 측정 후 | 분석 정확도·비용, P03 |
| D-08 | Jev 이용 조건 | 직접 API (early access) / Vercel AI Gateway. 데이터 보관·학습·처리 국가 확인 | 검색 구현·동의 안내 확정 전 | FR-S-01·02, FR-O-02 안내 문구 |
| D-09 | 단말 OCR 여부·축소 해상도 (묶음 판단·대표만 전송은 ADR-0021) | 측정 (D-07)과 함께 결정 | 단계 5 전 | FR-I-01·03, 클라이언트 설계 |
| D-10 | 장소 해석·번역 제공자 | 추천: 국내 Kakao Local·해외 Google Places / 단말 번역 + 서버 Claude 폴백 | FR-X-03·05 구현 전 | FR-X-03·05, 외부 전송 동의 범위 재검토 (ADR-0016) |
| D-11 | 응답 시간 목표 (SLO) | 제안: 검색 해석 Basic p95 600ms·Pro p95 1.5초, API p95는 부하 측정 후 | 부하 테스트·알람 설정 전 | 용량 산정, 알람 기준 |
| D-12 | RevenueCat 요금제·계약 | - | 단계 8 전 | FR-B-03·04 |
| D-13 | 인프라 비용 선택 | NAT Gateway AZ별 배치 (가용성) / 1개 (비용), RDS 인스턴스 크기 | 배포 환경 구성 전 | 인프라 코드 |
| D-14 | ADR-0014~0019의 `(제안)` 항목 확인 | Pro LLM = Haiku, 웹 탐색 Sonnet 폴백, 검색 구현 세부, RevenueCat 즉시 재조회·구매 이전 설정·장애 시 유예 후 Pro 제한, silent push 미도입·장소 알림 권한, SSOT 해석 (FR-B-03·04 = RevenueCat 결과 조회, FR-N-04 = 마지막 보고 권한 상태로 판단) | 각 구현 착수 전 | 해당 FR 구현 |
| D-15 | ER 설계안의 스키마 공통 판단 6가지 (`docs/erd/pik.erd.json` 설계 판단 메모) | 제안대로: principal 상위 타입, `client_ref` 분리, 모듈 간 FK 없음 (ID만 저장), `owner_change_counter`로 `change_seq` 발급, tombstone 삭제, 분석 결과 이력 없음 / 항목별 수정 | 첫 Flyway 마이그레이션 전 | 단계 2 엔티티 구현 |
| D-16 | ER 설계 중 발견한 SSOT 보고 3건 | 액션 aggregate: 별도 모듈 (추천) / 분석 모듈 안. 비로그인 이벤트 자동 생성: 단말 (추천) / 서버. 정리 상태 동기화 범위·`source_album_ref` 의미: P04·P06·P07과 함께 결정 | 해당 FR 구현 전 | FR-G-02·03, FR-E-01, FR-A-02·04 |
| D-17 | 검색 해석 호출 제한 (비로그인 포함) 집계 위치 | 인스턴스 메모리 (서버 대수만큼 나뉘어 집계) / DB 카운터 | FR-S 구현 전 | `/search/interpret` 남용 방지 (ADR-0017) |
| D-18 | 기기별 알림 발송 기록 테이블 필요 여부 | P09 발송 대상 기기 규칙에 따라 결정 | FR-N 서버 발송 구현 전 | FR-N-02~04 (ADR-0019) |

## 위험·제약
- 저장소가 공개 상태라 요구사항 원문 (`docs/spec`)·ADR·`.claude/docs`가 공개된다. 사용자가 수용했다 (2026-10-26). 비공개가 필요해지면 이력에서 제거한 뒤 저장소 재생성 또는 Private 전환을 검토한다 (Free 플랜이면 Private 저장소에 Ruleset 미적용).
- 미정 정책이 많아 FR 인수 조건이 불완전하다. 구현 시 하드코딩하지 않고 설정값으로 분리해야 한다.
- Jev는 2026-09 출시된 early access 제품이다 (출시 보도, 2026-10-25 웹 검색으로 확인). 속도·정확도는 업체 발표뿐이고 데이터 보관 정책이 공식 문서에 없다 (D-08).
- 로그인 사용자의 이벤트 시간 알림을 서버가 발송하는 구조는 FR-N-02 원문("OS 예약·취소")과 다르다. 사용자 결정으로 채택했다 (ADR-0019).
- 무료 검색 100장 범위 제한은 단말이 적용하므로 앱 변조로 우회할 수 있다. 서버 비용 영향은 없다 (ADR-0017, P02).
- 장소 알림의 서버 확인에는 위치 권한 '항상 허용'이 필요하다. '앱 사용 중 허용'이면 서버 확인 없이 표시되어, 다른 기기에서 완료한 이벤트의 장소 알림이 울릴 수 있다 (수용 위험, ADR-0019).
- 권한 요청 알림은 단말이 마지막으로 보고한 권한 상태로 판단해, FR-N-04의 "발송 시 재확인"과 차이가 있다 (ADR-0019).
- FR-S-01의 "Jev 기초 분류"를 사용자 정의에 따라 "검색어 → 폴더 관련도"로 해석했다. 사진 단위 Jev 분류는 하지 않는다 (ADR-0017).
