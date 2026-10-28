# ADR-0026: 멱등 기록 구현 방식 (단계 2-2)

- 상태: 채택
- 날짜: 2026-10-28
- 관련: ADR-0014, ADR-0016, ADR-0021, ADR-0023, ADR-0025, FR-A-02, D-11, 브랜치 `feature/stage2-idempotency`

## 맥락
- ADR-0023 #7은 멱등 기록이 어떻게 동작해야 하는지를 정했다. 업무 변경과 같은 트랜잭션에 저장하고, 같은 키의 동시 요청은 기다리다 409로 응답하며, 4xx 응답도 저장한다. ADR-0025 #5는 Command 서비스가 실행기를 명시적으로 호출하도록 정했다.
- 다만 구현 방식은 남아 있었다. 잠금 대상, 4xx 롤백 방식, 본문 판별 기준, 변경 요청 구분, 키 형식, 내부 이벤트 완료 처리, 마이그레이션 이름 규칙이다.
- 기록 행은 응답이 확정된 뒤에야 생긴다 (ADR-0023: 응답 컬럼 NOT NULL). 그래서 처리 중인 같은 키를 행 잠금으로 기다리게 할 수 없다.

## 결정
### 시작 보고에서 사용자가 확인한 항목 (2026-10-28, 모두 Claude 추천안)
| # | 항목 | 결정 |
|---|---|---|
| 1 | 같은 키 동시 요청 잠금 | 트랜잭션 범위 advisory 잠금 (`pg_advisory_xact_lock(hashtextextended(주체/키))`)에 `lock_timeout`을 건다. 잠금을 얻으면 원래 `lock_timeout`으로 되돌려, 업무 로직의 행 잠금에는 이 대기 시간이 적용되지 않게 한다. 대기 시간을 넘기면 (SQLSTATE `55P03`) 409 `CONFLICT` / `IDEMPOTENCY_IN_PROGRESS` |
| 2 | 4xx 롤백 | 한 트랜잭션 안에서 업무 로직 전에 savepoint를 만든다. 저장 대상 4xx면 savepoint로 되돌리고, JPA 영속성 컨텍스트를 비운 뒤 기록만 커밋한다 |
| 3 | 잠금 대기 시간 | 기본 3초 (`pik.idempotency.lock-timeout`). 부하 측정 (D-11) 때 다시 정한다 |
| 4 | 같은 본문 판별 | `request_hash` = SHA-256(operation + 커맨드 record를 키 정렬 JSON으로 직렬화한 값) |
| 5 | 변경 요청 구분 | 컨트롤러 메서드에 `@IdempotentCommand`를 붙인다. 인터셉터가 본문 검증보다 먼저 키를 검사하고, 오류 응답에 `jobId`·`result` = `FAILED`를 붙인다 |
| 6 | 키 형식 | 정규 형식의 UUIDv7만 허용한다. 아니면 400 `INVALID_INPUT` / `IDEMPOTENCY_KEY_INVALID`이고 `jobId` = null |
| 7 | 내부 이벤트 완료 처리 | `spring.modulith.events.completion-mode` = DELETE. 처리가 끝나지 않은 발행만 남는다 |
| 8 | Flyway 파일 이름 | 순번 (`V1__`, `V2__`) |

### 구현하면서 정한 관례 (Claude 제안)
| # | 항목 | 결정 |
|---|---|---|
| 9 | savepoint 위치 | Spring의 HibernateJpaDialect는 JpaTransactionManager에 savepoint를 제공하지 않는다 (`NestedTransactionNotSupportedException`). 그래서 트랜잭션에 묶인 JDBC 연결 (`ConnectionHolder`)에서 savepoint를 만든다. 업무 로직의 JPA·JDBC 작업도 같은 연결을 쓴다 |
| 10 | 실행기 호출 위치 | 실행기가 트랜잭션을 시작한다. 이미 트랜잭션 안에서 호출하면 실패시킨다. 영속성 컨텍스트를 비우는 범위가 호출자의 작업까지 번지지 않게 하기 위해서다 |
| 11 | 저장 응답 | 2xx = 200 + 업무 결과 JSON. 4xx = ProblemDetail이며, `instance`는 Spring 예외 처리와 같은 값 (요청 경로)이다. 재전송에는 저장한 상태 코드와 본문을 그대로 돌려준다 (jsonb라 키 순서는 바뀔 수 있다). 실행기에 들어오기 전의 오류 (키 누락·형식, 본문 검증)는 저장하지 않는다. 같은 요청이면 같은 결과가 나오기 때문이다 |
| 12 | 만료 기록 | 보관 기간이 지났어도 정리되기 전이면 그대로 재생한다. 정리 배치는 1시간마다 (`cleanup-interval`), 1,000행씩 (`cleanup-batch-size`) 반복 삭제하고, ShedLock `idempotency-cleanup`으로 한 인스턴스만 실행한다 |
| 13 | ShedLock | 7.10.1을 쓴다 (Spring 7.0.9·Boot 4.1.1 기준 빌드, ADR-0014의 Boot 4 호환 확인 항목 해소). `usingDbTime()`이 UTC 시각을 시간대 없는 값으로 쓰므로 `shedlock` 시각 컬럼은 `timestamp`로 둔다 (ERD 수정) |
| 14 | 변경 번호 | `ChangeSequence.next(owner)` = `INSERT ... ON CONFLICT DO UPDATE ... RETURNING`. 호출자 트랜잭션이 필수다 (MANDATORY). 카운터 행 잠금이 커밋까지 유지되므로 번호가 커밋 순서대로 보인다 |

