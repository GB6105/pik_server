# 단계 5 분석 파이프라인 설계: 접수·실행·생성

- 결정 기록: [ADR-0031](../adr/0031-stage5-analysis-pipeline.md). 운영·인프라는 [ADR-0032](../adr/0032-operations-infrastructure-security.md), 이후 단계 정책은 [ADR-0033](../adr/0033-later-stage-policy-proposals.md)
- 대상 FR: FR-I-01·02·03, FR-G-01~03, FR-E-01
- 전제 (사용자 확정): 모델과 무관한 계약, PR 3개 (5-1 접수 / 5-2 실행 / 5-3 생성), 신규 모듈 `photo` + `analysis` (기존 `action`·`event`·`usage` 모듈은 확장)
- SSOT·ADR과 충돌하면 SSOT·ADR 우선. 이 문서는 구현 PR마다 실제 DDL·API와 맞춰 갱신
- 비용·처리량 수치 = 2026-11-01 기준 추정 (가정 명시)

## 0. 공통
### 0.1 용어
- 접수 (`analysis_request`): 단말이 보낸 사진 목록 한 번. 응답의 `jobId` = 접수 ID
- 작업 (`analysis_job`): 대표 사진 하나의 분석 실행 단위. 묶음 구성원도 이 작업이 처리. `funding` = `TRIAL`/`PRO` (접수 때 고정)
- 시도 (`analysis_upload_attempt`): 대표 이미지 업로드 한 번
- 호출 (`external_call`): Claude HTTP 요청 한 번
- 생성 (`action_generation`·`event_generation`): 분석 결과에서 액션·이벤트를 만드는 후속 처리. 각 모듈이 소유
- 호출 manifest (`analysis_call_manifest`): 모델·프롬프트·스키마·입출력 상한·가격을 묶은 불변 설정 (2.5)

### 0.2 실행 근거
자동 값의 축 (서로 독립):
| 축 | 값 | 의미 |
|---|---|---|
| 출처 `verification` | `DIRECT` / `INHERITED` | 그 사진을 분석했는가 / 대표에서 복사했는가 |
| 추출 `extraction` | `READ` / `ESTIMATED` / `ABSENT` | 사진에서 읽음 / 추정 / 근거 없음 |
| 검증 `validation` | `VALID` / `NEEDS_CHECK` / `INVALID` | 서버 규칙 통과 / 이상 탐지 / 형식 오류 |

실행 가능 판정 (생성·확인·투영이 같은 함수 사용, 위에서 먼저 맞는 것):
0. 공통 전제: 소유 검증 ∧ 현재 대상 (`HIDDEN`·`STALE` 아님) ∧ 종류별 값 검증 (번호 = 선택 국가 유효, 날짜 = 형식·범위). 사용자 입력도 같은 검증
1. 사용자 수정값 (`user_state = EDITED`) = 실행 가능. 원본 변경은 표시만
2. 사용자 확인 = 그 필드의 `confirmed_source_seq ≥ photo.source_change_seq`면 실행 가능
3. 자동 값 = `DIRECT ∧ READ ∧ VALID` ∧ 종류별 조건 (3.2) ∧ `result.basis_change_seq ≥ photo.source_change_seq` (같은 사진의 값끼리만 비교)
4. 그 밖 = `NEEDS_CHECK` (사유 목록)
- 확인·수정은 원래 `generated_value`와 자동 근거를 바꾸지 않는다 (분석 이력 보존)
- 이 판정도 오류를 줄일 뿐 무오류 확인이 아니다

### 0.3 잠금 순서 (모든 TX 공통, 앞에서 뒤로만)
`멱등 키 → 동의 (공유/배타) → 주체 접수 → 주체 이벤트 → 전역 claim → photo (id 순) → analysis_request → analysis_job (id 순) → external_call → 이용량 할당 → 주체 일일 송신 → 기기 기록 → photo_ai_budget → 일일 비용 → 생성 행 → photo_action·event (id 순) → owner_change_counter`
- 행 생성 (`INSERT … ON CONFLICT`)은 그 자원의 순서 위치에서 한다 (마지막이 아님). 이미 잠근 상위 행 아래에서만 충돌하는 삽입 (예: 작업 잠금 아래의 `external_call`)은 위치 제약 없음
- `owner_change_counter`는 도메인 쓰기·flush 뒤 발급
- 예외: claim의 우선순위 순 `FOR NO KEY UPDATE SKIP LOCKED` (대기하지 않음)
- `analysis_job` 잠금 (ADR-0034): 이용량보다 먼저 작업을 잠그는 TX (claim·상태 전이·완료·취소)는 `FOR NO KEY UPDATE`보다 강한 잠금 금지 (`FOR UPDATE`·`DELETE`·`id`·`group_id` UPDATE 포함). 예외 = 주체 접수 잠금을 먼저 잡은 TX. 작업 잠금은 네이티브 SQL (JPA `SKIP_LOCKED`는 `FOR UPDATE`를 낼 수 있음). 근거 = 접수의 `AlreadyReserved` 항목 FK 검사 (`KEY SHARE`)가 이용량 잠금 뒤에 옴 (1.2 5d). 시험 C-8이 고정
- 만료·취소·회수 배치 = 후보 조회 (잠금 없음) + 작업별 전이 TX
- advisory 잠금 네임스페이스 분리 (멱등·동의·접수·이벤트·claim·기기)

## 1. 단계 5-1 접수·묶음
### 1.1 요청 `POST /analysis-jobs`
- 헤더: `Idempotency-Key`, `API-Version`, iOS = `Device-Check-Token` 매 접수 (필요할 때만 사용, 해시 제외). iOS 체험 시작 접수 = `App-Attest-Assertion`·`App-Attest-Challenge-Id`·`App-Attest-Nonce` (서버가 challenge를 줄 때만, 해시 제외, 각 4,096자 이하, `operations-infrastructure.md` B6-2)
- 본문 (멱등 해시 대상 = `IntakeCommand`): `trigger` (`NEW_PHOTOS`/`ORGANIZE`, `PRO_BACKLOG`는 서버 배치 전용), `photos[]` 1~100
  - `photoId` UUID (= `photo.client_ref`, 내부 `photo.id`와 다름), `contentVersion` 1~128 code point 불투명 토큰 (제어 문자 `\p{Cc}`·짝 없는 서로게이트 금지, 길이 = DB `char_length`)
  - `takenAt` 선택, RFC 3339 오프셋 필수 (초 필수, 소수 1~9자리). 원문 문자열로 해시, 마이크로초로 절삭해 묶음·저장 (DB 정밀도). 누락 = 단독, 형식 오류 = 400 `TAKEN_AT_INVALID`
  - `vector` 선택: `model`·`modelVersion` 1~64자 ASCII U+0020~U+007E, `data` float32 LE base64 (RFC 4648 표준 알파벳, padding 선택) ≤ 12,000자
  - `text` 선택: `status` (`UNKNOWN`/`NO_TEXT`/`TEXT`), `ocrVersion` 1~64자 ASCII U+0020~U+007E. 누락 = `UNKNOWN`
  - 길이·문자 규칙 위반 = 400 `VALIDATION_FAILED`
- 본문 읽기 = 메시지 변환기 대신 원본 바이트 (크기 검사·assertion 서명 입력 SHA-256). 순서: `Content-Encoding` (모든 헤더·코딩이 `identity`가 아니면 400 `MEDIA_TYPE_NOT_SUPPORTED`) → `Content-Type` (`application/json`·`application/*+json` 아니거나 없음 = 400 `MEDIA_TYPE_NOT_SUPPORTED`) → 2 MiB (2,097,152바이트, 설정값 `pik.analysis.intake.max-body-size`) 초과 = 파싱 전 400 `REQUEST_TOO_LARGE` → 파싱 오류 = 400 `MALFORMED_BODY` (읽기 중 입출력 오류 = 같은 사유, `retryable=true`)
- 같은 `photoId` = 400 `DUPLICATE_PHOTO`. 전체 검사 순서·저장 여부 = `.claude/docs/api-contract.md` `POST /analysis-jobs`

