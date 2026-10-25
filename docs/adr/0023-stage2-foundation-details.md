# ADR-0023: 단계 2 공통 기반 세부 결정 (API·오류·멱등·인증·로그)

- 상태: 채택
- 날짜: 2026-10-26
- 관련: 요구사항 `## 트랜잭션과 재시도`·`## 공통 오류와 결과`, FR-A-01·02·03, FR-B-01, FR-L-05, ADR-0014, ADR-0020, ADR-0022, 브랜치 `feature/stage2-decisions`

## 맥락
- ADR-0014는 형식 (`ProblemDetail` + `code`, 헤더 API 버전, `Idempotency-Key`, ES256 JWT + refresh 회전, 필드 허용 목록 로그)만 정하고 값과 세부 규칙은 구현 시 확정으로 남겼다. 요구사항도 작업 식별자 형식과 보관 기간을 구현 설계에 맡겼다 (152행).
- 단계 2 (공통 기반 + 익명 주체 발급)를 시작하기 전에 이 값을 정한다.

## 결정
### 사용자 결정
| # | 항목 | 결정 |
|---|---|---|
| 1 | 데이터 버전 위치 | 변경 요청 본문의 대상마다 `version`을 담는다. 응답도 대상별 현재 `version`을 돌려준다. `If-Match` 헤더는 쓰지 않는다 |
| 2 | 오류 응답 구조 | RFC 9457 `ProblemDetail`에 `code` (SSOT 분류, HTTP 상태와 1:1), `reason` (세부 사유, 기능별로 추가), `retryable`을 더한다. 장기 작업 응답에는 `jobId`를 담는다. 목록 작업은 200 + `items[]`로 대상별 `result`·`code`·`reason`·`retryable`을 돌려주고, 버전 충돌이면 `currentVersion`도 담는다 |
| 3 | 계정 토큰 수명 | access JWT 15분, refresh 토큰 90일. refresh는 쓸 때마다 교체하고 새 토큰이 다시 90일을 가진다 (마지막 사용부터 90일). 재사용이 감지되면 그 family 전체를 폐기한다. 값은 설정값이다 |
| 4 | 익명 토큰 | 설치 토큰은 발급할 때 기기 증명을 거치고, 만료·교체가 없다. 단말은 Keychain·Keystore에 저장하고 설치 토큰으로 access JWT (계정과 같은 수명)를 받는다. 서버는 설치 토큰을 해시로 저장하고 폐기할 수 있다 |

### 관례적 기본값 (Claude 제안)
| # | 항목 | 결정 |
|---|---|---|
| 5 | 오류 코드 해석 | 서버는 `FORBIDDEN`만 쓴다. `PERMISSION_REQUIRED`는 단말의 OS 권한 결과다 (SSOT "403 또는 단말의 권한 결과"). 다른 주체의 데이터를 요청하면 `NOT_FOUND`로 응답해 존재를 드러내지 않는다 |
| 6 | API 버전 | 요청 헤더 `API-Version`, 첫 값 `1`. 헤더가 없으면 400 (`INVALID_INPUT`). 폐기 예정 버전은 `Deprecation`·`Sunset` 응답 헤더로 알린다. 운영 엔드포인트 (actuator)는 버전 대상이 아니다 |
| 7 | 멱등 기록 | `Idempotency-Key` = UUIDv7, 범위 = (주체, 키), 보관 7일 (설정값). 완료한 2xx·4xx 응답은 저장해 같은 응답을 돌려준다. 503과 처리 중 예외는 저장하지 않아 재시도 때 다시 실행한다. 처리 중 재전송 = 409 `CONFLICT` / `IDEMPOTENCY_IN_PROGRESS` (재시도 가능). 같은 키에 다른 본문 = 400 `INVALID_INPUT` / `IDEMPOTENCY_KEY_REUSED`. 변경 요청에 키가 없으면 400 `INVALID_INPUT` / `IDEMPOTENCY_KEY_REQUIRED` |
| 8 | JWT | ES256. 클레임 = `iss`, `sub` (주체 ID), `kind` (ACCOUNT / ANONYMOUS), `iat`, `exp`. 헤더 `kid`. 개인정보는 넣지 않는다. 현재 키와 이전 키를 함께 검증해 키를 교체할 수 있게 한다 |
| 9 | 로그 | 필드 허용 목록: trace ID, 주체 ID, 동작, 대상 ID, 상태, 사유 코드, HTTP 상태, 소요 시간. 토큰·`Authorization`·푸시 토큰·이메일·IP와 `data-retention.md`의 금지 항목은 기록하지 않는다 |
| 10 | 기기 증명 | 단계 2는 검증 인터페이스와 개발·테스트용 대역만 만든다. 운영 프로필에서 대역이 켜져 있으면 기동을 실패시킨다. 실제 App Attest·Play Integrity 검증은 키를 준비한 뒤 클라이언트 연동 전에 구현한다 |
| 11 | 모듈 골격 | 단계 2는 `shared` (오류·멱등·로그·보안)와 `account` (주체·설치·기기·토큰)만 만든다. 나머지 모듈은 해당 단계에서 추가한다 |

