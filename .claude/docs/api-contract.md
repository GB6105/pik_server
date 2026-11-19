# API 계약

근거: 요구사항 `## 공통 오류와 결과`. REST 경로·DTO는 별도 설계.

## 호출 구분
| 구분 | 규칙 |
|---|---|
| Query | 상태 변경 금지. 비용 발생 조회(웹 탐색 등)는 해당 FR에서 별도 검증 |
| Command | 사용자 상태·외부 작업 변경. 작업 식별자 + 버전 필수 |
| Internal Event | 저장된 변화에 반응하는 내부 처리 |
| Batch | 누락·기한·잔여 대상 반복 점검 |

## 오류 코드
| 코드 | 의미 | HTTP |
|---|---|---|
| INVALID_INPUT | 필수 입력 누락·잘못된 값 | 400 |
| UNAUTHENTICATED | 로그인 전용 호출의 세션 부재·만료 (`LOGIN_REQUIRED`), access JWT 부재·만료, 설치 토큰 폐기 (ADR-0023) | 401 |
| FORBIDDEN | 기능 권한·동의·체험·Pro 이용 자격 부재, 기기 증명 실패. `reason`으로 구분 (`PERMISSION_REQUIRED`는 단말 OS 권한 결과, 서버 미사용) | 403 |
| NOT_FOUND | 소유 범위 내 대상 부재. 다른 주체의 데이터도 NOT_FOUND (존재 노출 금지) | 404 |
| CONFLICT | 버전 충돌·반영 중·허용 안 되는 상태 전이 | 409 |
| UNSUPPORTED_OPERATION | 지원하지 않는 동작 | 422 |
| TEMPORARY_FAILURE | 통신·외부 처리·일시 저장 장애 | 503 |
| RATE_LIMITED | 호출 제한 초과 (검색 해석, 단말 장소 조회). `Retry-After` 헤더 포함, `retryable` = true, 멱등 기록에 저장 안 함. **SSOT 오류 표에 없음** (ADR-0024 #13-2) | 429 |

## 공통 응답 필드 (요구사항 172행, ADR-0023)
| 요구사항 | 필드 | 범위 |
|---|---|---|
| 작업 ID | `jobId` = 장기 작업은 서버 작업 ID, 즉시 처리 변경은 요청의 `Idempotency-Key` | 변경 요청 (Command) 응답 |
| 결과 상태 | `result` = `SUCCEEDED` / `FAILED` / `CANCELLED` / `NEEDS_CHECK` / `ACCEPTED` (장기 작업 접수) | 변경 요청 응답, `items[]` |
| 대상별 사유 | `items[].code`·`items[].reason` | 대상이 여러 개인 변경 요청 |
| 재시도 가능 여부 | `retryable` | 변경 요청 응답, 오류 응답, `items[]` |
- 변경 요청의 오류 응답도 `jobId`·`result` = `FAILED` 포함. `jobId` 값: 즉시 처리 변경 = 유효한 키 (없거나 형식 오류면 null), 장기 작업 접수 (`@IdempotentCommand(longRunning = true)`) = 항상 null (작업을 만들지 못함. 필터·인터셉터·실행기 저장 오류 모두, 사용자 결정 Q13, ADR-0034)
- 조회 (Query) 응답에는 강제하지 않음

## 형식 (ADR-0014, ADR-0023)
- 오류 응답 = RFC 9457 `ProblemDetail` + `code` (위 표, HTTP와 1:1) + `reason` (세부 사유, 기능별 추가) + `retryable`. 장기 작업 응답은 `jobId` 포함
- 목록 작업 = 200 + `items[]` (대상별 `result`·`code`·`reason`·`retryable`, 버전 충돌이면 `currentVersion`). `items[].code`는 같은 분류를 HTTP 없이 사용
- 요청 자체가 잘못되면 4xx. 대상별로 처리했으면 모두 실패해도 200 + `items[]`
- 프레임워크 오류 (ADR-0025 #10): HTTP 상태 보존 (401·403·404·409·422·429 그대로), 405 = 422 `UNSUPPORTED_OPERATION` / `METHOD_NOT_ALLOWED` (`Allow` 유지), 그 밖의 4xx = 400 `INVALID_INPUT` / `BAD_REQUEST`, 500·예상하지 못한 예외 = 503 `TEMPORARY_FAILURE` / `INTERNAL_ERROR`, 그 밖의 5xx = 503 / `SERVER_UNAVAILABLE` (5xx는 `retryable` = true)
- 프레임워크 세부 사유: `API_VERSION_REQUIRED`·`API_VERSION_UNSUPPORTED`·`VALIDATION_FAILED`·`MALFORMED_BODY`·`MEDIA_TYPE_NOT_SUPPORTED`·`RESOURCE_NOT_FOUND`. 필터 예외·`sendError`도 같은 형식 (`/error` 컨트롤러), `/error` 직접 요청 = 404 (토큰이 없으면 401, ADR-0027)
- 변경 요청 = 컨트롤러 메서드 `@IdempotentCommand` + `IdempotencyKey` 파라미터 + `IdempotentExecutor` (ADR-0026). 키 = 소문자 정규 형식 UUIDv7, 아니면 400 `INVALID_INPUT` / `IDEMPOTENCY_KEY_INVALID` (빈 헤더 = `IDEMPOTENCY_KEY_REQUIRED`). 키 검사는 본문 검증보다 먼저. 오류 응답 `jobId` = 위 공통 응답 필드 규칙 (즉시 처리 = 유효한 키, 장기 작업 = null). `/error`로 렌더링하는 오류와 보안 필터 단계 거부 (401·403, 처리기가 핸들러를 찾아 판별, ADR-0027)도 포함
- 예외 메시지에 요청 데이터 금지. `PikException` = `code`·`reason`·`retryable` (+ `Retry-After`)만. `RATE_LIMITED`는 `retryAfter` 필수 (초 단위 올림), 429는 `PikException`으로만. 로그 `stack_trace` = 예외 클래스 이름만 (ADR-0025 #11)
- API 버전 = 요청 헤더 `API-Version`, 첫 값 `1`. 누락 시 400. 폐기 예정 = `Deprecation`·`Sunset` 응답 헤더. actuator 제외
- 데이터 버전 = 변경 요청 본문의 대상별 `version`. 응답도 대상별 현재 `version`. `If-Match` 미사용
- 작업 식별자 = `Idempotency-Key` 헤더 (UUIDv7). 세부 규칙은 `consistency-rules.md` 멱등성
- 장기 작업 접수 = 202 + 작업 ID + 진행 상태
- 실행기 단계 API (ADR-0034): `lookup` (사전 조회) → TX 밖 검사 실패 = `reject` (키 잠금 → 재확인 → 저장 대상만 저장) → `commit` (`Outcome` = 상태 코드 + 본문, 202 저장). 2xx 재생만 `ReplayProjection`으로 다시 투영. 저장 대상 4xx도 `PikException.unstored`면 저장 안 함 (플래그는 `reject`·`commit` 안에서만 의미, 그 전 단계 오류는 실행기를 거치지 않아 저장 안 됨). `PikException` 속성 (`properties`)은 ProblemDetail 필드로 출력

## 인증 (ADR-0023)
- access JWT = ES256, 15분. 클레임 `iss`·`sub` (주체 ID)·`kind` (ACCOUNT / ANONYMOUS)·`did` (기기 ID, ADR-0029)·`iat`·`exp`, 헤더 `kid`. 개인정보 금지
- 계정 refresh = 90일, 사용할 때마다 교체 (마지막 사용부터 90일), 재사용 감지 시 family 폐기. 교체 직후 유예 30초 (설정값) 안의 재사용은 응답 유실로 보고 폐기 안 함 → 새 토큰 쌍 재발급, 앞선 후속 토큰 폐기 (판단 기준 `rotated_at`)
- 익명 설치 토큰 = 발급 시 기기 증명, 만료·교체 없음, 해시 저장·서버 폐기 가능. 설치 토큰 → access JWT 교환
- 수명 값은 모두 설정값. 서명 키는 현재 + 이전 키 검증 (키 교체)

### 익명 인증 엔드포인트 (ADR-0025 #7, ADR-0027)
| 엔드포인트 | 요청 | 응답 |
|---|---|---|
| `POST /installs` | `{platform: IOS / ANDROID, appVersion?, attestation: {token, keyId?}, trialDevice: {androidId? / deviceCheckToken?}}` | 201 `{installId, installToken}` |
| `POST /auth/token` | `{grantType: "install_token", installToken}` | 200 `{accessToken, tokenType: "Bearer", expiresIn}` (초) |
- 둘 다 access token 불필요 (`Authorization` 헤더가 있어도 무시), 멱등 키 미사용 (Command 공통 필드 없음). 재전송 = 새 주체 (ADR-0025 #2)
- 기기 증명 종류 = `platform`에서 도출. 실패 = 403 `FORBIDDEN` / `ATTESTATION_FAILED`
- iOS 첫 체험 접수 assertion (ADR-0032 #6-2): 증거 없음·challenge 만료·nonce 오류 = 403 `FORBIDDEN` / `ATTESTATION_REQUIRED` + 새 challenge, counter 경합 = 409 `CONFLICT` + `Retry-After`, 서명·키 불일치 = 403 `ATTESTATION_FAILED`. 모두 멱등 기록에 저장하지 않음. 헤더 `App-Attest-Assertion`·`App-Attest-Challenge-Id`·`App-Attest-Nonce`는 멱등 해시 제외
- `trialDevice` = 재설치 체험 판별 값, 플랫폼 값 하나만 (Android `androidId` = 16진수 1~16자리, 대소문자·앞뒤 공백 무시, iOS `deviceCheckToken`). 없음·빈 값·형식 오류·다른 플랫폼 값 = 400 `TRIAL_DEVICE_INVALID`, 객체 누락 = 400 `VALIDATION_FAILED`. DeviceCheck가 토큰 거부 = 403 `DEVICE_CHECK_FAILED`. 전송 장애·Apple 5xx = 설치 진행 (201) + 기기 "확인 전" (D-23, ADR-0032 #7). 503 `DEVICE_CHECK_UNAVAILABLE` (재시도 가능, 멱등 기록에 저장 안 함)은 첫 체험 시작의 DeviceCheck 일시 장애에 쓴다: NEW·확인 전 기기의 조회·기록 실패, `STARTING` 기록의 조회 없는 재기록 실패 (ADR-0029 #18). 토큰 거절 403은 그대로. 검사 순서 = 형식 → 기기 증명 → DeviceCheck (ADR-0029)
- 오류: 모르는 `grantType` = 400 `UNSUPPORTED_GRANT_TYPE`, `installToken` 누락 = 400 `VALIDATION_FAILED`, 없는·폐기된 토큰과 비활성 주체 = 401 `INSTALL_TOKEN_INVALID` (구분 안 함)

### 접근 제어 (ADR-0027)
- 기본 거부. 공개 = `PublicEndpoints` 구현 (모듈별), 오류 디스패치, actuator health (공개 경로는 `Authorization` 무시). health 밖의 actuator = 토큰이 있어도 403 (없으면 401). 나머지 (없는 경로 포함) = `Authorization: Bearer <access JWT>`
- 401 사유: 토큰 없음 = `ACCESS_TOKEN_REQUIRED` (`WWW-Authenticate: Bearer`), 잘못됨·만료 = `ACCESS_TOKEN_INVALID` (`WWW-Authenticate: Bearer error="invalid_token"`). 컨트롤러의 401도 `WWW-Authenticate: Bearer`. 권한 부족 = 403 `ACCESS_DENIED`
- JWT 검증: ES256만, `kid` 필수, `typ` = `JWT`, `iss`·`exp`·`sub` (UUID)·`kind`·`did` (UUID) 확인, 만료 허용 오차 60초 (설정값)
- 요청 주체 = 컨트롤러 파라미터 `CurrentPrincipal` (`id`·`kind`·`deviceId`). 요청 본문·경로의 ID로 소유권 판단 금지

### 외부 전송 동의 (FR-O-02, ADR-0028)
| 엔드포인트 | 요청 | 응답 |
|---|---|---|
| `GET /consents/ai` | - | 200 `{consent, notice}` |
| `PUT /consents/ai` (변경 요청, `Idempotency-Key`) | `{decision: GRANTED / DENIED, noticeVersion, version}` | 200 `{jobId, result, retryable, consent}` |
- `consent` = `{purpose, status (NONE / GRANTED / DENIED / WITHDRAWN), version, noticeVersion, decidedAt, reconsentRequired, features}`. `status`·`noticeVersion`·`decidedAt` = 마지막 결정 기준 (처음 동의 시각은 이력에만). `notice` = 현재 안내 `{version, title, transfers[{data, recipient, purpose, country, retention}], notes[]}`
- `version` = 주체·목적별 동의 순번 (기록 없음 = 0). 받아들인 결정마다 1 증가 (같은 결정 반복 포함). 다르면 409 `VERSION_CONFLICT`
- 동의 = 현재 안내 버전만 (아니면 409 `NOTICE_OUTDATED`). 거절 = 서버가 제공한 버전이면 기록. 제공한 적 없는 버전 = 409 `UNKNOWN_NOTICE_VERSION`
- `VERSION_CONFLICT`·`NOTICE_OUTDATED`·`UNKNOWN_NOTICE_VERSION` = 조회해 현재 상태·안내를 보여 줌. 동의는 사용자가 현재 상태·안내를 보고 다시 고른 경우에만 새 키로 전송 (자동 재전송 금지). 거절은 전송 범위를 넓히지 않으므로 자동 재전송 허용 (새 키·조회한 현재 순번). `IDEMPOTENCY_IN_PROGRESS` = 같은 키로 재시도
- 직전이 동의인 거절 = `WITHDRAWN`, 그 밖의 거절 = `DENIED`
- `features` = 동의 기준 (기본 4개 + 유효한 동의 시 `PHOTO_ANALYSIS`·`NATURAL_LANGUAGE_SEARCH`). 체험·구독 자격은 별도. 분석 가능 여부는 `status`가 아니라 `features`로 판단
- `reconsentRequired` = 최소 안내 버전 (설정값) 미만에서 받은 동의 → 현재 안내로 다시 묻기

### 무료 체험 (FR-B-01, ADR-0029)
- HTTP API 없음. 분석 접수 (단계 5)가 `TrialUsage`를 호출하고 접수 응답에 반영
- 한도·잔여량 응답 금지. 체험이 묶음 전체를 덮지 못하면 분석하지 않고 구독 안내 필요로 응답
- iOS 분석 접수 요청 = 새 DeviceCheck 토큰을 헤더 `Device-Check-Token`으로 포함 (첫 체험 시작 기록용, 본문·멱등 해시에 넣지 않음, ADR-0030). 필요한데 없으면 400 `DEVICE_CHECK_TOKEN_REQUIRED`, DeviceCheck 오류는 설치와 같은 응답. 503 `DEVICE_CHECK_UNAVAILABLE` 뒤 재시도도 새 토큰. 같은 `Idempotency-Key`로 토큰만 바꿔 재전송 = 정상 (기존 접수 상태 반환)

### 분석 접수 `POST /analysis-jobs` (FR-I-01·02, ADR-0031·0034)
- 장기 작업 변경 요청. 계약 세부 (본문 규칙·응답 필드·처리 순서) = `docs/design/stage5-analysis-pipeline.md` 1.1·1.2·1.5
- 헤더: `Authorization`, `API-Version: 1`, `Idempotency-Key`, `Content-Type: application/json`, iOS `Device-Check-Token` (매 접수), 첫 체험 증거 `App-Attest-Assertion`·`App-Attest-Challenge-Id`·`App-Attest-Nonce` (각 4,096자 이하, 초과 = `ATTESTATION_FAILED`). 토큰·증거 헤더 = 멱등 해시 제외
- 본문: `{trigger, photos[{photoId, contentVersion, takenAt?, vector?{model, modelVersion, data}, text?{status, ocrVersion?}}]}`. `trigger` = `NEW_PHOTOS`/`ORGANIZE`

| # | 검사 (앞에서 실패하면 멈춤) | 실패 | 멱등 기록 |
|---|---|---|---|
| 1~3 | 인증 (필터)·멱등 키 (인터셉터)·`API-Version` | 공통 규칙 (401, 400 `IDEMPOTENCY_KEY_*`, 400 `API_VERSION_*`) | 안 함 |
| 4 | `Content-Encoding`이 `identity` 외 (모든 헤더·코딩) | 400 `INVALID_INPUT` / `MEDIA_TYPE_NOT_SUPPORTED` | 안 함 |
| 4b | `Content-Type`이 `application/json`·`application/*+json` 아님·없음 | 400 `MEDIA_TYPE_NOT_SUPPORTED` | 안 함 |
| 5 | 본문 > 2 MiB (2,097,152바이트, 파싱 전, 설정값) | 400 `REQUEST_TOO_LARGE` | 안 함 |
| 6 | JSON 파싱·enum 값 | 400 `MALFORMED_BODY` (본문 읽기 입출력 오류 = `retryable=true`) | 안 함 |
| 7 | 요청 기기 = 주체의 기기 (`did`) | 401 `UNAUTHENTICATED` / `ACCESS_TOKEN_INVALID` | 안 함 |
| 8 | 실행기 사전 조회 | 기록 있음 = 재생, 본문 다름 = 400 `IDEMPOTENCY_KEY_REUSED` | 원본 유지 |
| 9 | `pik.analysis.intake-enabled=false` | 503 `TEMPORARY_FAILURE` / `INTAKE_DISABLED` (`retryable=true`) | 안 함 |
| 10 | 길이·문자 규칙 (사진 1~100, `contentVersion`, model·version·`ocrVersion`, `data`) | 400 `VALIDATION_FAILED` | 안 함 |
| 11 | 같은 `photoId` | 400 `DUPLICATE_PHOTO` | 안 함 |
| 12 | `takenAt` RFC 3339 (오프셋 필수) | 400 `TAKEN_AT_INVALID` | 안 함 |
| 13 | 동의 빠른 확인 (TX 밖) | 403 `FORBIDDEN` / `AI_CONSENT_REQUIRED` | 저장 |
| 14 | 체험 시작 필요 ∧ 토큰 없음 | 400 `DEVICE_CHECK_TOKEN_REQUIRED` | 저장 |
| 15 | 체험 시작 필요 ∧ 증거 없음·challenge 만료·nonce 오류 | 403 `ATTESTATION_REQUIRED` + `challenge` (`retryable=true`) | 안 함 |
| 15 | assertion counter 경합 | 409 `CONFLICT` / `ATTESTATION_CONFLICT` + `Retry-After` | 안 함 |
| 15 | 서명·키 불일치, 형식 오류, 키 없음 | 403 `ATTESTATION_FAILED` | 안 함 |
| 16 | DeviceCheck 거절 / 장애 | 403 `DEVICE_CHECK_FAILED` / 503 `DEVICE_CHECK_UNAVAILABLE` | 저장 / 안 함 |
| 17 | 업무 TX 동의 재확인 | 403 `AI_CONSENT_REQUIRED` | 저장 (TX 전체 롤백 뒤) |
| 18 | `StartRequired` 재시도 뒤 또 `StartRequired` | 503 `TEMPORARY_FAILURE` / `TRIAL_START_CONFLICT` (`retryable=true`) | 안 함 |
- 14가 15보다 앞: assertion 서명 입력에 `SHA-256(Device-Check-Token)`이 들어감 (B6-2.E5)
- 13~16 = 실행기 `reject`: 키 잠금 아래 그 사이 저장된 기록이 있으면 그 기록 재생
- 모든 오류 응답: `jobId = null`, `result = FAILED`. 비저장 오류 = 같은 키·같은 본문으로 보완해 재전송 가능 (저장된 오류에 막히지 않음)
- `ATTESTATION_REQUIRED` 본문 추가 필드 `challenge {challengeId, nonce, expiresAt, keyId?}`. 다음 요청 = 같은 키·같은 본문 + 증거 헤더 3개 (서명 대상 = 키·본문 원본 바이트 SHA-256·토큰 SHA-256)
- 성공: 202 = 새 작업 ≥ 1, 200 = 그 밖 (접수 행은 항상 생성). `{jobId (= 접수 ID), result: ACCEPTED, retryable: false, phase, counts, items[], groups[], jobs[]}`. 업로드 URL 없음
  - `jobs[]` = 이 접수가 만든 작업 + `IN_PROGRESS` 항목이 참조한 작업 (중복 제거, `counts`·`phase`와 같은 범위). 각각 `requestId` (업로드 경로 기준)·`representativePhotoId` (업로드할 사진)·`uploadRequired`·`uploadPolicy`
  - 정렬 = `photoId` 부호 없는 순 (`items[]` = 사진, `groups[]`·`jobs[]` = 대표 사진, 작업은 이어서 작업 ID). 단말은 순서가 아니라 `photoId`로 대응
- 재전송 (같은 키·같은 본문) = 최초 상태 코드 + 저장 판정 + 현재 작업 상태. 증거·토큰·DeviceCheck 호출 없음

| `admissionResult` | `code` / `reason` | `retryable` |
|---|---|---|
| `ACCEPTED` | 없음 (`analysisJobId` = 새 작업) | false |
| `IN_PROGRESS` | 없음 (`analysisJobId` = 진행 작업, 다른 접수의 작업일 수 있음) | false |
| `NOT_ACCEPTED` | `FORBIDDEN` / `SUBSCRIPTION_REQUIRED` (체험이 묶음 전체를 덮지 못함) | 예약 반환 대기면 true, 소진·체험 없음이면 false |
| `NOT_ACCEPTED` | `FORBIDDEN` / `TRIAL_UNAVAILABLE` (체험 기록 없는 기기, 사용자 결정 Q10) | false |
- `EXISTING_RESULT`, `INPUT_RETRY_LATER`·`ATTEMPTS_EXHAUSTED`·`ANALYSIS_UNAVAILABLE` 판정과 `retryAfter` = 5-2 (사용자 결정 Q11)

## 정상 결과로 취급
- 빈 검색 결과
- 사용자 취소
- 장기 작업 접수 (완료 아님)
