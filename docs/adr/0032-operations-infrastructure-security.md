# ADR-0032: 운영·인프라·보안 설계 (네트워크·RDS·SLO·actuator·CI·기기 증명·복구·키)

- 상태: 채택 (기술 항목, Codex 비판 검토 R1~R8 수렴, 2026-11-01). #1 네트워크 출구·#6-2 첫 체험 assertion·#7 DeviceCheck 장애·#8-6 보안 거부 결정 원장은 **제안** (사용자 결정 대기: D-13·D-21·D-23·D-24)
- 날짜: 2026-11-01
- 관련: FR-B-01, FR-O-02, FR-L-05, ADR-0015, ADR-0020, ADR-0027, ADR-0029 #12·#18, ADR-0030 #5 보완·#6, ADR-0031, 설계 문서 `docs/design/operations-infrastructure.md`

## 맥락
- 단계 5 설계와 함께 운영 배포 전에 필요한 미결 결정 (D-06·D-11·D-13·D-21·D-22·D-23)과 DB 복구·키 운영을 Codex와 검토했다. 기준은 안정성·비용·속도다.
- 가격은 AWS 서울 공식 Price List 기준이다 (2026-11-01 확인, USD, 세금 제외).
- 세부 계약 (설정 값·검증 조건·런북 단계)은 설계 문서 `docs/design/operations-infrastructure.md`에 둔다. 이 ADR은 결정과 근거를 요약하며, 둘이 다르면 설계 문서를 기준으로 이 ADR을 고친다.