## 근거
- 1: 동기화 (FR-L-05)·목록 변경·게스트 귀속 (FR-A-02)은 대상 여러 개에 각자 버전이 필요하다. 헤더로는 대상 하나만 표현할 수 있다. 앱 전용 API라 HTTP 캐시 표준 (ETag)을 쓰지 못하는 영향은 작다.
- 2: 요구사항 172행 ("공통 응답에는 작업 ID, 결과 상태, 대상별 사유, 재시도 가능 여부")을 담는다. `code`를 SSOT 분류로 고정해 HTTP 매핑을 안정시키고, 기능이 늘면 `reason`만 추가한다.
- 3: 회전과 재사용 탐지가 긴 refresh 기간의 유출 위험을 줄인다. access가 짧아 로그아웃 뒤에 남는 유효 시간이 짧다.
- 4: 익명 주체는 다시 로그인할 수단이 없어, 회전 중 응답이 유실돼 토큰을 잃으면 주체를 복구할 수 없다. 설치 토큰이 유출돼도 피해는 그 설치의 체험 이용량이고, 서버에서 폐기할 수 있다.
- 7: 오프라인 단말은 며칠 뒤에 재전송할 수 있다. 보관 기간이 지난 뒤에도 데이터 버전과 `client_ref` 유니크 제약이 중복 반영을 막는다 (ADR-0022). 503을 저장하지 않아야 일시 장애 뒤의 재시도가 다시 실행된다.

| 대안 | 버린 이유 |
|---|---|
| `If-Match` 헤더 | 대상 하나만 표현 |
| 세부 사유까지 `code`에 담기 | `code` 목록과 HTTP 매핑이 기능마다 늘어남 |
| access 1시간 / refresh 30일, access 15분 / refresh 30일 | 사용자 결정 (재로그인 빈도와 유효 시간의 균형) |
| 익명 주체도 refresh 회전 | 토큰 유실 시 익명 주체를 복구할 수 없고, ERD `refresh_token`을 주체 단위로 바꿔야 함 |
| 멱등 보관 24시간 | 오프라인 재전송 기간을 덮지 못함 |

## 결과
- `api-contract.md`·`consistency-rules.md`·`data-retention.md`에 세부 규칙을 반영했다.
- ERD: `anonymous_install.revoked_at`을 추가했다 (설치 토큰 폐기).
- 새 비밀 값: JWT 서명 키 (ES256, `kid`별), 식별자 HMAC 키. 단계 2를 구현할 때 `.env.example`에 키를 추가하고, 로컬은 개발용 키를 `.env.local`에 둔다.
- 구현할 때 Spring Framework 7 API 버전 설정과 Spring Security Resource Server의 키 설정 이름을 공식 문서로 확인한다.
- 계속 미정: 검색 해석 호출 제한의 응답 형식 (429 사용 여부, D-17과 함께), `reason` 값 목록 (기능별로 추가).
- 출처: 1~4는 사용자가 정했다 (2026-10-26). 5~11은 Claude가 관례적 기본값으로 시작 보고에 적었고, 사용자는 이견을 내지 않았다.
