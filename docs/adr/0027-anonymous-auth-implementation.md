# ADR-0027: 익명 인증 구현 방식 (단계 2-3)

- 상태: 채택
- 날짜: 2026-10-28
- 관련: ADR-0020, ADR-0023, ADR-0025, ADR-0026, FR-A-02, FR-A-04, FR-A-05, 브랜치 `feature/stage2-anonymous-auth`

## 맥락
- ADR-0023은 access JWT (ES256, 15분, 클레임·`kid`)와 설치 토큰 (기기 증명, 만료 없음, 해시 저장, 폐기 가능)의 값을 정했다. ADR-0025는 엔드포인트 (`POST /installs`, `POST /auth/token`), 재전송 처리, 설치 토큰 형식, 로컬 임시 서명 키를 정했다.
- 구현 방식은 남아 있었다. 기기 증명 대역을 어디까지 허용할지, 운영 서명 키를 어떤 형태로 공급할지, 설치 ID를 누가 만드는지, 기기 증명 요청의 모양, 그리고 필터 단계에서 거부된 변경 요청의 `jobId` (ADR-0026 결과)다.
- 실제 기기 증명 (App Attest·Play Integrity) 검증은 Apple·Google 연동과 challenge 발급이 필요해 이번 범위에서 뺐다. 그래서 대역이 운영에서 실행되지 않게 막는 장치가 필요하다.

## 결정
### 시작 보고에서 사용자가 확인한 항목 (2026-10-28, 모두 Claude 추천안)
| # | 항목 | 결정 |
|---|---|---|
| 1 | 대역·임시 키 허용 범위 | 기기 증명 대역과 임시 JWT 서명 키는 `local`·`test` 프로필에서만 쓴다. 그 밖의 프로필 (프로필 없음 포함)은 기동을 실패시킨다. 활성 프로필만 보고, 기본 프로필 (`spring.profiles.default`)은 인정하지 않는다. Gradle `test` 작업은 `test`, `bootRun`·`bootTestRun` 작업은 `local` 프로필을 켠다 |
| 2 | 운영 서명 키 공급 | JWK Set JSON 하나 (`PIK_SECURITY_JWT_JWK_SET` → `pik.security.jwt.jwk-set`). 현재 키는 개인 키를 포함하고, 이전 키는 공개 키만 둔다. 키를 교체할 때는 새 키를 개인 키로 넣고 이전 키를 공개 키로 남겨, 이전 키로 서명된 토큰이 만료될 때까지 검증되게 한다 |
| 3 | 설치 ID | 서버가 `POST /installs`에서 UUIDv7로 만들어 응답한다 (`device.install_id`). 단말은 이 값을 보관한다 |
| 4 | 기기 증명 요청 | 요청 본문에 `attestation` 객체만 둔다. challenge 발급 API는 실제 검증을 구현할 때 추가한다. 시작 보고에서는 객체에 종류·토큰·키 ID를 담기로 했으나, 구현하면서 종류는 `platform`에서 도출하도록 바꿨다 (IOS = `APP_ATTEST`, ANDROID = `PLAY_INTEGRITY`). 객체에는 `token`과 App Attest용 `keyId`만 남는다 |
| 5 | 필터 단계 거부의 `jobId` | 인증 실패 처리기가 요청에 해당하는 핸들러 메서드를 찾는다. `@IdempotentCommand`이면 오류 응답에 `jobId` (유효한 `Idempotency-Key`, 아니면 null)와 `result` = `FAILED`를 붙인다 |