## 근거
- 1: 임시 응답으로 행을 먼저 저장하면 ADR-0023의 "처리 중 상태 없음"과 어긋난다. advisory 잠금 키는 64비트 해시라 충돌할 수 있지만, 그때도 다른 키가 잠깐 기다릴 뿐 결과는 같다.
- 2: 롤백한 뒤 새 트랜잭션에 기록하면, 그 사이에 같은 키의 재전송이 들어와 업무 로직이 다시 실행될 수 있다.
- 4: 단말이 다시 직렬화하면서 키 순서나 공백이 달라져도 같은 본문으로 본다.
- 5: GET이 아닌 요청 전체를 변경 요청으로 보면, 멱등 키를 쓰지 않는 `POST /installs`·`POST /auth/token` (ADR-0025 #2)까지 키를 요구하게 된다.
- 6: 아직 배포된 단말이 없으므로 지금 엄격하게 두어야 단말 구현 오류가 일찍 드러난다. `UUID.fromString`은 축약 형식도 받아들이므로 정규식으로 먼저 검사한다.
- 7: 이벤트 본문에 주체·사진 ID가 들어간다. 남겨 두면 보관 기간과 탈퇴 시 삭제 규칙을 새로 정해야 한다.
- 8: 같은 번호가 겹치면 Flyway가 기동할 때 실패하므로 병합 전에 드러난다. 시각 기반 이름은 이른 시각의 마이그레이션이 늦게 병합되면 운영 DB에서 순서 오류를 낸다.

| 대안 | 버린 이유 |
|---|---|
| 기록 행을 임시 응답으로 먼저 저장 | 처리 중 상태가 생김 |
| 롤백 후 별도 트랜잭션에 기록 | 두 트랜잭션 사이에 업무 로직이 다시 실행될 수 있음 |
| 공용 트랜잭션 매니저에서 savepoint 허용 | HibernateJpaDialect가 지원하지 않음 |
| 요청 원문 바이트의 해시 | 직렬화 차이만으로 같은 작업을 거부함 |
| GET이 아닌 요청 전체를 변경 요청으로 | 멱등 키를 쓰지 않는 POST까지 키를 요구함 |
| completion-mode UPDATE·ARCHIVE | 보관·삭제 규칙을 새로 정해야 함 |
| 시각 기반 Flyway 이름 | 병합 순서에 따라 운영 DB에서 순서 오류 |

## 결과
- V1 마이그레이션에 네 테이블을 만든다. `idempotency_record` (+ `ix_idempotency_record_expires_at`, ERD에도 추가), `owner_change_counter`, `event_publication` (Modulith 2.1.2 v2 DDL 그대로), `shedlock`이다. 주체 테이블은 2-3에서 `V2`로 만든다.
- 코드: `shared.idempotency` (실행기·키·인터셉터·정리 배치), `shared.change` (변경 번호), `shared.scheduling` (스케줄링·ShedLock), `shared.error`의 `CommandRequests`·`CommandResult`.
- 처리가 끝나지 않은 이벤트를 다시 처리하는 배치는 이벤트를 처음 발행하는 단계에서 넣는다.
- 장기 작업 접수 (재전송하면 현재 작업 상태를 돌려주고, `jobId` = 서버 작업 ID)는 단계 5에서 실행기에 추가한다. 지금 `@IdempotentCommand`는 즉시 처리하는 변경만 다룬다.
- 탈퇴 시 멱등 기록 삭제 (data-retention)는 탈퇴 구현 (단계 10)에서 넣는다.
- 위험: 같은 키를 기다리는 동안에도 DB 커넥션을 붙잡는다. ADR-0025의 가상 스레드·커넥션 풀 위험과 같으며, D-11 측정 때 함께 본다.
- 출처: 1~8은 시작 보고에서 사용자가 확인했다 (Claude 추천안 그대로). 9~14는 Claude가 구현하면서 정한 관례이며 종료 보고에서 확인을 받는다.
