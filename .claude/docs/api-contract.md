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

## 공통 응답 필드 (요구사항 172행, ADR-0023)
| 요구사항 | 필드 | 범위 |
|---|---|---|
| 작업 ID | `jobId` = 장기 작업은 서버 작업 ID, 즉시 처리 변경은 요청의 `Idempotency-Key` | 변경 요청 (Command) 응답 |
| 결과 상태 | `result` = `SUCCEEDED` / `FAILED` / `CANCELLED` / `NEEDS_CHECK` / `ACCEPTED` (장기 작업 접수) | 변경 요청 응답, `items[]` |
| 대상별 사유 | `items[].code`·`items[].reason` | 대상이 여러 개인 변경 요청 |
| 재시도 가능 여부 | `retryable` | 변경 요청 응답, 오류 응답, `items[]` |
- 조회 (Query) 응답에는 강제하지 않음

## 형식 (ADR-0014, ADR-0023)
- 오류 응답 = RFC 9457 `ProblemDetail` + `code` (위 표, HTTP와 1:1) + `reason` (세부 사유, 기능별 추가) + `retryable`. 장기 작업 응답은 `jobId` 포함
- 목록 작업 = 200 + `items[]` (대상별 `result`·`code`·`reason`·`retryable`, 버전 충돌이면 `currentVersion`). `items[].code`는 같은 분류를 HTTP 없이 사용
- 요청 자체가 잘못되면 4xx. 대상별로 처리했으면 모두 실패해도 200 + `items[]`
- API 버전 = 요청 헤더 `API-Version`, 첫 값 `1`. 누락 시 400. 폐기 예정 = `Deprecation`·`Sunset` 응답 헤더. actuator 제외
- 데이터 버전 = 변경 요청 본문의 대상별 `version`. 응답도 대상별 현재 `version`. `If-Match` 미사용
- 작업 식별자 = `Idempotency-Key` 헤더 (UUIDv7). 세부 규칙은 `consistency-rules.md` 멱등성
- 장기 작업 접수 = 202 + 작업 ID + 진행 상태

## 인증 (ADR-0023)
- access JWT = ES256, 15분. 클레임 `iss`·`sub` (주체 ID)·`kind` (ACCOUNT / ANONYMOUS)·`iat`·`exp`, 헤더 `kid`. 개인정보 금지
- 계정 refresh = 90일, 사용할 때마다 교체 (마지막 사용부터 90일), 재사용 감지 시 family 폐기. 교체 직후 유예 30초 (설정값) 안의 재사용은 응답 유실로 보고 폐기 안 함
- 익명 설치 토큰 = 발급 시 기기 증명, 만료·교체 없음, 해시 저장·서버 폐기 가능. 설치 토큰 → access JWT 교환
- 수명 값은 모두 설정값. 서명 키는 현재 + 이전 키 검증 (키 교체)

## 정상 결과로 취급
- 빈 검색 결과
- 사용자 취소
- 장기 작업 접수 (완료 아님)
