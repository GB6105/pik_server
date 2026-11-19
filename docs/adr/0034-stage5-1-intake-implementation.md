# ADR-0034: 분석 접수·묶음 구현 방식 (단계 5-1)

- 상태: 채택 (#1~#19 사용자 결정, #20~#38 구현 관례 = Claude 판단, ADR-0026~0029와 같은 방식)
- 날짜: 2026-11-10
- 관련: FR-I-01·02, ADR-0022, ADR-0024 #4-1·#4-2·#6-3, ADR-0026, ADR-0027 #15, ADR-0029, ADR-0030 #1·#4, ADR-0031, ADR-0032 #6-2·#7, ADR-0033 #5, 설계 문서 `docs/design/stage5-analysis-pipeline.md` 0.3·1·4.3, 브랜치 `feature/FR-I-01-intake`, 커밋 `eb62bf3`~`b06ed6c`

## 맥락
- ADR-0031과 설계 문서가 단계 5-1 (접수·묶음)의 계약을 정했다. 사용자는 2026-11-01에 모델과 무관한 계약, PR 5-1/5-2/5-3 분할, 신규 모듈 `photo` + `analysis`, 첫 체험 App Attest assertion = 포트 + `local`·`test` 전용 대역, 묶음 v1 구현 + 기본 꺼짐, `pik.analysis.intake-enabled=false` 기본을 정했다.
- 구현 계획 (Codex R19~R21 검토, 착수 가능 판정)에서 설계·ERD·ADR 사이의 충돌·누락 16건 (Q1~Q16)이 나왔다. 묶음 표 범위, 규칙 버전 보관, manifest 범위, 스냅샷 위치, 벡터 저장 범위·버전, 꺼짐 검사, 변경 번호, 비활성 응답, 체험 기록 없는 기기, 오류 사유 이름, 본문 상한이다.
- 5-1은 5-2 표 (`analysis_result`·`external_call` 등)가 없는 상태에서 접수 판정과 응답 투영을 만들어야 했다. 5-2 판정의 빈 구현은 넣지 않는다 (계획 Must NOT have).

## 결정
### 사용자 결정
| # | 항목 | 결정 | 날짜 |
|---|---|---|---|
| 1 | 묶음 표 범위 (Q1) | 5-1 = `analysis_group` (접수 범위 묶음, 불변)만. 영속 현재 묶음 `photo_group`·`photo_group_member`는 단계 9에서 추가하고 `analysis_group`·`analysis_result`로 초기 채움 (D-28). ERD 두 표에 "단계 9" 표시 | 2026-11-03 |
| 2 | 규칙 버전 보관 (Q2) | 별도 표 `analysis_grouping_rule` (analysis 소유), `id` = 규칙 manifest SHA-256. 단계 9 `photo_group.rule_version`도 같은 행 참조 | 2026-11-03 |
| 3 | 호출 manifest 범위 (Q3) | 5-1 = 업로드 정책만 (`{model, uploadPolicy}`). 프롬프트·스키마·가격 = 5-2 (V6). 기본값 `image/jpeg`, `maxLongEdgePx` 1568, `maxBytes` 2 MiB (설정값, D-07에서 확정) | 2026-11-04 |
| 4 | 접수 스냅샷 (Q4) | 항목에 `taken_at`·`text_status`·`ocr_version`. `photo.os_taken_at`은 단계 9 | 2026-11-03 |
| 5 | 벡터 저장 범위 (Q5-a) | 묶음 켜짐일 때 묶음 계산에 쓴 유효 벡터 전부 저장 (판정 무관, 거절 포함) | 2026-11-03 |
| 6 | 벡터 버전 (Q5-b) | 불변 벡터 버전 (`content_sha256` 유니크, 같은 값 재사용, 다른 값 새 행). 항목이 `vector_id`로 참조 | 2026-11-03 |
| 7 | 묶음 꺼짐 검사 (Q6) | 구조 검사 (base64 → 길이 > 0 ∧ 4의 배수 → 유한 → norm > ε, ε = 1e-6 설정값). 등록 모델·차원 검사는 켜짐만 | 2026-11-04 |
| 8 | 변경 번호 (Q8) | `photo` 생성·관측 변경 때 업무 TX 마지막에 `change_seq` 발급 (`nextBlock`). 접수는 `photo.version`을 올리지 않음 | 2026-11-03 |
| 9 | 체험 기록 없는 기기 (Q10) | 항목별 거부: 200, 신규 후보만 `NOT_ACCEPTED`/`FORBIDDEN`/`TRIAL_UNAVAILABLE`, `retryable=false`. `recordStart`·`reserve` 호출 0회. 같은 요청의 `IN_PROGRESS`·이전 기록 재생은 그대로 | 2026-11-05 |
| 10 | 비활성 응답 (Q7) | `intake-enabled=false` = 503 `TEMPORARY_FAILURE`/`INTAKE_DISABLED`, `retryable=true`, 멱등 기록 저장 안 함. 검사는 멱등 사전 조회 뒤 → 이전 기록 재생 허용 | 2026-11-06 |
| 11 | 본문 상한 (Q16) | 2 MiB (2,097,152바이트), 설정값 (`max-body-size: 2MB`, Spring `DataSize`). 규칙상 최대 본문 ≈ 1.3 MB | 2026-11-06 |
| 12 | B7 "확인 전" 상태 (Q9) | 5-1은 시작 판정을 `needsDeviceCheck()`로만 대비. 설치 경로는 D-23 구현 | 2026-11-10 |
| 13 | 5-2 판정 (Q11) | `EXISTING_RESULT`·`INPUT_RETRY_LATER`·`ATTEMPTS_EXHAUSTED`·`ANALYSIS_UNAVAILABLE` 판정 = 5-2. 5-1은 enum·CHECK만 | 2026-11-10 |
| 14 | 재사용 결과 열 (Q12) | `reused_result_id`·`reused_photo_version` 열과 CHECK = 5-2 (결과 PK와 함께) | 2026-11-10 |
| 15 | 장기 작업 오류 `jobId` (Q13) | 접수 오류 응답 `jobId = null` (`api-contract.md` 공통 응답 필드와 실행기 규칙을 맞춤) | 2026-11-10 |
| 16 | 자금 (Q14) | `funding = TRIAL` 고정 (Pro = 단계 8) | 2026-11-10 |
| 17 | 사유 이름 (Q15) | `TAKEN_AT_INVALID` (400), `INTAKE_DISABLED` (503), `TRIAL_START_CONFLICT` (503), `ATTESTATION_CONFLICT` (409), 항목 `TRIAL_UNAVAILABLE`. `REQUEST_TOO_LARGE`·`DUPLICATE_PHOTO`·`AI_CONSENT_REQUIRED` = 설계 이름 | 2026-11-10 |
| 18 | 응답 `jobs[]` 범위 | 참조 작업 포함: 이 접수의 작업 + `IN_PROGRESS` 항목이 참조하는 작업 (중복 제거, 업로드 정책 포함, `counts`·`phase`와 같은 범위). `JobView`에 `requestId`. 5-2 업로드 경로는 `/analysis-jobs/{requestId}/...` 그대로 | 2026-11-10 |
| 19 | 요청 문자열 규칙 | `ocrVersion`·`vector.model`·`vector.modelVersion` = 1~64자 ASCII U+0020~U+007E. `contentVersion` = 1~128자, 제어 문자 (`\p{Cc}`) 거부. 위반 = 400 `VALIDATION_FAILED`. 구현 보완 (6b 리뷰, Claude): 짝 없는 서로게이트 (`\p{Cs}`)도 거부, 길이 = code point 수 (DB `char_length`와 같음) | 2026-11-10 |

### 구현 관례 (Claude 판단, 2026-11-01~11-10)
| # | 항목 | 결정 |
|---|---|---|
| 20 | 실행기 단계 API | `IdempotentExecutor.lookup` (읽기 TX 사전 조회·해시 비교), `reject` (키 잠금 → 재확인 → 기록 있으면 재생, 없으면 저장 대상만 저장, 비저장은 다시 던짐), `commit(Supplier<Outcome>, ReplayProjection)` (`Outcome` = 상태 코드 + 본문, 202 저장). `ReplayProjection`은 2xx 재생에만 적용. 기존 `execute` = `commit` 위임 (기존 시험 무수정 통과) |
| 21 | 비저장 플래그 | `PikException`에 `storable` (기본 true)·`properties` (ProblemDetail 필드로 출력). `PikException.unstored(...)`. 저장 판정 = `storable` ∧ 4xx ∧ 401·429·`Retry-After` 아님 |
| 22 | 장기 작업 `jobId` | `@IdempotentCommand(longRunning = true)` → 필터 (`IdempotentCommands.markIfCommand`)·인터셉터·실행기 `problem()` 모두 `jobId = null`. `IdempotentRequest.longRunning` 추가 (기존 4인자 생성자 유지) |
| 23 | 변경 번호 블록 | `ChangeSequence.nextBlock(owner, n)` (한 번 갱신, 첫 번호 반환), `next` = `nextBlock(owner, 1)`. 카운터 갱신 전에 `EntityManager.flush()` (설계 0.3 "도메인 쓰기·flush 뒤 발급"을 공통 함수에서 강제) |
| 24 | 관측 횟수 | `PhotoObservations.observe`는 한 TX에 1회. 범위를 TX 자원으로 묶어 중첩 TX (`REQUIRES_NEW`)에서 호출하면 즉시 실패. 변경 집합이 있는데 `issueChangeSeqs`를 빠뜨리면 `beforeCommit`에서 실패 (롤백) |
| 25 | `photo` 저장 방식 | 5-1은 JPA 엔티티 없이 JDBC만 (`PhotoRecords`). 관측 갱신은 조건부 UPDATE로 `version`을 올리지 않음 (ADR-0027 #15 `touchLastSeen` 선례). 없는 행 = `client_ref` 순 `ON CONFLICT DO NOTHING` (`change_seq = 0` 임시값), 전체 = id 순 `FOR UPDATE` |
| 26 | 표 구성 | 접수 범위 묶음 (`analysis_group`, 거절 묶음 포함), 규칙 표 (`analysis_grouping_rule`, 기동 때 없으면 삽입), 최소 manifest (`analysis_call_manifest`, 업로드 정책만 타입 열). `analysis_request`에 `trigger`·`device_id`·`trial_mark_id`·`consent_version`·`grouping_rule_id`·`first_result_at` 추가, `status` 열 없음 (계산). `analysis_job` = 접수 열만, 상태 CHECK에 5-2 상태까지 |
| 27 | 항목 추가 열 | `client_ref` (재생 투영이 photo 모듈 호출 없이 사용), 스냅샷 (#4), `vector_id` (#6), 상태별 CHECK (설계 4.3). `IN_PROGRESS` 항목은 기존 묶음을 참조하지 않음 |
| 28 | 규칙 manifest | 설계 1.3 항목 + `enabled`·ε·모델별 dims + v1 대상·정렬·합류·대표·정규화 설명. 모델 목록 (model, version) 정렬, W = `Duration.toString()` → 같은 설정 = 같은 규칙 ID. 호출 manifest 내용 = `{model, uploadPolicy: {contentType, maxBytes, maxLongEdgePx}}` |
| 29 | 설정 검사 | 모델 목록 형식은 꺼짐에도 기동 때 검사 (빈 목록 금지만 켜짐 조건). θ = (0, 1) 열린 구간 (계획의 (0, 1]에서 변경). 설정의 model·version·manifest model·content type = ASCII U+0021~U+007E (공백 없음, 정규 JSON 해시 입력) |
| 30 | 묶음 세부 | `photoId` 비교 = 부호 없는 128비트 (PostgreSQL `uuid` 순서, `UUID.compareTo` 금지). base64 = RFC 4648 표준 알파벳, padding 선택, 공백·URL-safe = `MALFORMED_BASE64`. 단독 묶음 model = null, 등록 dims 불일치 = 단독. 대표 평균 = 다른 구성원과의 유사도 합 / (k − 1), 동률 = 구성원 순서 (`takenAt` → `photoId`). 유사도 = 저장 F32 → double → double 정규화 → 인덱스 순 합산 |
| 31 | `takenAt` | 해시는 원문 문자열. 마이크로초로 절삭한 뒤 묶음 계산·저장 (DB 정밀도와 같아 저장 스냅샷으로 재현) |
| 32 | 응답 정렬 | `items[]` = `photoId`, `groups[]` = 대표 `photoId`, `jobs[]` = 대표 `photoId` → 작업 ID (모두 부호 없는 순). 단말은 `photoId`로 대응 |
| 33 | `JobView.representativePhotoId` | `jobs[]`마다 대표 사진 `photoId` (photo 조인). 구성원만 다시 보낸 요청은 대표 사진을 `items[]`에서 알 수 없어, #18의 목적 (참조 작업 업로드)을 채우려면 필요 (6b 리뷰) |
| 34 | 본문 읽기 | 메시지 변환기 대신 원본 바이트 (`readNBytes(limit + 1)`, SHA-256 = assertion 서명 입력). `Content-Encoding` 모든 헤더·코딩 검사, JSON 아닌 `Content-Type` = 400 `MEDIA_TYPE_NOT_SUPPORTED`, 읽기 `IOException` = 400 `MALFORMED_BODY` `retryable=true`. 본문·값 오류는 모두 비저장 |
| 35 | 첫 체험 assertion | 포트 위치 = `account` (`TrialStartAttestations` 공개 + package-private `AssertionVerifier` + `FakeAssertionVerifier`, `local`·`test` 밖 기동 실패). 대역 = 무상태 challenge, `fake-assertion` 통과·`-stale` 403 + 새 challenge·`-race` 409 + `Retry-After`·그 밖 `ATTESTATION_FAILED`. 증표는 HTTP 요청 안에서만 재사용 (`StartRequired` 재시도 = counter 1회). 증거 헤더 = 각 4,096자 이하 |
| 36 | 접수 잠금 | 주체 접수 advisory 잠금 (`analysis.intake:` 접두사), 동의 advisory 잠금 (`consent:`). 참조할 기존 작업 = photo 뒤·예약 앞 id 순 `FOR SHARE` + 재확인 (`owner_id` 범위). 접수 행 삽입은 예약 뒤 (0.3 예외, 불변 조건: `analysis_job` 삽입 경로는 주체 접수 잠금을 먼저) |
| 37 | `analysis_job` 잠금 규칙 | `AlreadyReserved`로 생긴 `IN_PROGRESS` 항목의 FK `KEY SHARE`가 이용량 잠금 뒤에 오는 교착 (3단계 리뷰 후속 1) = 잠금 등급으로 해결. 이용량보다 먼저 작업을 잠그는 TX는 `FOR NO KEY UPDATE`보다 강한 잠금 금지 (`FOR UPDATE`·`DELETE`·`id`·`group_id` UPDATE 포함), claim = `FOR NO KEY UPDATE SKIP LOCKED`, 예외 = 주체 접수 잠금을 먼저 잡은 TX. 작업 잠금은 네이티브 SQL. 5-1에는 전이 코드가 없으므로 5-2 규칙으로 기록하고 C-8 시험 (전이 흉내 TX vs `AlreadyReserved` 접수, `FOR UPDATE` 대조 = 교착)으로 고정 |
| 38 | 로그 | 사진 ID·벡터·`contentVersion` 로그 금지. record `toString`이 값을 숨김 (요청·응답·관측·벡터 record), `photo`·`analysis` `package-info` 주석, 6b 리뷰에서 로그 호출부 확인 |

## 근거
- 1: 지금은 접수 범위 묶음과 현재 묶음의 내용이 같다. 현재 묶음을 바꾸는 기능 (ADR-0024 #5·#6-3, C10)은 단계 9 이후다. 두 표를 함께 쓰면 재생·정산이 불안정해진다.
- 2: 규칙 버전과 호출 manifest는 쓰는 시점·바뀌는 이유가 다르다.
- 3: Haiku 5.5는 고해상도 등급 (최대 2576px, 4,784 시각 토큰, 공식 문서 2026-11-04 확인)이다. 1568px면 어떤 비율에서도 서버가 축소하지 않아 (정사각형 최대 3,136 토큰) 저장 이미지 = 모델 입력이다. 장당 이미지 입력 약 $0.00024.
- 4·5·6: 재현·검증 기준은 접수 시점 값이다. 기록된 묶음을 모두 재현하고 서버 배치가 재사용할 수 있다. 불변 버전이면 동시 접수가 충돌하지 않는다 (Codex R19-02).
- 7: 단말 벡터 오류를 첫 출시부터 발견한다 (Codex R19-03).
- 8: ERD `change_seq` NOT NULL을 지키고 단계 9에서 채울 필요가 없다 (Codex R19 O1).
- 9: 현재 익명 설치 경로는 항상 체험 기록을 발급한다 (`AnonymousAuthService:53`). 기록 없는 기기는 FR-A-01 로그인 기기 연결 때 생길 수 있다. 접수 중 발급은 재설치 판별 우회다.
- 15: 장기 작업은 오류 때 작업을 만들지 못했으므로 키를 작업 ID로 돌려주면 안 된다.
- 20·21: 체험 시작의 외부 호출 (DeviceCheck·assertion)은 TX 밖이고, 그 실패도 같은 키 재전송에 같은 응답을 줘야 한다 (B6-2.E6). 증거 오류는 저장하면 보완 재전송이 막힌다.
- 23·24: 두 번째 `observe`는 잠금 순서를 어기고, 중첩 TX는 같은 스레드에서 무한 대기한다 (리뷰).
- 29: 단위 벡터의 자기 코사인이 double에서 1.0을 넘거나 못 미쳐, θ = 1이면 같은 벡터도 단독이 된다 (리뷰).
- 31: DB가 마이크로초로 저장하므로 원본 정밀도로 묶으면 저장 값으로 다시 계산한 묶음과 다를 수 있다.
- 37: `KEY SHARE`는 `FOR NO KEY UPDATE`와 충돌하지 않는다. `analysis_job` 키 = `id`·`group_id` (비부분 UNIQUE)이고 전이는 `status`만 바꾸므로 일반 UPDATE도 `NO KEY UPDATE` 등급이다. JPA `SKIP_LOCKED`는 `FOR UPDATE`를 낼 수 있다.

| 대안 | 버린 이유 |
|---|---|
| 5-1에 영속 `photo_group` (#1) | 재생·정산 불안정, 바꾸는 기능이 단계 9 이후 |
| 규칙 버전을 호출 manifest에 넣음 (#2) | 사용 시점·변경 이유가 다름 |
| 5-1 manifest에 프롬프트·스키마·가격 (#3) | 5-2 자원. 내용 해시가 바뀌면 새 ID라 5-2 확장에 충돌 없음 |
| 촬영 시각을 `photo.os_taken_at`에 (#4) | 재현 기준은 접수 시점 값 |
| 접수된 사진 벡터만 저장 (#5) | 기록된 묶음 전부를 재현하지 못함 |
| (사진, 모델, 버전)당 첫 값 유지 / 교체 (#6) | 저장 F32와 계산 입력이 달라짐 / 이전 접수 재현 불가 |
| 꺼짐이면 벡터 검사 안 함 (#7) | 단말 오류를 켜는 날까지 모름 |
| 접수 중 체험 기록 발급 (#9) | 재설치 판별 우회 |
| 본문 상한 2,000,000바이트 (#11) | 설정값 단위 (`DataSize` 2MB = 2 MiB)와 다름 |
| `jobs[]` = 이 접수가 만든 작업만 (#18) | 참조 작업을 업로드할 수 없고 `counts`·`phase` 범위와 다름 |
| `AlreadyReserved` 작업을 선잠금 집합에 넣고 TX 재시도 (#37) | 재시도 경로가 늘어남. 잠금 등급 규칙이면 충돌 자체가 없음 |
| `photo` JPA 엔티티 (#25) | 관측 갱신이 `version`을 올림 |
| `UUID.compareTo` 정렬 (#30) | 부호 있는 비교라 DB `uuid` 순서와 다름 |

## 결과
- V5 마이그레이션 (`photo`, `photo_vector`, `analysis_grouping_rule`, `analysis_call_manifest`, `analysis_request`, `analysis_group`, `analysis_job`, `analysis_request_item`). ERD에 같은 차이를 반영했다 (`eab488a`).
- 코드: `shared` 실행기 단계 API·비저장 플래그·장기 작업 `jobId`·`nextBlock`, `consent` advisory 잠금·`lockGrantedBasis`, `usage` `NotCovered(retryable)`·`StartGuard`·`fake-device-check-unavailable:` 대역 토큰, `account` `RequestingDevices`·`TrialStartAttestations`, 신규 `photo`·`analysis` 모듈, `POST /analysis-jobs`, 설정 `pik.analysis.*` (비밀 값 없음, `.env.example` 영향 없음).
- 시험: 전체 472개, 실패 0 (동시성 C-1~C-8 포함). `intake-enabled=false`가 기본이라 운영 접수는 생기지 않는다 (설계 4.4).
- 5-2 조건:
  - 재생 투영을 한 스냅샷으로 읽음 (REPEATABLE READ 읽기 TX 또는 단일 조회)
  - claim·작업 잠금 = 네이티브 SQL `FOR NO KEY UPDATE [SKIP LOCKED]` (#37), C-8의 claim 경로 변형 시험 추가
  - `AlreadyReserved` 보유 작업이 종료 상태일 때 클라이언트 해석 (`IN_PROGRESS` + `phase = DONE`) 정리. `AlreadyReserved` + 미잠금 보유 작업은 설계 경로로는 생기지 않는 방어 분기 (전이 TX가 같은 TX에서 예약 반환)
  - #13 판정 4종, #14 열, 항목 `retry_at`, 동의 `basisHolds`, `settle`, manifest 내용 확장 (새 ID)
  - 원접수 `ACCEPTED` 항목만 구성원 스냅샷으로 읽음 (`IN_PROGRESS` 참조 항목 제외, R19 O2, D-28 인수 조건)
- 후속: 보관 단계는 항목 → 작업 FK가 두 접수의 삭제 기한을 묶는 문제를 정한다 (`data-retention.md`). 어떤 항목도 참조하지 않는 `photo_vector` 정리도 함께. ERD의 모듈 간 논리 연결선 표기를 한 규칙으로 통일한다 (새 표는 연결선 없음, 기존 표는 있음).
- 보류: `Device-Check-Token` 길이 상한 없음 (현재 상한 = Tomcat 헤더 8 KB, Apple 토큰 크기 확인 뒤 결정). 윤초 `23:59:60` = `TAKEN_AT_INVALID` (실익 없음).
- D-07 제안 (사용자 확인 전): ADR-0033 #5의 측정 해상도 후보 (1092/1568px)는 표준 등급 기준이다. Haiku 5.5 고해상도 등급의 2576px (+ `maxPixels` 또는 시각 토큰 상한)을 후보에 추가할지 정한다. 글자가 많은 사진에서 이득 가능, 비용 약 2배.
- B7 설치 경로 (D-23 구현)는 `startDeviceCheck`·`startedElsewhere`·취소 복원 전이까지 함께 바꿔야 한다 (R19 O3). 5-1에서 지원 완료로 보지 않는다.
- 출처: #1~#19는 사용자 결정이다 (#1~#8 = Claude 추천안, #9~#17 = 계획 제안대로, #18·#19 = 6a 리뷰 뒤 사용자 선택). #20~#38은 Claude가 구현·리뷰 반영 중 정한 관례다 (#33·#34 일부·#37 문구 확장은 6a·6b 리뷰 반영).
