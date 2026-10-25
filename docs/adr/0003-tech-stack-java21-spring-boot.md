# ADR-0003: Java 21 + Spring Boot + Gradle

- 상태: 채택 (사후 검토 근거: ADR-0014)
- 날짜: 2026-10-25
- 관련: 커밋 `11fd080`

## 맥락
iOS·Android 클라이언트가 공유하는 백엔드가 필요하다.

## 결정
- 언어·런타임: Java 21 (toolchain 고정)
- 프레임워크: Spring Boot
- 빌드: Gradle (`./gradlew build | test | bootRun`)
- Java 21 이후 버전의 preview 기능은 사용하지 않는다.

## 근거
- preview 금지: preview 기능은 다음 릴리스에서 바뀌거나 제거될 수 있어 운영 코드의 안정성을 해친다.
- 스택 선택 자체의 근거: 선택 당시 근거는 기록되지 않았다. 요구사항에 비춘 사후 검토 근거는 [ADR-0014](0014-application-architecture-and-stack.md)에 기록했다 (D-03 해소).

## 결과
- 빌드·테스트 성공 여부는 exit code로 판정한다 (파이프로 출력을 가리지 않는다).
