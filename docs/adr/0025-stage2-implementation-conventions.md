# ADR-0025: 단계 2 구현 규약 (하위 작업 분할·오류 대응·로그·토큰 발급)

- 상태: 채택
- 날짜: 2026-10-28
- 관련: ADR-0014, ADR-0020, ADR-0023, FR-A-02, P08, 브랜치 `feature/stage2-foundation`

## 맥락
- ADR-0023이 단계 2의 값과 규칙을 정했지만, 구현 방식 (하위 작업 단위, 프레임워크 오류의 `code` 대응, 로그 형식, 설치 토큰 재전송, 로컬 서명 키)은 남겨 두었다.
- 요구사항 오류 표에는 500이 없고, 프레임워크가 내는 405·406·415도 대응하는 `code`가 없다. `code`는 HTTP 상태와 1:1이어야 한다 (ADR-0023 #2).

## 결정
### 시작 보고에서 사용자가 확인한 항목 (2026-10-28)
| # | 항목 | 결정 |
|---|---|---|
| 1 | 하위 작업 | 2-1 공통 기반 (모듈 골격·오류·API 버전·로그), 2-2 멱등 기록 (첫 Flyway 마이그레이션 포함), 2-3 익명 인증 (JWT·설치 토큰). 하위 작업마다 브랜치와 PR을 둔다 |
| 2 | 설치 토큰 발급 재전송 | 매번 새 주체를 만든다. 응답 유실로 남은 빈 주체는 P08 비로그인 데이터 보관 기준으로 정리한다 |
| 3 | 로컬·테스트 JWT 서명 키 | 기동할 때 임시 키를 만든다. 운영 프로필은 키가 없으면 기동을 실패시킨다 |
| 4 | 설치 토큰 | 256비트 난수 (base64url), 저장은 SHA-256 해시 |
| 5 | 멱등 적용 | Command 서비스가 명시적으로 호출하는 실행기. 서블릿 필터는 쓰지 않는다 |
| 6 | 로그 형식 | Spring Boot 구조화 로그 `logstash` + `logging.structured.json.include` 허용 목록 |
| 7 | 엔드포인트 | `POST /installs` (설치 토큰 발급), `POST /auth/token` (설치 토큰 → access JWT, 계정 로그인은 grant 종류로 추가) |
| 8 | springdoc-openapi | 단계 2에서 제외하고 클라이언트 연동 전에 추가한다 |

### 구현하면서 정한 관례 (Claude 제안)
| # | 항목 | 결정 |
|---|---|---|
| 9 | 예상하지 못한 예외 | 503 `TEMPORARY_FAILURE` / `INTERNAL_ERROR`, `retryable` = true. 요구사항 오류 표에 500이 없어 표 안에서 대응한다. 원인은 서버 로그 (`stack_trace`)에만 남긴다 |
| 10 | 프레임워크 오류 대응 | HTTP 상태를 보존해 대응한다. 401·403·404·409·422·429는 그대로 두고, 허용하지 않는 메서드 (405) = 422 `UNSUPPORTED_OPERATION` / `METHOD_NOT_ALLOWED` (`Allow` 헤더 유지), 그 밖의 4xx = 400 `INVALID_INPUT` / `BAD_REQUEST`, 500 = 503 `TEMPORARY_FAILURE` / `INTERNAL_ERROR`, 그 밖의 5xx = 503 `TEMPORARY_FAILURE` / `SERVER_UNAVAILABLE` (5xx는 `retryable` = true). 세부 사유: API 버전 누락 = `API_VERSION_REQUIRED`, 지원하지 않는 버전 = `API_VERSION_UNSUPPORTED`, 검증 실패 = `VALIDATION_FAILED`, 본문 해석 실패 = `MALFORMED_BODY`, 미디어 타입 = `MEDIA_TYPE_NOT_SUPPORTED`, 없는 경로 = `RESOURCE_NOT_FOUND`. 상태만 있는 오류의 기본 사유 = 401 `AUTHENTICATION_REQUIRED`, 403 `ACCESS_DENIED`, 409 `STATE_CONFLICT`, 422 `UNPROCESSABLE`, 429 `TOO_MANY_REQUESTS`. DispatcherServlet 밖의 오류 (필터 예외, `sendError`)도 `/error` 컨트롤러가 같은 대응으로 응답한다 |
| 11 | 예외 메시지 | `PikException` 메시지는 `code`/`reason`만 담는다. 요청 데이터를 예외 메시지에 넣지 않는다. 라이브러리 예외 메시지에도 요청 데이터가 들어갈 수 있으므로, 로그의 `stack_trace`에는 예외 클래스 이름만 남긴다 (`MessageFreeStackTracePrinter`). PostgreSQL 오류 세부 (`logServerErrorDetail`)와 Tomcat의 요청 줄 해석 실패 INFO 로그도 끈다 |
| 12 | 요청 로그 | 요청마다 `trace_id`를 MDC에 두고, 끝날 때 `action` (메서드 + 매핑 패턴), `http_status`, `duration_ms`를 한 줄로 남긴다. 원본 URI와 쿼리 문자열은 남기지 않는다 (검색어 포함 가능). 필터 밖으로 나간 예외는 같은 줄을 ERROR (`request failed`, `http_status` = 503, `stack_trace`)로 남긴다. actuator 요청 (`management.endpoints.web.base-path` 아래)은 남기지 않는다. OpenTelemetry를 도입하면 `trace_id`를 추적 ID로 바꾼다 |
| 13 | 모듈 | `shared`는 Modulith OPEN 모듈 (모든 모듈이 사용), `account`는 일반 모듈. 경계는 `ApplicationModules.verify()` 테스트로 검사한다 |

## 근거
- 2: 토큰은 해시로만 저장해 같은 토큰을 돌려줄 수 없다. 설치 ID만으로 기존 주체에 토큰을 다시 주면 그 ID를 아는 쪽이 주체를 가로챌 수 있다.
- 3: `.env.local`을 고치지 않고 로컬·테스트를 실행할 수 있다. 기기 증명 대역과 같은 방식으로 운영에서 빠지는 것을 막는다 (ADR-0023 #10).
- 5: "업무 변경과 같은 트랜잭션에 저장, 4xx면 업무 변경만 롤백" (ADR-0023 #7)을 코드로 드러낸다. 필터는 응답 캡처와 트랜잭션 경계가 어긋난다.
- 6: logstash 형식은 필드가 평평해 허용 목록에 필드 이름만 적으면 된다.
- 9: 500을 새 코드로 만들면 SSOT 오류 표와 다시 어긋난다. 재시도는 멱등 기록이 중복 반영을 막는다.
- 10: `code`와 HTTP 상태의 1:1 대응을 지키려고 405는 422로 바꾼다. 단말은 `code`로만 분기하므로, 401·403·409·429를 400으로 합치면 재인증·재시도를 판단할 수 없다.
- 11: 오류가 난 요청의 데이터는 예외 메시지로 로그에 들어가기 쉽다. 원인 분석은 예외 클래스, 호출 위치, `trace_id`로 한다.

| 대안 | 버린 이유 |
|---|---|
| 단계 2를 PR 하나로 | 검토 범위가 커짐 (사용자 선택) |
| 설치 ID로 기존 주체 재사용 | 주체 탈취 위험 |
| 예상하지 못한 예외 = 500 + 새 코드 | SSOT 오류 표에 없음 |
| 405를 그대로 405로 | `code`와 HTTP 1:1 대응이 깨짐 |
| ECS 로그 형식 | 중첩 필드라 허용 목록에 부모 경로까지 적어야 함 |

## 결과
- 2-1: `shared` (오류·로그)·`account` 모듈 골격, `API-Version` 헤더 (`spring.mvc.apiversion.*`), 가상 스레드, 구조화 로그, `/error` 컨트롤러를 추가했다. Spring Modulith 2.1.2 (Boot 4.1.2 기준)를 BOM으로 가져온다.
- 변경 요청 오류 응답의 `jobId`·`result` (ADR-0023 #12)는 멱등 실행기와 함께 2-2에서 넣는다.
- `api-contract.md`에 프레임워크 오류 대응을 추가했다.
- 2-1 범위의 핸들러는 비동기 요청 (`DeferredResult`·SSE)을 쓰지 않는다. 비동기를 도입하면 요청 로그와 MDC 처리를 다시 확인한다.
- Spring Security 예외 (`AccessDeniedException`·`AuthenticationException`)는 2-3에서 예상하지 못한 예외 처리와 분리하고, EntryPoint·AccessDeniedHandler가 ProblemDetail을 직접 쓴다. `shared`가 `account` 기능 (주체 조회 등)을 써야 하면 `shared`에 SPI를 두고 `account`가 구현해 모듈 순환을 피한다.
- 필터 예외는 Tomcat도 `trace_id` 없이 한 번 더 기록한다. `stack_trace`는 같은 printer를 거치므로 예외 메시지는 남지 않는다.
- 위험: 가상 스레드는 요청 수만큼 늘지만 Hikari 커넥션 풀은 고정 크기라, DB를 기다리는 요청이 풀에 몰린다. 풀 크기와 대기 시간 (`connection-timeout`)은 부하 측정 (D-11) 때 정한다.
- 출처: 1~8은 시작 보고에서 사용자가 확인했다 (2026-10-28). 9~13은 Claude가 구현하면서 정한 관례이며 종료 보고에서 확인을 받는다.