### 1.2 처리 순서
1. 인증 → 본문 읽기·파싱 (1.1) → 요청 기기 확인 (`RequestingDevices.find(주체, did)`, 없으면 401 `ACCESS_TOKEN_INVALID`, B6-2.E3 1단계) → 실행기 사전 조회 (`lookup`, 해시 비교, 같으면 재생) → `pik.analysis.intake-enabled=false`면 503 `TEMPORARY_FAILURE`/`INTAKE_DISABLED` (비저장, `retryable=true`. 사전 조회 뒤라 기존 기록 재생은 허용)
2. 값 검증 (`VALIDATION_FAILED` → `DUPLICATE_PHOTO` → `TAKEN_AT_INVALID`, 비저장) → 빠른 동의 확인 (TX 밖, `AiConsents.isGranted`, 아니면 `reject`로 403 `AI_CONSENT_REQUIRED` 저장)
3. TX 밖 준비: 벡터 검사 (1.4). `groupingEnabled`면 요청의 **모든** 유효 벡터 디코딩·정규화·쌍별 유사도. 사진 ID·활성 작업 조회 (잠금 없음), 예비 묶음
4. 체험 시작 (TX 밖, usage 내부 짧은 TX만): 기기 `trial_mark_id` = null이면 `recordStart`·`reserve`를 부르지 않음 (사용자 결정 Q10, 5d). 그 밖에 후보 ≥ 1이면 `recordStart(예비 묶음 중 가장 작은 크기, token, guard)`. Android·시작된 iOS (`TRIAL_STARTED`)는 usage가 바로 반환. `StartGuard.beforeStart` = usage가 Apple 호출 직전 (B6-2.E1: NEW·`STARTING` 복구)에만 1회 호출 → 증거 없으면 비저장 403 `ATTESTATION_REQUIRED` + challenge, 있으면 `TrialStartAttestations.verify` → 증표를 이 HTTP 요청 안에 보관 (재시도 재사용, counter 1회). 이 단계의 `PikException` = `reject` (저장 대상만 저장)
5. 업무 TX (`executor.commit`, 멱등 키 잠금 아래):
   a. 동의 공유 잠금 → 기준 버전 확보 (`lockGrantedBasis`, 비어 있으면 403 `AI_CONSENT_REQUIRED`, 전체 롤백 후 저장)
   b. 주체 접수 advisory 잠금 → `photo` 찾기·생성 (없는 행 `client_ref` 순 `ON CONFLICT DO NOTHING`, 전체 id 순 `FOR UPDATE`). 관측 기록 (1.6). 한 TX에 1회
   c. 재분류: 대표 활성 작업 또는 활성 작업의 `ACCEPTED` 항목 (구성원) = `IN_PROGRESS` 예비. c': 찾은 기존 작업을 id 순 `FOR SHARE`로 잠그고 상태 재확인 (종료된 작업의 사진 = 후보로 되돌림) → 후보만 준비한 유사도로 묶음 재계산 (결정적)
   d. `trial_mark_id` = null이면 예약 없이 신규 후보 전부 `NOT_ACCEPTED`/`FORBIDDEN`/`TRIAL_UNAVAILABLE`, `retryable=false` (`IN_PROGRESS`는 그대로). 그 밖에 묶음을 `max(takenAt)` 내림차순 (NULL 뒤, 동률 = 대표 `photoId` 부호 없는 순)으로 작업 ID를 미리 발급해 `reserve`:
      - `Reserved` 진행 / `NotCovered` = 묶음 `NOT_ACCEPTED`/`FORBIDDEN`/`SUBSCRIPTION_REQUIRED` (`retryable` = `NotCovered.retryable`), 다음 묶음 / `AlreadyReserved` 매핑 사진은 그 작업의 `IN_PROGRESS`, 나머지는 같은 규칙으로 다시 묶어 대기열에 정렬 삽입. 이 작업은 c'에서 잠그지 않음 → 항목 FK `KEY SHARE`가 이용량 잠금 뒤 (0.3 `analysis_job` 잠금 규칙. 설계 경로로는 생기지 않는 방어 분기)
      - `StartRequired` = 신호 예외로 TX 전체 롤백 (기록 없음) → 막힌 묶음 크기로 `recordStart` (같은 guard, 증표 재사용, 없으면 비저장 challenge 응답) → `commit` 1회 재시도 (a부터 다시) → 또 나오면 503 `TEMPORARY_FAILURE`/`TRIAL_START_CONFLICT` (저장 안 함)
   e. `keepVectors` (묶음 켜짐 ∧ 유효 벡터 ∧ c' 후보 = 계산 입력 전부, 판정 무관) → JDBC batch: 접수 (기준 동의 버전, 규칙 ID, trigger, 기기·체험 기록 ID, 접수 시각, `first_result_at = null`) → 묶음 → 작업 (`AWAITING_UPLOAD`, `funding = TRIAL`, 현재 manifest ID, `base_priority`, `queued_at = now`, 기한 = now + 설정) → 항목 (스냅샷 + `vector_id`). 0.3보다 늦은 삽입 = 4.3 잠금 순서 예외
   f. `issueChangeSeqs` (생성·`source_change_seq` 증가 사진만, 도메인 쓰기·flush 뒤 마지막 `owner_change_counter`)
   g. 실행기가 `Outcome(새 작업 ≥ 1 = 202, 그 밖 = 200, 투영 응답)`과 멱등 기록 저장 후 커밋
- 5-2가 같은 자리 (5c)에 더할 판정 (사용자 결정 Q11): `EXISTING_RESULT`, 입력 반복 실패 차단 (`INPUT_RETRY_LATER`, 2.7), 사진 평생 송신 (`ATTEMPTS_EXHAUSTED`), 체험 기기 송신 한도 소진 = 항목 `NOT_ACCEPTED`/`ANALYSIS_UNAVAILABLE` (빠른 거절, 최종 판정은 허가). 5-1은 enum·CHECK만
- 5-1 `funding` = `TRIAL` 고정 (사용자 결정 Q14, Pro = 단계 8). 체험 시작 판정 = usage `needsDeviceCheck()`, B7 "확인 전" 설치 경로는 D-23 구현 (사용자 결정 Q9)

### 1.3 묶음 규칙 v1 (P05 초기값, 설정값)
- 대상: `groupingEnabled` ∧ 같은 (`model`, `modelVersion`) 유효 벡터 + `takenAt` + `text.status = NO_TEXT`. 그 밖은 단독
- `(takenAt, photoId)` 순으로, 모든 구성원과 코사인 ≥ θ ∧ 시간 폭 ≤ W (120초) ∧ 크기 < 20인 묶음 중 최소 유사도가 가장 큰 곳 (동률 = 먼저 만든 묶음). 없으면 새 묶음
- 대표 = 평균 유사도 최대 → 이른 `takenAt` → `photoId`
- 유사도 = 저장한 F32 값을 double로 계산 (재현 기준)
- 규칙 버전 = manifest (algorithmVersion, `enabled`, W, ε, 최대 크기, 모델별 dims·θ, 대상·정렬·합류·대표·정규화 설명) 정규 JSON의 SHA-256, `analysis_grouping_rule`에 보관 (기동 때 없으면 삽입, 사용자 결정 Q2). 모델 목록은 (model, version) 정렬, W는 `Duration.toString()` → 같은 설정 = 같은 ID. 범위 = 한 접수 안 (`analysis_group`, 사진의 영속 묶음은 단계 9, D-28)
- `photoId` 순서 = 부호 없는 128비트 (PostgreSQL `uuid` 순서와 같음, `UUID.compareTo` 금지). 단독 묶음 (크기 1)의 model = null, 등록 dims와 다른 벡터 = 단독. 대표 평균 = 다른 구성원과의 유사도 합 / (k − 1). θ 허용 범위 = (0, 1) (자기 코사인이 double에서 1을 벗어날 수 있음)

### 1.4 벡터
- `groupingEnabled=false` (기본값) = 벡터 등록 목록 없이 기동, 벡터는 구조 검사만 (base64 → 디코딩 길이 > 0 ∧ 4의 배수 (`BAD_DIMENSION`) → 유한 → norm > ε, 사용자 결정 Q6), 저장 안 함, 모두 단독. 미등록 모델·차원 불일치는 사유 없음
- `groupingEnabled=true` = 등록 목록 `(model, modelVersion, dims, threshold)`이 비거나 잘못되면 기동 실패 (중복 쌍, model·version 1~64자 ASCII U+0021~U+007E, dims 1~2,250, θ (0, 1)). 목록 형식은 꺼짐에도 기동 때 검사
- 검사: base64 (RFC 4648 표준 알파벳, padding 선택, 공백·URL-safe = `MALFORMED_BASE64`) → 등록 (`UNKNOWN_MODEL`) → 길이 (`BAD_DIMENSION`) → 유한 (`NOT_FINITE`) → double norm > ε (`ZERO_NORM`, ε 기본 1e-6 설정값) → 정규화 후 유한 재확인. 어기면 그 벡터만 무시 (항목 `vectorIgnored`, 400 아님), 사진은 단독
- 저장 `photo_vector` (사용자 결정 Q5): 묶음 켜짐일 때 묶음 계산에 쓴 유효 벡터 전부 (판정 무관, 거절 포함). 불변 벡터 버전 = 수신 F32LE 바이트 (정규화 전)·`content_sha256`·model·modelVersion·dims, (사진, 모델, 버전, sha256) 유니크로 같은 내용 재사용·다른 내용 새 행. 사진 버전은 항목 스냅샷이 보유, 항목 `vector_id`가 참조. 삽입은 photo 행 잠금 아래만. 지표 라벨은 등록 모델·허용 사유만

### 1.5 항목·응답·재생
- `analysis_request_item` (불변, PK (`request_id`, `photo_id`)): `client_ref`, 스냅샷 (`photo_version`, `basis_change_seq`, `taken_at`, `text_status`, `ocr_version`, 사용자 결정 Q4), `admission_result` (`ACCEPTED`/`EXISTING_RESULT`/`IN_PROGRESS`/`NOT_ACCEPTED`), `code`·`reason`·`retryable`, `group_id`·`group_role`, `analysis_job_id` (`IN_PROGRESS` = 다른 접수의 작업 가능), `vector_id`·`vector_ignored_reason`. `reused_result_id`·`reused_photo_version` (+ `EXISTING_RESULT` CHECK)·`retry_at`은 5-2 (사용자 결정 Q12)
- 상태 (계산): 사진 기준 `requested = accepted + existingResult + inProgress + notAccepted`, 실행 수량 `waiting/running/done`, phase. 수량·phase 범위 = 응답 `jobs[]` (이 접수가 만든 작업 + `IN_PROGRESS` 항목이 참조한 작업, 중복 제거). phase: 작업 없음 또는 모두 종료 = `DONE`, 모두 `AWAITING_UPLOAD`/`READY` = `WAITING`, 그 밖 = `RUNNING`. 진행 작업 참조는 그 작업이 끝나기 전에는 `DONE` 아님. `DONE` = 분석 처리 종료 (생성 미포함, 생성은 4.2 조회)
- HTTP: 새 작업 ≥ 1 = 202, 그 밖 = 200 (접수 행·항목은 항상 저장). 요청 자체 거절 = 4xx·503 (모든 오류 `jobId = null`, `result = FAILED`, 사용자 결정 Q13)
- 본문: `jobId` (= 접수 ID), `result=ACCEPTED`, `retryable=false`, `phase`, `counts`, `items[]`, `groups[]`, `jobs[]`. **URL 없음**
  - `items[]` = 사진마다 1개: `photoId`, `admissionResult`, `code`·`reason`·`retryable`·`retryAfter` (5-2부터), `analysisJobId`, `groupId`·`role`, `execution` (현재 작업 상태), `observedVersion` (이번 요청 값)·`analyzedVersion` (그 작업이 분석하는 버전), `vectorIgnored`
  - `groups[]` (FR-I-02 반환) = 이 접수가 만든 묶음: `groupId`, `representativePhotoId`, `photoCount` (대표 포함), `analysisJobId` (거절 묶음 = null). 구성 = 같은 `groupId`의 `items[]`
  - `jobs[]` (사용자 결정 2026-11-10 "참조 작업 포함") = `analysisJobId`, `requestId` (작업을 만든 접수, 5-2 업로드 경로 `/analysis-jobs/{requestId}/...`), `groupId`, `representativePhotoId` (업로드할 사진. 구성원만 다시 보낸 요청의 `items[]`에는 없음), `status`, `uploadRequired` (`AWAITING_UPLOAD`), `uploadPolicy` (`contentType`, `maxBytes`, `maxLongEdgePx`, 작업 manifest에서 조회)
  - 정렬: `items[]` = `photoId`, `groups[]` = 대표 `photoId`, `jobs[]` = 대표 `photoId` → 작업 ID. `photoId` = 부호 없는 순 (`uuid` 순서). 단말은 순서가 아니라 `photoId`로 대응
- 오류 층 고정: `admissionResult` (접수 판정) / `code` (오류 분류, api-contract) / `reason` (세부 사유). 예: 이용 자격 부족 = `admissionResult=NOT_ACCEPTED`, `code=FORBIDDEN`, `reason=SUBSCRIPTION_REQUIRED`, `retryable` = 예약 대기면 true, 소진·체험 없음이면 false. 체험 기록 없는 기기 = `reason=TRIAL_UNAVAILABLE`, `retryable=false` (사용자 결정 Q10). 숫자 비노출
- 재전송 = 최초 상태 코드 + 저장 판정 (DB 항목) + 현재 실행 상태 (`IntakeProjection`, 첫 응답과 같은 함수). 접수 행이 없으면 저장 본문 그대로. 오류는 저장 응답 그대로
- 사전 검사 오류 = 실행기 확정 오류 경로 (`reject`: 키 잠금 → 재확인 → 저장). 저장 = 403 동의, 400 토큰 누락, DeviceCheck 거절 403. 저장 안 함 = 503 (`INTAKE_DISABLED`·`TRIAL_START_CONFLICT`·`DEVICE_CHECK_UNAVAILABLE`), B6-2 증거 오류 (`ATTESTATION_REQUIRED`·409 `ATTESTATION_CONFLICT`·`ATTESTATION_FAILED`, ADR-0026 저장 규칙의 명시적 예외), 본문·값 오류 (1.1). 인수 조건: 증거 오류 뒤 같은 키로 증거를 보완한 재전송은 저장된 403에 막히지 않음
- 실행기 확장 (구현, ADR-0034): `lookup` (사전 조회·해시 비교), `reject` (확정 오류 저장), `commit` + `Outcome` (202 저장), `ReplayProjection` (2xx 재생만 투영). 비저장 = `PikException.unstored` (`reject`·`commit` 안에서만 의미. 그 전 단계에서 던진 오류는 실행기를 거치지 않아 저장되지 않음)

### 1.6 원본 버전 관측 (단순화)
- `photo.observed_version`, `photo.source_change_seq` (관측값이 직전 `observed_version`과 **다를 때만** +1)
- 접수 항목 (구성원 포함 사진마다)에 `basis_change_seq` = 접수 시점의 그 사진 `photo.source_change_seq` (고정). 결과는 자기 사진 항목의 값을 복사 (대표 값 복사 금지, 사진 사이 비교 금지)
- 원본 변경 표시 = `photo.source_change_seq > result.basis_change_seq`. 처리 중 관측 변경도 완료 뒤 자동으로 표시됨
- 같은 값 반복 관측 = 증가 없음 (확인 무효화 안 함). A→B→A = 두 번 증가 (보수적으로 확인 필요)
- 해제 = 새 분석 (새 `basis_change_seq`) 또는 필드별 `confirmed_source_seq` (0.2). 사진 단위 해제 없음
- 증가 때 `PhotoSourceChanged(owner, photo, seq)` 발행 (5-3 소비, 결과 없으면 리스너 no-op)

## 2. 단계 5-2 업로드·실행·결과·정산
### 2.1 상태
| 현재 | 사건 | 다음 | 함께 (한 TX) |
|---|---|---|---|
| `AWAITING_UPLOAD` | 확정 공통 조건 통과 | `READY` | 시도 `CONFIRMED`, ETag·sha256 |
| `AWAITING_UPLOAD` | 발급 한도 도달 ∧ 현재 시도 사용 불가 (2.3) | `FAILED(UPLOAD_ATTEMPTS_EXHAUSTED)` | 반환, 묶음 해제, 삭제 outbox |
| `AWAITING_UPLOAD` | 업로드 기한 + 유예 경과, 확정 불가 | `CANCELLED(UPLOAD_EXPIRED)` | 반환, 해제, 삭제 outbox |
| `READY` | claim | `RUNNING` | `attempt_count+1`, 새 `run_token`, 임대 |
| `RUNNING` | 결과 저장 | `SUCCEEDED` | 결과, 사진별 정산 (`settle`), 비용·송신 정산, 저장한 결과의 `AnalysisCompleted` 발행, 삭제 outbox |
| `RUNNING` | 일시 실패·예산 연기·회수 (송신 없음)·불명 확정 | `READY` | 토큰 폐기, `next_attempt_at` (불명은 2.6) |
| `RUNNING` | 영구 실패 | `FAILED(사유)` | 반환, 해제, 삭제 outbox |
| 비종료 | 처리 기한 경과 | `CANCELLED(PROCESSING_EXPIRED)` | 반환, 해제, 삭제 outbox |
| 비종료 | 동의 무효 (검사 시점) | `CANCELLED(CONSENT_WITHDRAWN)` | 반환, 해제, 삭제 outbox |
| 비종료 | 주체 비활성·대표 사진 메타데이터 삭제 | `INELIGIBLE` | 반환, 해제, 삭제 outbox |
- 종료 작업은 다시 열지 않는다. 시도 = `ISSUED` → `CONFIRMED`/`REJECTED`/`SUPERSEDED`/`ABANDONED` (작업 종료). URL 만료는 PUT만 막는다
- 호출 = `SENDING` → `SUCCEEDED`/`RESPONDED_FAILURE`/`UNKNOWN`/`NOT_SENT`, 본문 저장 안 함. 정산 상태: `cost_state` (`RESERVED`/`SETTLED`/`ADJUSTED`), `send_state` (`RESERVED`/`SPENT`/`RELEASED`). 정산은 호출 행 CAS로 한 번만

### 2.2 기한 (설정값)
| 기한 | 값 | 기준 |
|---|---|---|
| URL | min(발급+15분, 업로드 기한), 시도에 고정 | 시도 발급 |
| 업로드 기한 | 24시간 | 접수 |
| 확정 유예 | 15분 | 업로드 기한 또는 마지막 시도 URL 만료 (회수 배치만) |
| 처리 기한 | 48시간 (claim·허가·완료에서도 검사) | 접수 |
| 임대 120초 / 송신 여유 70초 / HTTP 전체 60초 | | claim / 허가 / 송신 |
| 임시 객체 | 종료 즉시 삭제 outbox, 청소 6시간, 수명 주기 3일, 미삭제 경보 | |
- 안내 (P08 확정, ADR-0033 #10): "분석 후 즉시 삭제, 남은 경우에도 업로드 후 4일 안에 삭제 대상"

### 2.3 업로드
- 시도 발급 `POST /analysis-jobs/{requestId}/upload-attempts` (`Idempotency-Key`), `items[]` ≤ 100 = `analysisJobId`, `contentLength` ≤ `maxBytes`, `sha256`. 응답 = 대상별 처리 목록 (200)
  - 서명 헤더 `Content-Type: image/jpeg`, `Content-Length`, `x-amz-checksum-sha256`, `If-None-Match: *`. 실제 S3 시험 실패 시 업로드 경로 재결정 (HEAD 후퇴는 동등 보호 아님)
  - **신규 발급** 조건: `AWAITING_UPLOAD` ∧ 업로드 기한 전 ∧ 기준 동의 ∧ 주체 활성 ∧ 미반환 예약 ∧ `issued_count < 5` ∧ 입력 속도 제한 (2.7). 성공 = `issued_count+1`, 이전 `ISSUED` → `SUPERSEDED`
  - 한도 도달 신규 발급 = 항목 `REJECTED`/`UPLOAD_ATTEMPT_LIMIT` (작업 상태는 그대로, 종료는 회수 배치)
  - **재생** (같은 키): 저장된 시도 ID → 소유·기준 동의·주체 활성·그 시도가 현재 `ISSUED`·고정 URL 만료 전이면 같은 만료로 재서명. 횟수 검사 없음. 아니면 항목 `REJECTED`/`ATTEMPT_NOT_CURRENT` (새 키로 신규 발급). 저장 본문에 URL 없음
- 확정 `POST .../upload-attempts/{attemptId}/complete`
  1. 소유 검증 (아니면 404) → 멱등 사전 조회
  2. 같은 시도 `CONFIRMED` = S3 없이 200. 다른 시도 확정·시도가 `ISSUED` 아님·작업 종료 = 409 `ATTEMPT_NOT_CURRENT` (저장)
  3. TX 밖 HEAD: 404 = 409 `UPLOAD_NOT_FOUND` + `Retry-After` (저장 안 함), 403·5xx = 503
  4. TX: 동의 공유 → 작업 → 확정 공통 조건 재확인
  - 확정 공통 조건 (확정 API·회수 배치 공용) = 현재 `ISSUED` ∧ `LastModified` ≤ URL 만료 ∧ 미반환 예약 ∧ 기준 동의 ∧ 주체 활성 ∧ 처리 기한 전
  - 결과 (200, 업무 변경 커밋): 정상 `READY` / 메타데이터 불일치 = 시도 `REJECTED` + 삭제 outbox, 항목 `FAILED`·`UPLOAD_INVALID` / 동의 무효 = `CANCELLED` / 조건 실패 (기한·예약·주체) = 해당 종료 전이 + 항목 사유
- 상태 조회 `GET /analysis-jobs/{requestId}` = URL 없는 안정 투영, `ETag`/304 (소유 검증 뒤), `pollAfterSeconds` + 지터

### 2.4 실행
1. claim TX: `ai_gate`의 모든 원인 중 최대 `blocked_until > now()`면 claim 안 함 (잠금 없이 읽음) → 전역 claim 잠금 → 빈 전역 슬롯 → `READY ∧ next_attempt_at ≤ now() ∧ 처리 기한 전`을 정렬 `effective_priority, queued_at, job_id` 순 `FOR NO KEY UPDATE SKIP LOCKED` (네이티브 SQL, 0.3) → 주체 동시 실행 상한 (초기 4) 초과 주체 건너뜀
   - `effective_priority = max(0, 2×base_priority + 1 − floor(대기/10분) − (접수의 first_result_at이 null이면 1))` (조회 때 계산, 노화 배치 없음). 같은 등급에서 첫 결과가 1단계 앞서고, 후속 작업은 10분 대기면 따라잡음
   - `base_priority` = `NEW_PHOTOS` 0, `ORGANIZE` 1, `PRO_BACKLOG` 2. `queued_at`은 재시도 때 초기화하지 않음. 실행 자원이 계속 공급되고 작업이 자격을 유지하면 base b 작업은 `(2b+1)×10분` 뒤 최상위에 도달 (예산 차단·기한 초과까지 막는다는 뜻은 아님)
   - 대표 부하에서 실행 계획·읽은 행 수·정렬 spill·claim 잠금 p95 측정. 병목이면 오래 기다린 후보를 반드시 포함하는 제한 조회 또는 저장한 승격 시각으로 바꿈
2. 준비 (TX 밖): `If-Match: ETag` GET (최대 바이트에서 중단) → 헤더 크기·픽셀 선검사 → 디코딩 → sha256 대조. 412·404·디코딩 실패 = `FAILED(INPUT_CHANGED/INPUT_MISSING/INPUT_INVALID)`, 송신 없음 (`INPUT_INVALID`는 2.7 반복 실패 카운터 증가)
3. 허가 TX: 동의 공유 → 작업 → **기존 허가 조회** (있으면 그것을 반환, 재송신 안 함) → 조건 (`RUNNING ∧ 토큰 ∧ 남은 임대 ≥ 송신 여유 ∧ 처리 기한 전 ∧ 주체 활성 ∧ 기준 동의 ∧ 입력 확정 ∧ 작업 manifest 실행 가능`) → 예약 (2.7) → `external_call` 삽입 (유니크 (job, run_token), 작업의 manifest ID). **이 커밋이 전송 시작 경계**
4. 송신: 로컬 기한 재확인 (지났으면 호출 `NOT_SENT`) → HTTP 1회 (SDK `maxRetries(0)`, 시간 제한 명시)
5. 응답 처리 (TX 밖): 공통 검증기 (2.5)·결과 변환
6. 완료 TX: 동의 공유 → 구성 사진 존재 확인 (공유) → 접수 (`first_result_at`) → 작업 → 호출 → 할당 → 주체 일일 → 기기 → `photo_ai_budget` → 일일 비용
   - 승자 = `RUNNING ∧ 토큰 일치` (임대가 지났어도 회수가 먼저 커밋하지 않았으면 허용) ∧ 처리 기한 전
   - 동의·주체 무효 = 응답 폐기 + 해당 종료 전이 (정산은 그대로)
   - 결과 저장: 대표 `DIRECT`, 구성 `INHERITED` (원래 작업 ID 보관, ADR-0024 #6-3), 자기 사진 항목의 `basis_change_seq`·작업 manifest ID 복사
   - 예약 집합 R을 결과를 저장할 사진 C와 메타데이터가 삭제된 구성원 D로 나눔 (C ∩ D = ∅, C ∪ D = R, ID로 검사). usage `settle(principal, job, C, D)` 한 번으로 C = consume, D = release (quota·기기 잠금 아래). 기존 작업 전체 consume/release를 순서대로 호출 금지. 재실행 멱등 = 예약별 최종 상태가 요청과 같으면 no-op
   - `first_result_at`이 null이면 지금 시각으로 설정 (첫 `SUCCEEDED`)
   - 비용·송신 정산 1회 (2.5 표), **실제 저장한 결과에만** `AnalysisCompleted(owner, photo, resultId, epoch=1)` 발행, 삭제 outbox → `SUCCEEDED`. 이미 발생한 비용·위험 송신은 구성원 반환과 무관하게 유지
   - 진 응답 = 결과 버림, 호출만 정산. DB 일시 실패 = 같은 응답으로 완료 TX만 제한 재시도
7. 회수: `RUNNING ∧ locked_until < now()` → 그 토큰의 호출 없음 = `READY` + 토큰 폐기, `SENDING` 호출 있음 = 불명 확정 (2.6)

### 2.5 Claude 호출·응답
- 호출 manifest (불변 행, ID = 내용 SHA-256, 기동 때 설정에서 만들고 없으면 삽입, 범위 검사 실패 = 기동 실패): model `claude-haiku-5-5`, promptVersion (프롬프트 본문 해시), schemaVersion (결과 v1), thinking 설정, effort 초기 `low`, `max_tokens` 1,024, `maxLongEdgePx`, `maxBytes`, `maxPixels`, contentType, 이미지 토큰 상한, OCR 사용·상한 (초기 사용 안 함), 캐시 정책 (prefix 범위·5분), `outputLocale` (생성 라벨 언어, 초기 `ko-KR`), 가격 버전 (입력·캐시 생성·캐시 읽기·출력 단가), 최대 비용 (계산값). 값은 모두 D-07 측정 대상
- 접수가 현재 manifest를 작업에 고정 (`uploadPolicy`의 근원). 호출·결과도 **작업의 manifest**로 실행·기록. 실행 가능한 manifest 목록 = 현재 + 이전 (프롬프트·스키마 자원을 버전별로 보관). 이전 manifest를 실행할 수 없게 만드는 배포는 해당 작업이 빠진 뒤 (런북)
- 원장 (`external_call`): manifest ID, 입력 sha256, 예산 일자, 사용 토큰 (입력·출력·캐시 생성·캐시 읽기), 정산 상태
- 캐시: 안정된 공통 prefix만, 늘리지 않음, 사용량으로 이득 확인
- 응답 처리 순서: `end_turn`이고 JSON 전체가 파싱될 때만 처리 (`max_tokens` = 정산표대로 분석 전체 `FAILED(TRUNCATED)`, 검색 블록만 잃는 부분 성공 없음). 원시 JSON 트리로 파싱하고, SDK helper의 엄격 스키마 검증으로 전체를 거절하지 않는다. 액션·이벤트 부분과 `search` 블록 (4.1)을 나누어 검증한다
- 공통 순수 검증기 (A2 완료 전 실행, A3 재사용): 액션·이벤트 부분의 스키마 위반·전체 사용 불가 (4.1) = 분석 `FAILED(INVALID_OUTPUT)` / 필드 오류 = `validation` 붙여 저장 / 근거 없음 = 정상. 날짜 범위 (촬영 시각 −1년~+3년)는 이상 탐지 (`NEEDS_CHECK`). `validation_reference_at` = 촬영 시각, 없으면 접수 시각

**정산표 (유일 기준)**. 위험 송신 = 사진 평생·주체 일일·기기 평생 (체험 작업만) 세 횟수를 함께 다룬다.
| 응답 | 호출 상태 | 비용 | 위험 송신 | 체험 사진 예약 | 작업 |
|---|---|---|---|---|---|
| 2xx `end_turn` + 검증 통과 | `SUCCEEDED` | 실제 | 소진 | 사진별: 결과 저장 = consume, 삭제 구성원 = release | `SUCCEEDED` |
| 2xx `refusal`/`max_tokens`/검증 실패 | `RESPONDED_FAILURE` | 실제 | 소진 | 반환 | `FAILED(REFUSAL/TRUNCATED/INVALID_OUTPUT)` (`TRUNCATED` 경보) |
| 400 | `RESPONDED_FAILURE` | 사용량 있으면 실제, 없으면 최대 | 소진 | 반환 | `FAILED(PROVIDER_REJECTED)`, 비율 높으면 회로 |
| 401·403·404 (**예외**) | `RESPONDED_FAILURE` | 사용량 없으면 최대 | **해제** (우리 설정 오류, 회로가 반복을 막음) | 유지 | 회로 + 경보, `READY` 연기 |
| 429·529 | `RESPONDED_FAILURE` | 0 | 해제 | 유지 | `Retry-After`·지터 백오프 `READY` |
| 500·504, 송신 뒤 끊김·시간 초과 | `UNKNOWN` | 최대 (같은 worker가 늦게 받으면 1회 `ADJUSTED`) | 소진 | 유지 | 불명 (2.6) |
| 송신 전 실패·로컬 기한 초과 | `NOT_SENT` | 0 | 해제 | 유지 | 백오프 `READY` (기한 지났으면 해당 종료) |
- 규칙: 사용량 없이 최대 비용으로 정산하는 응답은 위험 송신도 소진. 예외는 401·403·404 하나뿐
- 설정 오류 회로 (401·403·404, 또는 400 비율이 임계 초과): manifest·키 설정 버전이 바뀌거나 운영자가 풀 때까지 열림. 풀린 뒤 전역 claim 잠금 아래 `ai_gate.probe_job_id` 1개만 지정해 claim, 허가에서도 probe 자격 확인, 성공해야 정상 claim 재개. probe 사망은 임대 회수로 재개. 이미 커밋한 허가는 회로가 취소하지 않음. HTTP 상태만으로 원인을 단정하지 않고 경보로 운영자 확인
- 일시 회로 (429·529·5xx 연속): 지수 백오프로 자동 반개방
- 작업이 실패·취소·대상 아님으로 끝나면 체험 사진 예약은 전체 반환. 성공은 사진별 (결과 저장 = consume, 삭제 구성원 = release)

### 2.6 불명 (사용자 결정 2026-11-01, D-26: 사진마다 1회 자동 재호출)
- 불명 확정 TX = 호출 `UNKNOWN` + 최대 비용 확정 + 위험 송신 소진 + 토큰 폐기
  - `photo_ai_budget.unknown_retry_used = false` → true로 바꾸고 `READY` (**중복 과금을 감수하는 자동 재호출 1회**, 사진 수명 1회, 새 작업에서도 셈)
  - 이미 true = `FAILED(UNKNOWN_OUTCOME)` + 반환
- 재호출은 새 claim부터, 허가에서 동의·예산·기한 재검사. 제공자 회로가 열리면 보류 (처리 기한 안 연기)
- 표시 = "결과를 얻지 못해 종료". 호출이 실행되지 않았다고 표시하지 않음

### 2.7 예산·입력 제한
| 예산 | 저장 | 한도 | 부족 시 |
|---|---|---|---|
| 전역 일일 비용 | `ai_spend_day` (KST, 한도·예약·확정, 마이크로달러) | 설정값 | `READY` 연기 + `ai_gate` 설정 + 경보, 처리 기한 지나면 취소 |
| 기기 평생 송신 (`TRIAL` 작업만) | `device_trial_mark.send_count` (usage) | 체험 한도 × 2 | `FAILED(DEVICE_BUDGET_EXHAUSTED)` + 반환 |
| 주체 일일 송신 | `ai_send_daily` | 300 | 다음 날 연기 (처리 기한 안) |
| 사진 평생 송신 | `photo_ai_budget.risk_sends` (사진 수명 동안 유지) | 3 | `FAILED(ATTEMPTS_EXHAUSTED)` + 반환, 재접수 `NOT_ACCEPTED` |
| 전역 동시 실행 | claim 슬롯 | 설정값 | claim 안 함 |
| 주체 동시 실행 | claim의 주체별 `RUNNING` | 4 | 건너뜀 |
| 전역 송신 속도 | 인스턴스 버킷 × 최대 인스턴스 (근사) | 설정값 | 대기 |
- 위험 송신은 허가 때 예약, 정산표대로 소진·해제. 최대 비용 = manifest의 계산값 (캐시 prefix × 캐시 생성 단가 + 나머지 입력 × 입력 단가 + `max_tokens` × 출력 단가, 마이크로달러 올림)
- 전역 사전 차단 `ai_gate` (원인별 행: `BUDGET`, `CONFIG_CIRCUIT`, `TRANSIENT_CIRCUIT`, 각 `blocked_until`): 허가가 예산 부족을 확인하면 확정만으로 한도 도달 = 다음 KST 자정, 예약 때문이면 +10분. 같은 원인의 활성 기한을 줄이는 갱신 금지 (`GREATEST`), 해제는 원인별 명시 동작만. claim은 잠금 없이 최대값을 읽고 건너뜀 (허가의 원자적 검사는 그대로)
- 입력 비용 제한 (Claude 비용과 별개, 기존 요청 속도 제한 저장소 사용):
  - 시도 발급 = **항목 수**와 허용 바이트 합 (`항목 × maxBytes`)을 주체 기준·체험 기기 기준 (`TRIAL`) 각각 시간당 상한
  - 같은 사진의 `INPUT_INVALID` 3회 = 24시간 `NOT_ACCEPTED`/`INPUT_RETRY_LATER` (`retryAfter`), 체험 칸은 소모하지 않음 (`photo_ai_budget.input_invalid_count`·`input_blocked_until`)
  - 디코더 바이트·픽셀 상한, 전역 유입 제한 유지
- 한계: 같은 파일의 새 `photoId`는 사진 카운터를 따로 센다. 주된 방어 = 기기·주체·전역

### 2.8 실행 모드 (사용자 결정 2026-11-01, D-25)
- 체험 첫 실행도 worker의 Messages 호출. Batch는 Pro 최초 처리량 (단계 8)만. 접수 HTTP는 202 그대로
- 근거: Batch는 최대 24시간 창. 사진 1장 비용 (2026-11-01 추정) 동기 약 $0.00058~0.00081 / Batch 절반. 첫 결과 시간을 비용보다 우선
- 조건: 슬롯·주체 상한 구현, 첫 결과 p95·100장 완료 시간·사용자당 비용 측정

### 2.9 회수·청소·시험
- 회수 배치 (5분, ShedLock), 대상 = `AWAITING_UPLOAD` 중 (업로드 기한 + 유예 경과) ∨ (`issued_count = 5` ∧ 현재 시도 URL 만료 + 유예 경과) → 현재 시도 HEAD → 확정 공통 조건이면 확정, 404·`REJECTED`면 종료 (`UPLOAD_EXPIRED` 또는 `UPLOAD_ATTEMPTS_EXHAUSTED`), 403·5xx는 다음 주기
- 처리 기한 배치 = 비종료 → 취소
- 청소 우선순위: 종료 작업 = 객체 전부 삭제 (`CONFIRMED` 포함) → 비종료 `READY`·`RUNNING` = 확정 입력만 보호 → `AWAITING_UPLOAD` = 현재 `ISSUED` 시도 객체 보호 → `REJECTED`·`SUPERSEDED`·`ABANDONED` = URL 만료 뒤 삭제
- 시험 3층: 단위 대역 / 버전 고정 S3Mock 5.1 (서명·만료 미검증) / 운영 배포 전 실제 S3 계약 시험

### 2.10 예외 표
| 상황 | 처리 |
|---|---|
| 구성 사진 메타데이터가 처리 중 삭제 | 작업 계속, 완료 때 그 사진 결과는 쓰지 않고 그 예약은 반환 (결과를 만든 칸만 비반환, ADR-0024 #1) |
| 대표 사진 메타데이터 삭제 | 작업 `INELIGIBLE` + 반환 |
| 송신하지 않은 허가 (기한·연결) | 호출 `NOT_SENT`, 정산표 마지막 행 |
| 불명 재호출을 이미 쓴 사진의 불명 | `READY`를 거치지 않고 `FAILED(UNKNOWN_OUTCOME)` |
| 원장 보관 기간 뒤 늦은 정산 | no-op (사진 카운터는 남아 있으므로 한도는 유지) |

## 3. 단계 5-3 액션·이벤트 생성
범위 밖: 번역·웹 탐색 (단계 6·11), 알림 소비 (단계 9)

### 3.1 생성 (모듈별 소유)
- 입력 = `AnalysisCompleted(owner, photo, resultId, epoch)`. 수동 재처리 (규칙 수리)는 같은 이벤트를 새 epoch로 발행 (`analysis_result.generation_epoch` 증가)
- 리스너: `action` 모듈 (모든 주체), `event` 모듈 (로그인 주체만, 비로그인은 3.4). Modulith `@ApplicationModuleListener`, 프로필 조건 없이 api·worker 모두 등록 (발행 때 리스너별 기록이 남도록)
- 생성 행: `action_generation(owner, photo)`, `event_generation(owner, photo)`. 열 = `source_result_id`, `epoch`, `status` (`READY`/`NO_RESULT`/`BLOCKED`/`FAILED`/`RETRYING`), `outcome` (이벤트: `LINKED`/`CREATED`/`AMBIGUOUS`/`SUPPRESSED`/`NONE`), `failure_count` (롤백 뒤 영속 기록된 업무 실패 수, 작업 claim 수·HTTP 호출 수·위험 송신 수·publication 시도 수와 별개), `failure_code`, `version`
- 진입 함수 (짧은 단일 TX, 선행 잠금 먼저):
  - ACTION: photo 공유 (변경 세대) → 생성 행 upsert·잠금 → (결과, epoch)가 analysis의 현재 값보다 오래됐거나 같은 입력이 이미 완료면 no-op → 주체 활성·사진 존재 확인 (아니면 `BLOCKED`) → 생성 (3.2) → `photo_action` (id 순) → `owner_change_counter`
  - EVENT: 주체 이벤트 잠금 → photo 공유 → 생성 행 → 같은 확인 → 판정 (3.3) → `event`·`event_photo` (id 순) → `owner_change_counter`
- 동의는 다시 보지 않는다 (외부 전송 없음, ADR-0021 #5 기존 결과 유지)
- 실패 기록 (리스너 자체, publication 내부 구조에 의존하지 않음):
  1. 조정 리스너 = `@ApplicationModuleListener(propagation = NOT_SUPPORTED)` (TX 없음). 생성 본체는 **다른 빈의 TX 메서드** (또는 `TransactionTemplate`)로 실행 (자기 호출로 프록시 우회 금지)
  2. 그 TX가 commit/rollback을 마친 뒤 예외를 잡는다 → 실패 기록 TX: 같은 선행 잠금 순서로 생성 행을 (결과, epoch) 기준 upsert. 그 입력이 analysis의 현재 값이고 같은 입력이 종결 (`READY`/`NO_RESULT`/`FAILED`/`BLOCKED`)이 아닐 때만 `failure_count+1`, `RETRYING`, `failure_code` (옛 epoch 실패는 새 epoch 행을 바꾸지 않음)
  3. 결과 `RETRY` (`failure_count` < 상한, 초기 5) = 예외를 다시 던짐 → publication 실패 → 재제출
  4. 상한 도달 `FAILED`, 또는 이미 종결·대체된 입력 = **정상 반환** → publication 완료 (`completion-mode: delete`로 삭제). 소진 publication이 재제출 대상 앞자리를 막지 않음. `FAILED` 저장 뒤 완료 기록 전에 죽어도 다음 실행이 종결 입력을 no-op으로 보고 정상 반환
  5. 실패 기록 TX 자체가 실패 = 예외를 다시 던짐 (정상 반환 금지)
- 재제출·회수 (Modulith 2.1.2): stale 회수 = `spring.modulith.events.staleness.published`·`processing`·`resubmitted`·`check-interval` 명시 (미설정이면 회수 비활성). 실패 publication 재제출 = 고정 간격 주기 작업, 제한 batch. `completionAttempts` ≥ 20은 **경보만** (재제출 필터로 제외하지 않음: JDBC가 LIMIT로 먼저 읽고 필터를 뒤에 적용하므로 뒤의 정상 대상을 가림). 실패 기록 전 반복 사망·DB 장기 불능은 운영 장애로 처리
- `FAILED` 자동 재개 금지, 수동 재처리 = 새 epoch
- 상태 투영: 행 없음, 행 입력이 현재 결과·epoch보다 오래됨, `RETRYING` = `PENDING`. 비로그인 주체의 이벤트 = `NOT_APPLICABLE`
- `READY` = 생성 계산 완료 (`NEEDS_CHECK` 액션 포함 가능)
- 보정 배치 없음. 대신 "발행 시 리스너 등록"을 프로필별 통합 시험으로 확인, 접수 활성화는 4.4

### 3.2 액션 (복사·전화·지도·일정)
- 행: `action_id`, 종류, `generated_value` (원문 + 정규화값 + `canonicalization_version`), 자동 근거 (출처·추출·검증·분석 버전·`basis_change_seq`), `user_value`, `user_state` (`NONE`/`CONFIRMED`/`EDITED`/`HIDDEN`), `confirmed_source_seq`, `auto_state` (`ACTIVE`/`STALE`), `version`. 실행 가능 여부는 저장하지 않고 0.2 함수로 계산
- 대응: 같은 종류에서 새 후보가 기존 `generated_value` 또는 `user_value`와 같으면 같은 행. 여러 행과 같으면 사용자 보호 행 (`CONFIRMED`·`EDITED`·`HIDDEN`) 우선 → `action_id` 오름차순. 보호 행이 둘 이상 맞으면 새 후보는 어느 행에도 붙이지 않음 (합치거나 숨기지 않음). 정규화 버전이 다르면 저장 원문을 새 규칙으로 다시 정규화해 비교
- 자동 값 종류별 조건 (0.2 3번):
  - 전화 = 다음 중 하나 ∧ 그 국가 유효 번호:
    - `EXPLICIT_CODE`: 번호에 국가 코드
    - `LINKED_ADDRESS`: 그 연락처와 같은 블록의 주소·국가명이 국가 X를 가리킴 (모델이 연결을 보고, D-07에서 오류율 측정)
    - 그 밖 = `NEEDS_CHECK` (확인 화면 기본 국가 KR, 설정값). 언어·통화·도메인은 기본 국가 제안으로만 사용. 근거 없는 국가 추론 분기는 첫 출시에 두지 않음 (`02-212-3456`은 26개 지역, `010-2000-0000`은 7개 지역에서 유효해 편익이 입증되지 않음, R7)
  - 일정 = 날짜 `READ` ∧ (시각 `READ` 또는 종일) ∧ 시간대 근거 `EXPLICIT_OFFSET` (그 일정에 IANA·오프셋이 명시됨) 또는 `PLACE_LINKED` (그 일정 장소·도시 표기의 국가가 단일 시간대 국가). 모호한 도시 이름은 `EXPLICIT_OFFSET`이 아님. 문서 언어는 근거 아님. 그 밖 = `NEEDS_CHECK` (확인 화면 기본 KST)
  - 지도 = 정규화 검색어만 반환, 단말이 지도 앱을 연다
- `STALE` = 후보 집합이 완전히 검증된 세대에서만, `user_state = NONE` 행만, 버전 CAS. `HIDDEN`은 자동 복구 안 함
- 확인·수정 = 4.2 Command. 액션 버전 + `sourceChangeSeq` CAS, 오래되면 409
- 이미지 안 문장은 데이터. 실행 URL은 서버·단말이 만든다 (AI 출력 URL 미사용)

### 3.3 이벤트 (로그인)
- 사진당 이벤트 후보 1개 (v1 한계): 일정 중 확인된 필드 수 (날짜·시각·장소·행사명이 `READ ∧ VALID`) 최대 → 이른 날짜 → 배열 순서. 나머지 일정은 `NEEDS_CHECK` 일정 액션
- 비교 값: 날짜·시각·장소 = 사용자 확정값이 있으면 그것, 없으면 자동 근거. 행사명 = 자동 근거만 (사용자 이름 변경은 비교 제외). 표시값은 비교에 쓰지 않음
- 차원별 판정 (P05 설정값 τ, 규칙 버전 고정):
  | 차원 | 같음 | 모순 | 불명 |
  |---|---|---|---|
  | 날짜 | 양쪽 확인값 일치 | 양쪽 확인값 다름 | 한쪽 없음 |
  | 시각 | 양쪽 확인값 일치 (시간대 환산 뒤) | 양쪽 확인값 다름 | 한쪽 없음·종일 |
  | 장소 | 정규화 도로명 주소 일치, 또는 정규화 이름 일치 ∧ 한쪽 이상 주소 없음 | 양쪽 주소의 시·군·구 다름 | 그 밖 (같은 시·군·구의 다른 주소는 이름이 같아도 불명) |
  | 행사명 | 유사도 ≥ τ_high | 유사도 ≤ τ_low | 사이 |
- 강한 일치 = 모순 없음 ∧ 날짜·장소 같음 ∧ 시각·행사명은 같음 또는 불명
- 경쟁 후보 = 모순 없음 ∧ 날짜 같음 ∧ 장소·시각·행사명 중 하나 이상 같음 (날짜만 같고 나머지가 모두 불명인 이벤트는 경쟁 후보 아님)
- 결과: 강한 일치 1개 ∧ 다른 경쟁 후보 0 = `LINKED` / 강한 일치 0 ∧ 경쟁 후보 0 = `CREATED` / 그 밖 = `AMBIGUOUS` (연결·생성 안 함, `NEEDS_CHECK` 일정 액션). 강한 일치 대상이 `HIDDEN`·`COMPLETED`이거나 그 사진이 `EXCLUDED` = `SUPPRESSED` (새 이벤트로 바꾸지 않음)
- 대가: 날짜만 같은 정보 부족 이벤트와 중복 생성 가능. D-07에서 오병합률·중복률·확인 요청률 측정
- 날짜 없는 의도 = 구체 대상 (정규화 이름) 일치만 연결, 아니면 날짜 없는 이벤트 생성 (홈 카드, 알림 대상 아님)
- 상대 날짜 = 촬영 시각 기준 계산 + `ESTIMATED` (병합·자동 알림에 쓰지 않음). 구성 사진 (`INHERITED`)은 자동 연결 안 함
- 기여 관계 = `event_photo` (`source_result_id`, 근거, 상태 `INCLUDED`/`EXCLUDED`). no-op은 같은 (이벤트, 사진, 결과) 관계 추가 중복에만. 새 epoch는 필드 재검증·규칙 재평가 수행
- 필드별 근거: 날짜·시각·장소마다 근거 사진·분석 버전·사용자 입력 여부. `user_locked_fields`는 이벤트 행 잠금 뒤 최신값으로 판단
- 저장 TX에서 `change_seq` (홈 갱신 = 동기화)와 `notification_version` 증가 (날짜 확인·자격 변경 시). 단계 9 알림이 `notification_version`을 백필 소비

### 3.4 이벤트 (비로그인)
- 서버 = 무상태 판단 Query (4.2). 단말 한 로컬 TX = 리비전 CAS + 적용 + 적용 키 기록 + 리비전 증가. 경쟁에서 지면 최신 요약으로 다시 요청
- 적용 키 (사진 + 결과) = `LINKED`·`CREATED`만. 같은 적용 키는 다른 리비전에서도 다시 적용하지 않음. `AMBIGUOUS`·`SUPPRESSED`는 적용 키 없음 (리비전이 바뀌면 재판정)
- 요약 완전성: 요약 종류 `DATED` = 일정일 ±1일 범위의 이벤트 전체, `UNDATED` (날짜 없는 의도) = 정규화 대상 이름이 같은 이벤트 전체. 50개 초과면 `complete=false`로 보내고, 서버는 그 요청에 `LINKED`·`CREATED`를 모두 내지 않는다 (`AMBIGUOUS`, 효과·적용 키 없음)
- 이벤트마다 기여 사진 목록 대신 **요청 사진과의 관계 상태** (`NONE`/`INCLUDED`/`EXCLUDED`)를 필수로 보낸다 (사용자 제외 보존)
- 평가 맥락 (`basisRevision`, 규칙 버전)은 응답에만 담고 효과 키와 분리

### 3.5 원본 변경
- 1.6의 `source_change_seq`로 판단 (결과·액션이 없어도 기록됨). `PhotoSourceChanged` 리스너가 해당 사진의 액션·이벤트 자격을 다시 계산
- 액션: 0.2 함수 결과가 바뀌면 투영만 바뀜 (`NEEDS_CHECK(SOURCE_CHANGED)`)
- 이벤트: 바뀐 근거에 기대는 필드의 자동화 자격을 다시 계산. 다른 `DIRECT` 근거나 사용자 확정이 있으면 유지, 없으면 확인 필요 + `notification_version` 증가. 표시값·연결은 유지
- 원본 삭제·권한 철회 = P11 흐름. 단계 9 동기화 감지도 같은 처리 함수

## 4. 구현 계약
### 4.1 분석 결과 스키마 v1 (모델과 무관한 내부 DTO + JSON Schema)
- `context`: `summary` ≤ 200자 (사진의 객관적 내용·관계를 담는 한두 문장, **표시와 검색 색인 공용**, 로그 금지), `primaryLanguage` (BCP 47), `suggestedCountry` (ISO 3166-1 alpha-2 또는 null, 확인 화면 기본값 제안 전용)
- `schedules[]` ≤ 3: `title` (≤ 100, `extraction`), `date`·`startTime`·`endTime` (값 + `extraction`), `allDay`, `timezone` (IANA 또는 오프셋 + `EXPLICIT_OFFSET`/`PLACE_LINKED`/`NONE`), `place` (`name` ≤ 100, `address` ≤ 200, `countryCode`, `extraction`), `relativeDateText` ≤ 50
- `phones[]` ≤ 5: `raw` ≤ 32, `label` ≤ 50, `countryCode` (명시·연결된 경우), `countryEvidence` (`EXPLICIT_CODE`/`LINKED_ADDRESS`/`NONE`), `extraction`
- `accounts[]` ≤ 3: `bank` ≤ 30, `number` ≤ 32, `holder` ≤ 30, `extraction`
- `copyTexts[]` ≤ 5: `text` ≤ 500, `kind` (`URL`/`COUPON`/`ADDRESS`/`OTHER`), `extraction`
- `places[]` ≤ 3: `name` ≤ 100, `address` ≤ 200, `countryCode`, `extraction`
- `undatedIntents[]` ≤ 3: `targetName` ≤ 100, `kind` (`VISIT`/`BUY`/`WATCH`/`OTHER`)
- `search` (검색용 파생 정보, FR-I-03): 검색 값은 자동 실행 근거가 아니다 (0.2 대상 아님, 액션으로 승격 금지). 근거 구분 = 위치: `observedKeywords`만 사진에서 읽은 값, 나머지는 추정. 빈 배열·null = 판단 불가 또는 해당 없음
  | 필드 | 형식·상한 |
  |---|---|
  | `entities[]` | ≤ 8, `{label ≤ 20자, colors[] ≤ 2}` (사물별 색) |
  | `backgroundColors[]` | ≤ 2. 색 enum 12종: `RED`·`ORANGE`·`YELLOW`·`GREEN`·`BLUE`·`PURPLE`·`PINK`·`BROWN`·`BLACK`·`WHITE`·`GRAY`·`BEIGE` |
  | `scenes[]` · `activities[]` | 각 ≤ 3, 각 ≤ 20자 |
  | `moods[]` | ≤ 2: `CHEERFUL`·`CALM`·`ROMANTIC`·`FESTIVE`·`GLOOMY`·`TENSE`·`NOSTALGIC`·`NEUTRAL` |
  | `peopleCount` | `NONE` (사람 없음 확인)/`ONE`/`FEW` (2~5)/`MANY` (6+)/null (판단 불가) |
  | `contentType` | `PHOTO`/`DOCUMENT`/`RECEIPT`/`POSTER`/`TICKET`/`MENU`/`BUSINESS_CARD`/`OTHER`/null. 내용 종류만 (스크린샷 여부 = 단말 메타데이터) |
  | `saveIntent` | `REFERENCE`/`TODO`/`SHOPPING`/`MEMORY`/`OTHER`/null (`OTHER` = 확인된 다른 범주) |
  | `observedKeywords[]` | ≤ 10, 각 ≤ 30자, 사진에 적힌 원문 그대로 (본문 전체 아님) |
  - 생성 라벨 언어 = manifest `outputLocale`, 읽은 문구 = 원문. locale 변경으로 기존 결과를 재분석하지 않음
  - 시간·장소: 촬영 시각 = 단말 메타데이터, 행사 시간·장소 = `schedules`·`places`. 검색 블록에 날짜·주소를 다시 뽑지 않음
  - 개인정보: 모든 자유 문자열 (`summary`·`label`·`scenes`·`activities`)에서 얼굴 식별, 외모 기반 신원·나이·인종·종교·건강·성적 지향·정치 성향 등 민감 속성 추론 금지. 사진에 인쇄된 글은 `observedKeywords`에 원문 허용 (명함 이름 포함, DB 저장만, 로그 금지)
  - 검증: 액션·이벤트 부분과 분리한 정규화. enum 밖 값 = 그 항목 버림, 길이 초과 = 절단 + `truncatedFields`, 블록 누락·타입 오류 = 블록 폐기. 검색 블록 문제만으로 분석을 실패시키지 않음
  - `searchState` (서버 계산, 정규화 전 오류 유무 보존): `AVAILABLE` = 유효 항목이 남음 / `EMPTY` = 오류 없는 정상 빈 블록 / `DISCARDED` = 블록 누락·타입 오류 또는 오류 항목을 버려 유효 항목이 없음. 이 상태는 `search` 블록에만 해당하고, 사진의 검색 가능 여부는 `summary`·시간/장소 투영·단말 메타데이터를 따로 반영해 계산 (`EMPTY` ≠ 검색 불가)
  - `INHERITED` 구성 사진 = 대표의 검색 블록을 이어받되 직접 읽은 사실로 표시하지 않고 사진별 메모·태그를 덮어쓰지 않음 (FR-S-03)
- 서버가 붙임: `verification`, `validation`, `basis_change_seq`, manifest ID, `schemaVersion`, `outputLocale`, `searchState`
- 배열 개수 초과 = 앞에서부터 자르고 `truncatedFields` 기록 (스키마 위반 아님). 길이 초과: 표시 텍스트 (`summary`·`title`·`label`·`text`·`name`)만 절단, 실행 값 (`raw`·`number`·날짜·시각·주소)은 자르지 않고 그 항목 `INVALID`. 모든 배열이 비어 있음 = 정상 (생성 `NO_RESULT`)
- 전체 사용 불가 = 스키마 위반 (필수 키·타입) 또는 제시된 항목이 모두 `INVALID`

### 4.2 공개 API (5-3)
| API | 종류 | 계약 |
|---|---|---|
| `GET /photos/{photoId}/analysis` | Query | 소유 검증 (아니면 404). `analysisStatus` (`NONE`/`IN_PROGRESS`/`DONE`/`FAILED`), `analyzedVersion`, `sourceChangeSeq`, `generation` (`action`·`event` 상태: 분석 없음 = `NONE`, 3.1 투영, 비로그인 이벤트 = `NOT_APPLICABLE`), `actions[]` (`actionId`, 종류, 표시값, `readiness`, 사유, `version`), `event` (`eventId`, `outcome`) 또는 null. 검색 투영: `summary`, `search`, `searchState`, `schemaVersion`, `outputLocale`, `verification`, `analyzedVersion`, `resultVersion`, `basisChangeSeq`, `sourceChanged` (= `photo.source_change_seq > result.basis_change_seq`, 구성 사진은 자기 기준값), 묶음·출처 작업 식별자, `searchTimePlace` (`schedules`·`places`의 날짜·시각·시간대·장소 이름·주소 + 읽음/추정·검증 상태, 액션·이벤트 생성 성공과 무관하게 결과 저장만으로 제공, 촬영 시각과 구분). 체험 대상 `trialEligible` (서버 계산, 정의·전이: `later-stage-policies.md` C9 P02, 자격 변경 = ETag 갱신). `ETag`/304 |
| `POST /photo-actions/{actionId}/confirm` | Command (`Idempotency-Key`) | 본문 `version`, `sourceChangeSeq`, 선택 `value`·`countryCode`·`timezone` (있으면 `EDITED`, 없으면 `CONFIRMED`). 소유 검증 (아니면 404). 버전·seq가 오래되면 409 `VERSION_CONFLICT`. 200 = 갱신된 액션 |
| `POST /event-decisions` (비로그인) | 상태를 바꾸지 않는 판단 Query (본문이 커서 POST, 멱등 키 불필요) | 본문 `photoId`, `resultVersion`, 요약 (`revision`, `kind` (`DATED`/`UNDATED`), `complete`, `range` 또는 `targetName`, `events[]` ≤ 50: `eventId`, 비교 값, 보호 상태, 요청 사진과의 관계 상태 (`NONE`/`INCLUDED`/`EXCLUDED`)). 서버가 요청 주체 소유 사진의 저장 결과를 찾아 `resultVersion` 일치 확인 (다르면 409). `complete=false`면 `LINKED`·`CREATED` 없음 (`AMBIGUOUS`). 응답 = `basisRevision`, `resultVersion`, 판정, `proposalId`, 적용 키 (`LINKED`·`CREATED`만) |

### 4.3 모듈 소유·DDL 계약
| 모듈 | 소유 | 공개 함수·이벤트 |
|---|---|---|
| `photo` (5-1 구현, `shared`만 의존) | `photo` (`client_ref`, `observed_version`, `source_change_seq`, `change_seq`), `photo_vector` (불변 벡터 버전) | `PhotoObservations` (`observe`·`issueChangeSeqs`·`findIds`·`keepVectors`), `PhotoSourceChanged`. 변경 세대 공유 조회 = 5-3 |
| `analysis` (5-1 구현, `shared`·`account`·`consent`·`usage`·`photo` 의존) | 5-1: `analysis_grouping_rule`, `analysis_call_manifest`, `analysis_request`, `analysis_request_item`, `analysis_group`, `analysis_job` (접수 열). 5-2: `analysis_upload_attempt`, `external_call`, `analysis_result`, `photo_ai_budget`, `ai_spend_day`, `ai_send_daily`, `ai_gate`, `analysis_job` 실행 열 | 5-1 공개 API 없음. 5-2: `AnalysisCompleted`, 결과·현재 epoch 조회, 수동 재처리 |
| `usage` | 체험 할당·예약, `device_trial_mark.send_count` | reserve, 사진별 `settle(principal, job, consumeIds, releaseIds)` (신규), 작업 전체 release (실패·취소), 기기 송신 예약·정산 |
| `action` | `photo_action`, `action_generation` | 리스너 2개, 확인 Command, 생성 투영 |
| `event` | `event`, `event_photo`, `event_generation` | 리스너 2개, 비로그인 판단 Query |
- FK는 모듈 안에서만. 모듈 밖 참조는 ID 값
- 결과 저장·삭제 = 같은 TX에서 `owner_change_counter`로 `change_seq` 발급 + 출처·버전 기록 (단말 검색 색인 동기화의 기반). 결과 변경 피드·전체 동기화 API는 단계 9 공통 동기화 (`later-stage-policies.md` C10)
- 유니크·부분 인덱스: `uk_analysis_job_photo_active` (`owner_id`, `photo_id`) `WHERE status NOT IN (FAILED, CANCELLED, INELIGIBLE)` (ADR-0024 #4-1, `SUCCEEDED` 포함), `uk_usage_reservation_photo` (`principal_id`, `photo_id`) `WHERE status <> RELEASED`, 작업당 현재 `ISSUED` 시도 1개, `external_call (job, run_token)`, `READY` (`next_attempt_at`, `base_priority`, `queued_at`), 주체별 `RUNNING`, 생성 행 PK (owner, photo), `ai_gate` PK (원인)
- V5 (5-1, `V5__photo_and_analysis_intake.sql`): 시각 = `TIMESTAMP WITH TIME ZONE`, ID = UUIDv7, 모듈 밖 ID (주체·기기·체험 기록·사진·벡터)는 FK 없음. 표별 요약은 아래

| 표 | 열·제약 (요약) |
|---|---|
| `photo` | `owner_id`·`client_ref` (UNIQUE), `observed_version` (1~128자), `source_change_seq` (새 행 0), `change_seq` (0 = 커밋 전 임시값, 업무 TX 마지막 발급), `version` DEFAULT 0 (관측은 올리지 않음). ERD의 `cloud_ref`·`os_taken_at`·`os_modified_at`·`availability`·`original_deleted_at`·`memo`·`deleted_at`·관련 인덱스는 소유 단계 (9·P11) |
| `photo_vector` | `id` PK, `photo_id` FK, `model_id`·`model_version` (1~64자), `encoding = 'F32LE'`, `dims > 0`, `vector` (`octet_length = dims × 4`), `content_sha256` (32바이트), UNIQUE (`photo_id`, `model_id`, `model_version`, `content_sha256`), `ix_photo_vector_owner` |
| `analysis_grouping_rule` | `id` = 규칙 manifest SHA-256 (`^[0-9a-f]{64}$`), `content` JSONB. 불변 |
| `analysis_call_manifest` | `id` = 내용 SHA-256 hex, `content` JSONB (5-1 = `{model, uploadPolicy}`), `content_type`·`max_bytes`·`max_long_edge_px` (> 0). 불변. 5-2가 내용·열 확장 (새 ID) |
| `analysis_request` | `origin` (`CLIENT`/`SERVER_BATCH`, `CLIENT` ⇒ `device_id`), `trigger` (`NEW_PHOTOS`/`ORGANIZE`/`PRO_BACKLOG`), `trial_mark_id`, `consent_version ≥ 1`, `grouping_rule_id` FK, `photo_count ≥ 1`, `representative_count ≥ 0` (만든 작업 수), `first_result_at`, `version`. 상태 열 없음 (계산) |
| `analysis_group` | `request_id` FK, `representative_photo_id` (photo.id), `photo_count ≥ 1`, `model_id`·`model_version` (단독 = NULL). 묶음 판단을 거친 후보마다 1개 (거절 묶음 포함), 불변 |
| `analysis_job` | `request_id`·`group_id` (UNIQUE)·`manifest_id` FK, `photo_id` (대표 photo.id)·`photo_version`, `funding` (`TRIAL`/`PRO`), `status` CHECK = 2.1 전체 상태, `base_priority` 0~2, `queued_at`·`upload_deadline`·`processing_deadline`, `version`. 실행 열·READY 인덱스는 5-2 (V6) |
| `analysis_request_item` | 1.5. CHECK: `ACCEPTED` ⇒ 작업·묶음·역할 / `IN_PROGRESS` ⇒ 작업 ∧ 묶음 없음 / `NOT_ACCEPTED` ⇒ `code`·`reason` / 역할 ⇔ 묶음 / `vector_id` ⇒ 사유 없음. 인덱스 (`owner_id`, `photo_id`)·(`analysis_job_id`)·(`group_id`) |

- 잠금 순서 예외 (V5 주석, `consistency-rules.md`): 접수·묶음·작업·항목 삽입은 예약 뒤. `uk_analysis_job_photo_active` 충돌은 주체 접수 잠금 아래에서만 생김 (불변 조건: `analysis_job`을 삽입하는 모든 경로는 주체 접수 잠금을 먼저 잡음, 5-2 `SERVER_BATCH` 포함). `IN_PROGRESS` 항목이 참조할 기존 작업은 photo 뒤·예약 앞에 id 순 `FOR SHARE`로 먼저 잠금 (1.2 5c'). `AlreadyReserved` 작업만 늦은 `KEY SHARE` (0.3 `analysis_job` 잠금 규칙). 그 밖의 FK 부모 = 같은 TX의 새 행, 불변 행 (manifest·규칙), 이미 `FOR UPDATE`로 잠근 `photo`
- 보관:
  - `SUCCEEDED` 작업과 그 접수 = 그 작업을 가리키는 결과가 있는 동안 유지 (ADR-0024 #6-3·#4-2)
  - `FAILED`·`CANCELLED`·`INELIGIBLE` 작업 = 종료 + 90일. 항목·시도 = 접수의 모든 작업 종료·미정산 없음 + 90일. 남은 작업이 없는 접수도 함께
  - `external_call` 상세 = 90일 (주체·사진 ID로 다른 데이터와 연결되므로 개인 관련 데이터로 취급)
  - `photo_ai_budget`·`analysis_result` = 사진 메타데이터 수명 (P11 삭제 때 함께). 멱등 7일. 임시 이미지 = 2.2
  - 후속 (보관 단계): `IN_PROGRESS` 항목이 다른 접수의 작업을 FK로 참조 (`fk_analysis_request_item_job`) → 두 접수의 삭제 기한이 묶임. 참조 항목을 먼저 삭제하거나 작업 기한을 마지막 참조 접수 기준으로 둠. 어떤 항목도 참조하지 않는 `photo_vector` 행 정리도 이때 (R19 O2)

### 4.4 PR·활성화 경계
- PR 5-1 (photo + 접수), 5-2 (업로드·실행·정산), 5-3 (생성·API)
- 접수 활성화 설정 `pik.analysis.intake-enabled` (기본 false). Unit A 세 PR이 모두 병합되고 프로필별 리스너 시험이 통과한 뒤 켠다
- 꺼짐 응답 = 503 `TEMPORARY_FAILURE`/`INTAKE_DISABLED`, `retryable=true`, 멱등 기록 저장 안 함. 실행기 사전 조회 뒤에 검사하므로 기존 기록 재생은 허용 (사용자 결정 Q7)
- 리스너는 모든 프로필에 등록. 리스너가 없는 상태로 발행하는 경로가 없도록 5-1·5-2의 이벤트 발행 코드는 5-3과 같은 배포에서만 활성 (설정 false면 접수가 생기지 않으므로 발행도 없음)

## 5. 성능 계약 (측정 전 기본값)
- 완료 TX 전에 검증·변환을 끝내고, TX 안에서는 자격·세대 재검사와 쓰기만 한다 (`ai_spend_day` 보유 시간 단축). 생성 행 쓰기는 완료 TX에서 빠짐 (리스너로 이동)
- 접수 삽입은 JDBC batch. 잠금 대기·보유 시간 지표 (`ai_spend_day`, 전역 claim, `owner_change_counter`)
- 분산 예산·Redis·정교한 스케줄러는 측정에서 병목이 확인된 뒤
- 참고 추정 (2026-11-01, 가정): 하루 1,000명 × 100장 단독 = Claude $58~81/일, DB 601k~701k TX/일 (1시간 집중 167~195 TX/s). 비용 행 보유 10ms면 시간당 10만 호출의 55.6% 점유
