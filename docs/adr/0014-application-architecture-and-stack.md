# ADR-0014: 서버 애플리케이션 아키텍처와 기술 스택

- 상태: 채택
- 날짜: 2026-10-25
- 관련: ADR-0003, ADR-0005, ADR-0010, ADR-0015, D-03, FR-G-01~04, FR-I-01~06, FR-A-02, FR-B-04, 브랜치 `feature/tech-stack-design`

## 맥락
- 요구사항에 응답 시간·사용자 수 같은 수치 목표가 없다 (D-11). API는 가벼운 CRUD·동기화이고, 지연·비용·장애는 외부 AI 호출(장당 수 초로 추정, 장당 과금, 제공자 rate limit)에 몰린다.
- 요구사항이 구조를 제약한다: 외부 작업은 실행 의도를 저장한 뒤 재처리, 변경 요청은 작업 식별자 + 데이터 버전, 장기 작업은 접수 응답, 늦은 분석 결과로 상태 복구 금지, 이벤트 브로커·조회용 DB는 부하·유실 복구 요구를 보고 결정.
- ADR-0003·0010의 선택(Spring Boot, MVC, PostgreSQL, JPA)은 근거가 기록되지 않았다 (D-03).

## 결정
| 영역 | 선택 |
|---|---|
| 구조 | 모듈러 모놀리스 (Spring Modulith 2.x). 모듈 = `domain-boundaries.md`의 도메인 후보 |
| 런타임 | Java 21 + 가상 스레드 (`spring.threads.virtual.enabled=true`) |
| 웹 | Spring MVC (현행 유지) |
| 내부 이벤트 | Modulith event publication registry (JDBC) = transactional outbox |
| 작업 큐 | 업무 테이블 + `FOR UPDATE SKIP LOCKED` 폴링. 별도 브로커 없음 |
| 영속성 | aggregate 변경 = JPA + `@Version`. 작업 획득·대량 upsert(`ON CONFLICT`)·keyset 동기화 조회 = `JdbcClient` |
| ID | UUIDv7 (PostgreSQL 18 `uuidv7()`). 단말이 만드는 작업 식별자도 UUIDv7 |
| 외부 호출 | `RestClient` + 연결·응답 타임아웃 필수. Spring Framework 7 `@Retryable`·`@ConcurrencyLimit`, 회로 차단은 Resilience4j |
| 캐시 | Caffeine (로컬). Redis 미도입 |
| 배치 | `@Scheduled` + ShedLock (JDBC) |
| 인증 토큰 | 자체 발급 access JWT (ES256, 짧은 만료) + refresh 토큰 (해시 저장·회전·재사용 탐지). Spring Security OAuth2 Resource Server. 비로그인 주체는 설치 단위 익명 토큰으로 식별 (발급·남용 방지 방식은 P08 확정 후. 제안: App Attest·Play Integrity 기기 증명) |
| API | REST/JSON, 헤더 기반 API 버전 (Framework 7), RFC 9457 `ProblemDetail` + `code`, `Idempotency-Key` 헤더, springdoc-openapi |
| 관측 | Micrometer + `spring-boot-starter-opentelemetry` (OTLP), 구조화 JSON 로그 (필드 허용 목록) |
| 테스트 | Testcontainers + WireMock (외부 연동) + Modulith 경계 검증 + k6 (부하) |

- 배포 단위는 이미지 하나를 `api`·`worker` 프로필로 나눠 실행한다 (ADR-0015).

## 근거
- 가상 스레드: 외부 응답 대기 중심 부하에서 스레드 고갈 없이 동시성을 얻는다. Java 21은 `synchronized` 구간에서 가상 스레드가 캐리어 스레드에 고정(pinning)되므로, 외부 호출마다 동시 실행 상한을 둔다.
- MVC 유지 (ADR-0010 사후 근거): 가상 스레드로 WebFlux 수준의 동시성을 얻으면서 블로킹 JPA·JDBC 코드를 단순하게 유지한다.
- PostgreSQL 단일 저장소 (ADR-0010 사후 근거): 실행 의도와 상태를 같은 트랜잭션에 기록해야 하므로, outbox·작업 큐를 같은 DB에 두면 이중 쓰기 문제가 없다. 운영 구성 요소가 줄어 장애 지점도 준다.
- Spring Boot (ADR-0003 사후 근거): Modulith·Security·Flyway·Testcontainers·관측 도구의 버전 호환을 한 생태계에서 관리한다.
- JPA + JdbcClient (ADR-0010 사후 근거): 불변조건 검증은 aggregate 단위 JPA가 맡고, 대량·잠금 쿼리는 JDBC가 N+1과 불필요한 flush 없이 처리한다.
- UUIDv7: 시간순 키라 B-tree 삽입 지역성이 좋다.
- 재시도 제한: 비용이 드는 외부 호출은 작업 이력을 대조한 뒤에만 재시도한다 (`consistency-rules.md`).

| 대안 | 버린 이유 |
|---|---|
| WebFlux | 가상 스레드로 동시성을 확보하므로 반응형 코드·JPA 비호환 비용만 남음 |
| MSA | 1인 개발이고 배포 단위 분리 요구가 없음. 분석 격리는 프로필 분리로 해결 |
| Kafka·SQS | 같은 DB outbox로 요구 충족. 처리량이 DB 폴링 한계에 닿거나 외부 시스템으로 이벤트를 내보낼 때 도입 (SQS 우선) |
| Redis | 인스턴스 간 공유 rate limit·캐시가 필요해질 때 도입 |
| Spring Batch | 대량 ETL이 없음. 점검 배치는 `@Scheduled`로 충분 |
| GraalVM native | 장기 실행 컨테이너라 이점이 작음. 기동 시간은 CDS로 단축 (ADR-0015) |

## 결과
- 외부 호출은 트랜잭션 밖에서 실행하고, 의도·결과만 트랜잭션에 기록한다.
- 저장소 메서드는 소유자 ID 없이 조회할 수 없게 만들고, 아키텍처 테스트로 검증한다.
- Resilience4j·ShedLock·springdoc-openapi는 도입 시점에 Spring Boot 4 호환 버전을 확인한다.
- ADR-0003·0010의 `근거 미기록` 항목은 이 ADR을 사후 검토 근거로 연결한다 (D-03 해소). 선택 당시의 근거는 여전히 기록되지 않았다.
- 출처: 구성은 Claude 설계안이며, 사용자가 종료 보고에서 이 구성을 확인했다 (2026-10-25). `(제안)` 항목은 구현 전에 사용자 확인을 받는다 (D-14). 외부 제품 사실은 이 날짜의 공식 문서·발표 기준이며, 도입할 때 버전별로 다시 확인한다.
