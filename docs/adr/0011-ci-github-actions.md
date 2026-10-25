# ADR-0011: GitHub Actions CI (빌드·테스트)

- 상태: 채택
- 날짜: 2026-10-25
- 관련: `.github/workflows/ci.yml`, ADR-0008, ADR-0010

## 맥락
- Git Flow(ADR-0008)에서 `develop`·`main`으로 병합하기 전에 빌드·테스트를 자동으로 검증해야 한다.
- 테스트는 Testcontainers PostgreSQL을 쓰므로 Docker가 필요하다 (ADR-0010).

## 결정
| 항목 | 선택 |
|---|---|
| 플랫폼 | GitHub Actions, `ubuntu-latest` (Docker 기본 제공) |
| 트리거 | push: `main`·`develop`·`release/**`·`hotfix/**` / PR: `main`·`develop` 대상 |
| 단계 | checkout → setup-java (temurin 21) → setup-gradle → `./gradlew build --no-daemon` |
| 실패 시 | `build/reports/tests/`를 아티팩트로 7일 보관 |
| 권한 | `contents: read` |
| 동시 실행 | 같은 ref는 이전 실행을 취소 |
| 액션 버전 | 메이저 태그 고정 (`@v7` 등) |
| 범위 | 빌드·테스트만. 배포 제외 |

## 근거
- feature 브랜치는 `develop` 대상 PR에서 검사되므로, push 트리거에서는 제외해 중복 실행을 줄였다.
- `setup-gradle`은 의존성 캐시와 Wrapper 체크섬 검증을 함께 제공한다 → 변조된 `gradle-wrapper.jar`를 차단한다.
- 로컬과 같은 명령(`./gradlew build`)을 사용해, 로컬 검증과 CI 결과가 같은 기준을 따르게 했다.
- 배포는 비밀 값 주입 경로(D-05)가 정해지지 않아 제외했다. 빌드·테스트에는 비밀 값이 필요 없다.

| 대안 | 버린 이유 |
|---|---|
| `jdx/mise-action`으로 JDK 설정 | `mise.toml`과 같은 temurin 21을 `setup-java`로도 얻을 수 있음. 의존 액션이 하나 줄어듦 |
| 액션을 커밋 SHA로 고정 | 공급망 보안은 더 강하지만 갱신 비용이 큼 → D-06으로 보류 |
| 모든 브랜치 push에 실행 | PR 실행과 중복됨 |

## 결과
- 원격에 push하기 전까지 CI는 실행되지 않는다 (D-04).
- 브랜치 보호 규칙에서 `CI / build`를 필수 체크로 지정하는 작업은 D-04와 함께 정한다.
