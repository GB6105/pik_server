# ADR-0012: Lombok 제한적 사용

- 상태: 채택
- 날짜: 2026-10-25
- 관련: ADR-0005, ADR-0010, 브랜치 `feature/lombok-config`

## 맥락
- JPA 엔티티는 `record`로 만들 수 없다 (기본 생성자 필요, `final` 불가, 상태 변경 필요). Lombok 없이는 엔티티 getter·`protected` 기본 생성자·서비스 생성자 주입 코드를 직접 작성해야 한다.
- 엔티티에 `@Data`·`@Setter`·`@EqualsAndHashCode`·`@ToString`을 붙이면 지연 로딩 유발, 양방향 연관관계 무한 재귀, `Set` 안 엔티티의 hashCode 변경 문제가 생긴다.
- aggregate root가 버전·상태 전이를 검증해야 하므로 (`.claude/docs/domain-boundaries.md`) setter로 상태를 바꾸는 경로가 없어야 한다.

## 결정
| 구분 | 대상 |
|---|---|
| 허용 | `@Getter`, `@NoArgsConstructor`, `@RequiredArgsConstructor`, `@Slf4j` |
| 차단 (`lombok.config`, 컴파일 오류) | 그 외 전부: `@Data`, `@Value`, `@Setter`, `@EqualsAndHashCode`, `@ToString`, `@AllArgsConstructor`, `@Builder`, `@With`, `@Getter(lazy)`, `@NonNull`, `@Cleanup`, `@SneakyThrows`, `@Synchronized`, `@Locked`, `val`, `var`, experimental 전체, Slf4j 외 로거 |
| 차단 (Gradle `forbidLombokSuperBuilder`, `check` 단계) | `@SuperBuilder` |
| 문서 규칙 (`CLAUDE.md`) | 엔티티 `@NoArgsConstructor(access = AccessLevel.PROTECTED)`, 엔티티 상태 변경은 도메인 메서드만 |
| Lombok 미사용 | DTO·커맨드·이벤트·값 객체는 `record` |

- `lombok.addLombokGeneratedAnnotation = true`: 커버리지 측정에서 생성 코드를 제외한다.
- Lombok 버전은 Spring Boot 4.1.2 의존성 관리 값(1.18.48)을 따른다.

## 근거
- 반복 코드가 가장 많은 곳(엔티티 getter·생성자, 생성자 주입, 로거)만 허용해 이득을 얻고, 엔티티 오용 위험이 있는 어노테이션은 빌드에서 차단한다.
- `@Builder`·`@AllArgsConstructor`는 생성자의 검증을 우회하는 객체 생성 경로를 만든다.
- 검증: 차단 대상마다 별도 파일로 컴파일해 오류를 확인했다. `@SuperBuilder`만 Lombok 1.18.48이 `lombok.superBuilder.flagUsage`·`lombok.experimental.flagUsage = ERROR`를 무시해 컴파일에 성공했다 (`lombok config`로 두 키가 ERROR로 적용된 것을 확인, 빌더 클래스 생성됨) → Gradle task로 보완했다.

| 대안 | 버린 이유 |
|---|---|
| Lombok 미사용 | 엔티티·서비스마다 getter·생성자 반복 코드가 생긴다 |
| 제한 없이 사용 | 엔티티 오용을 리뷰에만 의존한다 |
| 금지 목록만 차단 (나머지 허용) | 새 어노테이션이 검토 없이 들어온다. 사용자가 허용 목록 방식을 선택했다 |

## 결과
- 허용 목록 밖의 Lombok 어노테이션을 쓰려면 이 ADR을 대체하는 결정과 `lombok.config` 변경이 필요하다.
- `@NoArgsConstructor`의 접근 수준은 빌드에서 강제되지 않는다. 리뷰에서 확인한다.
- Lombok 버전을 올릴 때 `@SuperBuilder` flagUsage가 동작하면 Gradle task를 제거할 수 있다.