## 결정
| # | 항목 | 결정 |
|---|---|---|
| 1 | 네트워크 출구 (**제안**, D-13) | NAT 없이 `api`·`worker` 태스크를 public subnet에 두고 공인 IPv4를 붙인다. 보안 그룹: `api` = ALB SG에서 앱 포트만, `worker` = 인바운드 없음, RDS = 태스크 SG에서 DB 포트만. RDS는 private subnet, S3는 gateway endpoint (무료). interface endpoint는 두지 않는다. 고정 출구 IP가 필요한 연동이 생기면 그때 NAT·출구 프록시를 비교한다 |
| 2 | RDS | Multi-AZ (standby 1개), 시작 `db.t4g.medium`. 배포 전에 서울 PG18 × 이 클래스 × Multi-AZ 주문 가능 여부를 `DescribeOrderableDBInstanceOptions`로 확인한다. RDS Proxy는 두지 않고, 서비스별 최대 태스크 수는 커넥션 예산식 (설계 근거 참조)으로 정한다. CPU credit·메모리·DB 부하·Hikari 대기를 경보한다 |
| 3 | SLO (D-11) | 월 가용성 99.5% (초기 내부 목표). 서버 처리 p95: 외부 호출 없는 API 300ms, 분석 접수 500ms (DeviceCheck 왕복은 별도 지표). 사용자 완료 (업로드 확정 → 첫 사용 가능 결과)는 Messages 경로 30초를 따로 측정한다. 검색 해석 Basic 600ms·Pro 1.5초. 경보는 burn rate로 하고, 부하 측정 뒤 조정한다 |
| 4 | 운영자 actuator (D-22) | `management.server.port` 8081을 loopback에 바인딩하고, 수동 조회는 ECS Exec + 관리 포트 읽기 전용 범위로 한다. 앱 포트에는 `probes.enabled=true`·`probes.add-additional-paths=true`로 `/livez`·`/readyz`만 열고, SecurityConfig는 `EndpointRequest.toAdditionalPaths(SERVER, "health")`를 공개하며 잘못된 JWT가 health를 막지 않게 한다. ALB 헬스 체크 = `/readyz`. liveness에는 외부 의존성을 넣지 않는다. 지표는 OTLP (ADOT 사이드카) |
| 5 | CI 액션 고정 (D-06) | full commit SHA + 최소 권한 + Dependabot (`github-actions`, 주 1회). 긴급 보안 갱신은 주기와 별도로 한다 |
| 6-1 | 기기 증명 challenge (D-21) | `POST /installs/challenge`가 32바이트 난수 (유효 5분, 1회 사용, DB에 해시)를 발급한다. challenge 소비와 설치 생성은 같은 DB 전이다. 바인딩 = 직렬화를 고정한 SHA-256(`protocolVersion, operation, platform, challengeId, nonce, trialEvidence`)이고, 서버가 다시 계산해 대조한다. Android = Play Integrity Standard `requestHash` (앱·패키지·서명 인증서·시간·verdict 검증), iOS = App Attest attestation `clientDataHash` (인증서·앱 ID·환경·key ID 검증). 설치 멱등 재생은 소비한 challenge를 다시 외부 검증하지 않는다 |
| 6-2 | 첫 체험 App Attest assertion (**제안**, D-21) | 추천 = 첫 출시에서 제외. 해시 바인딩은 제출 값 변조만 막고, 기기 식별 값이 그 물리 기기의 값임은 증명하지 못한다 (ADR-0029 잔여 위험 유지) |
| 7 | DeviceCheck 장애 (**제안**, D-23) | 설치 때 조회가 전송 장애·Apple 5xx로 실패하면 설치를 진행하고 기기를 "확인 전"으로 둔다. 확인 전 기기는 첫 체험 접수에서 새 토큰 조회·기록이 성공해야 체험을 시작한다 (실패하면 그 접수만 503). 제공자의 토큰 거절·증명 실패 = 403, 요청 형식 오류 = 400 유지 |
| 8 | DB 복구 런북 (PITR) | ① 모든 writer·외부 전송 격리 ② 복원된 `SENDING` 호출 = 최대 비용·위험 송신 1회 정산, 복구 조정 중 불명 자동 재호출 잠금 ③ 손실 구간 = 복원 시각 ~ 구 writer 중단 시각, 총비용만 제공자 사용량으로 대조, 영향 접수의 자동 재실행 보류, 그날 남은 비용 허용액은 운영자가 확정 ④ 예약은 사진별 C/D로 예약마다 대조, 불일치는 격리 ⑤ S3 객체는 복원된 작업·시도와 대조 ⑥ 보안 거부 결정 재적용 (아래 8-6) ⑦ publication 재제출, 단말 cursor는 409 `RESYNC_REQUIRED`. 런북 `docs/runbook/db-restore.md`, 분기 1회 복원 연습 |
| 8-6 | 보안 거부 결정 원장 (**제안**, D-24) | 동의 철회·탈퇴·토큰 폐기만 S3 객체 1건/결정 (`denials/{yyyy}/{mm}/{dd}/{decisionId}.json` = 결정 ID·주체 ID·종류·범위 (주체 전체 또는 특정 refresh 세션)·복구 세대, 토큰·사진 원문 없음)으로 남긴다. 순서 = 원장 PUT → DB TX (PUT 뒤 30초 안) → `.committed` 표시 (best-effort) → 응답. 원장 PUT 실패 = 503. 복구 = 보존된 원장 전체 열거 + 결정 ID 멱등 적용. 미확정 철회·폐기 = 차단 적용, 미확정 탈퇴 = 계정·전송 격리까지만 (물리 삭제는 DB 증거 또는 사용자 재확인 뒤). 원장 적용 전에는 외부 전송을 재개하지 않는다. 보관 ≥ 최대 반영 지연 + 허용 최대 복원 과거폭 (RDS 백업 보존 기간) + 여유 → 초안 1년 |
| 9 | 삭제·재동기화 | tombstone 보관 기간 뒤 오래된 단말 동기화 = 409 + retention floor. 주체 삭제 = `DELETING` → 신규 작업 차단 → 예약 정산·호출 종료 → 모듈별 멱등 삭제 → cursor floor. `device_trial_mark`·HMAC 키는 사진 데이터 삭제와 분리 |
| 10 | 키 운영 | 운영 JWK Set·HMAC 키 = SSM SecureString, 값 변경은 재배포로만 반영한다. JWT는 ADR-0030 #6의 2단계. Android ID HMAC 최초 생성은 가장 낮은 설정 버전 키로 기기를 잠근 뒤 전 버전을 재조회하고, 없을 때만 활성 버전으로 삽입한다. 키 2개 이상이면 `PIK_USAGE_DEVICE_KEY_ACTIVE` 필수 (ADR-0030 #5의 "설정이 없으면 가장 큰 버전" 기본 동작은 키 1개일 때만 남는다). 절차 `docs/runbook/key-rotation.md` |

