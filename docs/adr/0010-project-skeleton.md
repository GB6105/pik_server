# ADR-0010: 프로젝트 골격 구성

- 상태: 채택 (근거 일부 미기록)
- 날짜: 2026-10-25
- 관련: ADR-0003, ADR-0004, 브랜치 `feature/spring-boot-init`

## 맥락
- ADR-0003의 스택(Java 21 + Spring Boot + Gradle)으로 빌드 가능한 골격이 필요하다.
- 사용자가 Spring Initializr로 생성한 결과를 기준으로 한다.

## 결정
| 항목 | 선택 |
|---|---|
| Spring Boot | 4.1.2 (생성 시점 Initializr 기본값, 최신 GA) |
| Gradle | 9.8.2 Wrapper, Kotlin DSL (`build.gradle.kts`) |
| JDK | temurin-21, `mise.toml`로 고정 |
| 좌표 | group `io.github.gb6105`, 패키지 `io.github.gb6105.pik`, 이름 `pik-server` |
| 웹 | `spring-boot-starter-webmvc` (서블릿 MVC) |
| DB | PostgreSQL 18, Spring Data JPA |
| 스키마 | Flyway 마이그레이션, `ddl-auto: validate` |
| JPA | `open-in-view: false` |
| 로컬 DB | `compose.yaml` + `spring-boot-docker-compose` (`developmentOnly`) |
| 테스트 | Testcontainers 2.x PostgreSQL + `@ServiceConnection` |
| 운영 | Actuator |
| 설정 | `application.yml`, `.env.local`을 선택적으로 import |

## 근거
- `ddl-auto: validate` + Flyway: 스키마 변경 이력을 마이그레이션 파일로 남기고, 엔티티와 스키마가 어긋나면 기동 시점에 실패하게 한다.
- `open-in-view: false`: 트랜잭션 밖(뷰 렌더링 중)의 지연 로딩 쿼리를 막는다. 외부 연동을 트랜잭션에서 분리하는 불변 규칙(ADR-0005)과도 맞는다.
- Testcontainers: 테스트를 운영과 같은 PostgreSQL 18에서 실행해, H2 같은 대체 DB와 동작이 다른 문제를 피한다.
- 비밀 값은 `.env.local`(로컬)과 주입된 환경 변수(배포)로만 받는다 (ADR-0004).
- PostgreSQL·JPA·MVC 선택 자체, 그리고 좌표: **근거 미기록** → D-03에서 함께 보완한다.

## 결과
- `test`·`build`에 Docker가 필요하고, `bootRun`에는 Docker와 `.env.local`이 필요하다.
- 첫 엔티티를 만들 때 `src/main/resources/db/migration/V1__*.sql`이 필요하다.
- `.claude/settings.json`의 Read deny를 `.env.*`에서 `.env.local`·`.env.*.local`로 좁혀 `.env.example`을 읽을 수 있게 했다 (사용자 변경).