### 구현하면서 정한 관례 (Claude 제안)
| # | 항목 | 결정 |
|---|---|---|
| 6 | 모듈 경계 | `shared.security`: 보안 필터 체인, JWT 검증·발급 (`AccessTokens`), 실패 응답, `CurrentPrincipal`, 공개 경로 SPI (`PublicEndpoints`). `account`: 주체·설치·기기 엔티티, 기기 증명, 두 엔드포인트, `PublicEndpoints` 구현. `shared`는 `account`를 참조하지 않는다 |
| 7 | 응답 | `POST /installs` = 201 `{installId, installToken}`. `POST /auth/token` = 200 `{accessToken, tokenType: "Bearer", expiresIn}` (`expiresIn`은 초). 두 엔드포인트는 멱등 키를 쓰지 않으므로 (ADR-0025 #2) Command 공통 필드 (`jobId`·`result`)가 없다 |
| 8 | 요청 | `POST /installs` = `{platform: IOS / ANDROID, appVersion?, attestation: {token, keyId?}}`. `POST /auth/token` = `{grantType: "install_token", installToken}`. 모르는 `grantType` = 400 `INVALID_INPUT` / `UNSUPPORTED_GRANT_TYPE`, `installToken` 누락 = 400 / `VALIDATION_FAILED`. 계정 로그인은 `grantType`을 추가한다 |
| 9 | 설치 토큰 거부 | 없는 토큰, 폐기된 토큰 (`revoked_at`), 활성이 아닌 주체 (`DELETING`·`DELETED`)를 모두 401 `UNAUTHENTICATED` / `INSTALL_TOKEN_INVALID` 하나로 응답한다 |
| 10 | 기본 거부 | `PublicEndpoints`가 나열한 경로, 오류 디스패치, actuator health만 토큰 없이 허용한다. 공개 경로에서는 `Authorization` 헤더를 무시해, 만료된 토큰을 붙인 요청도 토큰을 새로 받을 수 있다. health 밖의 actuator (`/actuator` 목록 포함)는 토큰이 있어도 거부한다 (403). 그 밖의 경로는 access token이 필요하다. 그래서 없는 경로와 `/error` 직접 요청도 토큰이 없으면 404가 아니라 401로 응답한다 |
| 11 | 인증 실패 응답 | 토큰이 없으면 401 `ACCESS_TOKEN_REQUIRED` + `WWW-Authenticate: Bearer`, 토큰이 잘못됐거나 만료됐으면 401 `ACCESS_TOKEN_INVALID` + `WWW-Authenticate: Bearer error="invalid_token"`이다. 권한 부족 (actuator 등)은 403 `FORBIDDEN` / `ACCESS_DENIED`이다. 컨트롤러가 낸 401 (`INSTALL_TOKEN_INVALID`)에도 `WWW-Authenticate: Bearer`를 붙인다 |
| 12 | 실패 응답 렌더링 | 보안 필터의 실패 처리기는 `ProblemDetail`을 직접 쓰지 않고, `PikException`을 `HandlerExceptionResolver`에 넘겨 컨트롤러 오류와 같은 `ApiExceptionHandler`가 쓰게 한다 (ADR-0025 결과의 "직접 쓴다"를 대체). 핸들러를 찾을 때 남는 `produces` 속성은 지워, 응답이 항상 `application/problem+json`이 되게 한다. 렌더링하지 못하면 `sendError`로 `/error` 컨트롤러에 넘긴다 |
| 13 | JWT 검증 | ES256만 받는다 (`alg: none`·HS256 거부). `kid`가 없으면 거부한다. 헤더 `typ` = `JWT`로 발급하고, 다르거나 없으면 거부한다. `iss`, `exp` (필수), `sub` (정규 형식 UUID), `kind` (ACCOUNT / ANONYMOUS)를 확인한다. 만료 허용 오차는 60초 (`pik.security.jwt.clock-skew`), 발급자는 `pik-server` (`pik.security.jwt.issuer`)이다 |
| 14 | 키 집합 검증 | 모든 키는 EC P-256이고, `kid`가 비어 있지 않으며 중복되지 않아야 한다. `use`·`alg`가 있으면 `sig`·`ES256`이어야 한다. 개인 키는 정확히 1개 (서명 키)이고, 기동할 때 서명·검증을 한 번 해 공개 키와 짝이 맞는지 확인한다. 어기면 기동을 실패시킨다. 설정 객체의 `toString`은 키 집합을 가린다. JSON 해석 오류 메시지에는 키 원문이 들어갈 수 있어 원인 예외를 연결하지 않는다 |
| 15 | 마지막 사용 시각 | 토큰을 교환할 때 `anonymous_install.last_seen_at`이 1일 (`pik.account.last-seen-interval`) 넘게 지났을 때만 그 주체의 `anonymous_install`·`device` 행을 갱신한다. 엔티티 도메인 메서드가 아니라 조건부 UPDATE 문 (`last_seen_at <= 기준 시각`)으로 갱신하고, `device.version`은 올리지 않는다. 엔티티 상태 변경을 도메인 메서드로만 하는 규칙 (CLAUDE.md)의 예외다 |
| 16 | 기기 증명 대역 | 정해진 토큰 하나만 통과시키고, 나머지는 403 `FORBIDDEN` / `ATTESTATION_FAILED`로 거부한다. `attestation_kind`는 `platform`에서 도출해 저장한다. 기기 증명은 DB 트랜잭션 밖에서 실행하고, 주체·설치·기기 행은 한 트랜잭션에서 저장한다 |
| 17 | 로그 | access token을 검증한 뒤 `principal_id`를 MDC에 두고, 요청 완료 로그에도 남긴다. `/installs`·`/auth/token`은 처리한 주체 ID를 완료 로그에 남긴다. 토큰 값과 `Authorization` 헤더는 남기지 않는다 |
| 18 | ID 생성 | 서버가 만드는 ID는 `shared.id.UuidV7` (RFC 9562, `SecureRandom`)로 만든다. ID를 미리 정하므로 새 엔티티는 `EntityManager.persist`로 저장한다 (Spring Data `save`는 merge로 처리해 조회가 한 번 더 생김) |

## 근거
- 1: 대역이 운영에서 실행되면 기기 증명 없이 설치 토큰이 발급된다. 운영 프로필을 지정하지 않은 배포에서도 기동이 실패해야 사고가 드러난다. 그래서 허용 목록 (`local`·`test`)으로 판단하고, 차단 목록 (`prod`)은 쓰지 않는다.
- 2: 환경 변수 하나로 현재 키와 이전 키를 함께 공급하면, 키 교체가 값 하나를 바꾸는 작업이 된다. ECS task `secrets` (ADR-0015)에 그대로 넣을 수 있다.
- 3: 설치 ID를 단말이 만들면 다른 설치의 ID를 보내 기기 행을 가로챌 여지가 생긴다. 서버가 만들면 같은 설치에서 로그인할 때 (FR-A-04) 이 ID로 기기를 이어 붙일 수 있다.
- 4: challenge 없이 받은 증명은 재사용 공격을 막지 못하지만, 실제 검증을 넣을 때 challenge API와 함께 바꾸면 된다. 지금 요청 모양을 정해 두면 단말 구현을 먼저 시작할 수 있다. 종류는 플랫폼마다 하나뿐이라 따로 받으면 불일치만 검사하게 된다.
- 5: 단말은 변경 요청의 오류 응답에서 `jobId`로 재전송 대상을 찾는다 (ADR-0023 #12). 인증 실패만 이 필드가 빠지면 단말이 경로마다 예외를 처리해야 한다.
- 9: 사유를 나누면 토큰이 존재하는지, 주체가 탈퇴했는지를 응답으로 알아낼 수 있다. 단말은 어느 경우든 새 설치 토큰을 받아야 하므로 구분할 필요가 없다.
- 10: 공개 경로를 명시적으로 나열하면, 새 엔드포인트가 인증 없이 열리는 실수를 막는다. 없는 경로의 401은 경로 존재를 드러내지 않는 효과도 있다.
- 12: 오류 형식 (`type`·`title`·`code`·`reason`·`retryable`·`jobId`)을 한 곳에서만 만들면, 형식을 바꿀 때 보안 경로가 어긋나지 않는다.
- 13: `kid`가 없으면 Nimbus는 집합의 모든 키로 검증을 시도한다. 이 서버가 발급한 토큰은 항상 `kid`를 가지므로, 없는 토큰은 다른 곳에서 만든 것이다. `typ`을 검사하지 않으면 같은 키로 다른 종류의 JWT를 서명하게 됐을 때 access token으로 받아들인다. 직접 만든 검증기로 바꾸면서 Spring Security 기본 검증기의 `typ` 검사가 빠져 따로 넣었다.
- 15: 엔티티를 읽어 고치면, 하루의 첫 교환이 동시에 여러 건 오면 `device` 낙관적 잠금 충돌로 하나만 성공하고 나머지는 503이 됐다 (코드 검토에서 재현). 조건부 UPDATE는 먼저 온 요청만 쓰고 나머지는 0행을 갱신하므로 충돌이 없다. 마지막 사용 시각은 단말이 바꾸는 데이터가 아니므로, 이 값 때문에 `device.version`이 올라 단말의 변경 요청이 거짓 충돌을 내지 않게 한다.

| 대안 | 버린 이유 |
|---|---|
| 운영 프로필 (`prod`)에서만 대역 차단 | 프로필을 빠뜨린 배포에서 대역이 실행됨 |
| 키마다 환경 변수 (현재·이전 PEM) | 키 교체 때 여러 값을 함께 바꿔야 하고, 이전 키가 여러 개면 변수가 늘어남 |
| 단말이 설치 ID를 생성 | 다른 설치의 ID를 보내 기기 행을 가로챌 수 있음 |
| 필터 단계 거부에는 `jobId` 없음 | 단말이 인증 실패만 별도로 처리해야 함 |
| 실패 처리기가 `ProblemDetail`을 직접 작성 | 오류 형식을 두 곳에서 만들게 됨 |
| 설치 토큰 거부 사유 세분화 | 토큰 존재·탈퇴 여부를 응답으로 알아낼 수 있음 |
| 마지막 사용 시각을 엔티티 도메인 메서드로 갱신 | 동시 교환에서 낙관적 잠금 충돌 (503), 매일 `device.version` 증가 |
| health 밖의 actuator를 토큰만으로 허용 | 모든 앱 설치가 익명 토큰을 받을 수 있어 운영자 전용 endpoint가 사실상 공개됨 |

## 결과
- V2 마이그레이션이 `principal`·`anonymous_install`·`device`를 만든다. `device.trial_mark_id`에는 FK를 두지 않는다 (다른 모듈, ADR-0022 #3). `account`·`refresh_token`은 로그인 구현 때 추가한다.
- 코드: `shared.security`, `shared.id`, `shared.logging.PrincipalLogging`, `shared.idempotency.IdempotentCommands`, `account` 모듈의 엔티티·서비스·컨트롤러.
- 컨트롤러는 `CurrentPrincipal` 파라미터로 요청 주체를 받는다. 소유 범위 검증은 이 값으로 한다.
- `.env.example`에 `PIK_SECURITY_JWT_JWK_SET`을 추가했다. 체험 기록 (`device_trial_mark`)용 HMAC 키는 체험 구현 단계에서 추가한다.
- Spring Security 기본 응답 헤더 (`Cache-Control: no-cache, no-store`, `X-Content-Type-Options` 등)가 모든 응답에 붙는다. 조회 응답에 캐시 검증 (ETag, D-14)을 넣을 때 다시 확인한다.
- 남은 작업: 실제 기기 증명 검증과 challenge API는 운영 배포 전에 구현한다. 그때까지 `local`·`test` 밖에서는 기동이 실패하므로 배포할 수 없다. 운영 JWK Set 생성·보관 절차는 배포 환경 구성 (D-13) 때 정한다.
- 위험: 토큰 교환은 요청마다 설치·주체 행을 조회한다 (15분마다 설치당 1회). 부하 측정 (D-11) 때 함께 본다.
- 설치 토큰을 폐기하거나 주체가 `DELETING`이 돼도 이미 발급한 access token은 최대 15분 동안 유효하다 (ADR-0023 #3). `CurrentPrincipal`에는 주체 상태가 없으므로, 탈퇴 구현 (단계 10, FR-A-05)에서 `DELETING` 주체의 신규 분석·동기화 차단을 따로 넣는다.
- 운영자용 actuator endpoint (지표 등)가 필요해지면 별도 관리 포트나 운영자 인증을 정한다.
- 출처: 1~5는 시작 보고에서 사용자가 확인했다 (Claude 추천안 그대로, 4의 종류 도출은 구현 중 변경, 1의 기본 프로필 제외는 코드 검토 반영). 6~18은 Claude가 구현·코드 검토 반영 중 정한 관례이며, 종료 보고에서 사용자 확인을 받는다.