## 근거
- 1: 출구 증분 비용은 태스크 공인 IPv4 3개 $10.95/월, Zonal NAT 2개 $93.44/월 + $0.059/GB, interface endpoint 5종 × 2AZ $94.90/월이다. Anthropic·RevenueCat·Apple·Google의 일반 인증 계약에는 고정 출구 IP 조건이 없다 (2026-11-01 확인). ADR-0015의 NAT 구성을 바꾸므로 사용자가 정한다.
- 2: `db.t4g.medium` $148.19/월, `db.m7g.large` $342.22/월 (Multi-AZ). 커넥션 예산 = Σ(서비스 최대 태스크 × 배포 중 증가 배수 × Hikari 풀) + Flyway·운영·복구 여유 ≤ `max_connections` − 예약 − 안전 여유.
- 4: 관리 포트를 앱 포트와 분리하면 운영자 인증을 새로 만들 필요가 없다. Spring Boot 4.1.2의 additional paths로 헬스 체크만 앱 포트에 노출한다.
- 7: Apple 장애 때 새 iOS 사용자가 앱을 설치하지 못하는 것을 막는다. 체험 시작 시점 (ADR-0029)은 바꾸지 않는다.
- 8: RDS PITR은 트랜잭션 로그를 5분마다 올리므로 최근 커밋을 잃을 수 있다. 유니크 제약은 손실 구간에서 사라진 행의 중복까지 막지 못한다.
- 8-6: 철회·탈퇴·폐기를 잃으면 사용자 의도에 반해 외부 전송이나 로그인이 다시 열린다. ECS non-blocking 로그는 유실될 수 있어 로그로 복구하지 않는다. 미확정 탈퇴를 자동 삭제하면 DB에서 거절된 탈퇴까지 데이터를 지울 수 있다.

| 대안 | 버린 이유 |
|---|---|
| 1: AZ별 NAT 2개 (ADR-0015) | 월 $93.44 + 처리량. 지금 연동에 고정 IP가 필요 없음 |
| 1: interface endpoint로 NAT 대체 | AWS 서비스만 대체하고 외부 API는 못 감. 월 $94.90 |
| 4: 운영자 인증 (access token과 분리) | 새 인증 체계를 만들어야 함 |
| 8-6: 일반 로그로 결정 복구 | 로그 유실 가능 |
| 8-6: 원장 없이 손실 구간 활동 주체 전체 격리 | 복구가 느리고 넓게 막힘. 원장을 거절하면 이 방식이 대안 |
| 8-6: 체크포인트 뒤 일부만 읽기 | 체크포인트보다 먼저 PUT하고 늦게 커밋한 결정을 놓침 |

## 결과
- 확정 항목: D-06·D-22 해소, D-11·D-21 (assertion 제외)·D-13 (RDS) 방향 확정. 인프라 코드·SecurityConfig·CI는 운영 배포 준비 단계에서 구현한다.
- 사용자 결정 대기: D-13 (네트워크 출구), D-21 (assertion), D-23, D-24 (원장·RPO/RTO). ADR-0015는 D-13 결정 뒤 보완 ADR로 바꾼다.
- 런북 2종 (`db-restore.md`·`key-rotation.md`)은 운영 배포 전에 작성한다.
