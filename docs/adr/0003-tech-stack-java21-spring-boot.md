# ADR-0003: Java 21 + Spring Boot + Gradle

- 상태: 채택 (근거 미기록)
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
- 스택 선택 자체의 근거: **근거 미기록**. 검토한 대안과 선택 이유를 보완해야 한다.

## 결과
- 빌드·테스트 성공 여부는 exit code로 판정한다 (파이프로 출력을 가리지 않는다).
