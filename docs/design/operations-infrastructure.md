# 운영·인프라·보안 설계

- 결정 기록: [ADR-0032](../adr/0032-operations-infrastructure-security.md). 이 문서가 세부 계약이고, ADR은 결정과 근거를 요약한다
- 대상: D-06·D-11·D-13·D-21·D-22·D-23·D-24, DB 복구·삭제 배치·키 운영
- 기준: [단계 5 설계 문서](stage5-analysis-pipeline.md). 가격 = AWS 서울 공식 Price List (2026-11-01 확인), USD, 세금 제외
- "사용자 결정" 표시 항목은 2026-11-01에 모두 결정됐다 (D-13·D-21·D-23·D-24, 원장 보관 기간 = P08). 값을 바꾸면 이 문서와 ADR-0032를 함께 고친다

## B1 네트워크 출구 (사용자 결정 2026-11-01, D-13, ADR-0015 보완)
- 결정: NAT 없이 `api`·`worker` 태스크를 public subnet에 두고 공인 IPv4 (`assignPublicIp`)
  - 보안 그룹: `api` = ALB SG에서 앱 포트만 (WAF 우회 차단), `worker` = 인바운드 0, RDS = 태스크 SG에서 DB 포트만, 관리 포트 8081 비공개
  - RDS는 private subnet, S3는 gateway endpoint (무료). interface endpoint 없음 (ECR·Logs·SSM은 공인 경로)
- 비용 (월 730시간, 출구 증분만):
  | 구성 | 월 |
  |---|---|
  | 태스크 공인 IPv4 3개 / 6개 | $10.95 / $21.90 |
  | Zonal NAT 2개 (공인 IP 포함) | $93.44 + $0.059/GB |
  | interface endpoint 5종 × 2AZ (NAT 대체가 아님, AWS 서비스만) | $94.90 + 처리량 |
- 설정 실수 방지: 인프라 코드 검사에서 태스크 보안 그룹의 인바운드 `0.0.0.0/0`·`::/0` 허용과 `worker` 인바운드 규칙을 거부 (CI 단계)
- 고정 출구 IP: Anthropic·RevenueCat·Apple·Google 일반 인증 계약에는 필수 조건 없음 (2026-11-01 확인). 연동마다 배포 관문에서 계정·키 정책 확인 (예: Google Maps 서버 키 IP 제한). 필요해지면 Regional NAT·출구 프록시 비교

## B2 RDS (D-13 일부)
- Multi-AZ (one standby) 유지. 시작 `db.t4g.medium` $148.19/월 (`db.m7g.large` $342.22/월). gp3·백업·CPU credit 별도
- 배포 전 관문: 서울 PG18.x × `db.t4g.medium` × Multi-AZ 주문 가능 여부를 `DescribeOrderableDBInstanceOptions`로 확인. 안 되면 PG18·Multi-AZ 지원 최소 클래스
- 경보: CPU credit 과금 (Unlimited, $0.075/vCPU·시간), 메모리, DB 부하, 쿼리 지연, Hikari 대기
- RDS Proxy 미도입. 커넥션 예산 = Σ(서비스 최대 태스크 × 배포 중 증가 배수 × Hikari 풀) + Flyway·운영·복구 여유 ≤ `max_connections` − 예약 − 안전 여유. 서비스별 최대 태스크 수는 이 식으로 정함

## B3 SLO (D-11)
- 월 가용성 99.5% (30일 약 216분), 초기 내부 목표
- 서버 처리 SLI (p95): 외부 호출 없는 API 300ms, 분석 접수 500ms (DeviceCheck 왕복은 별도 지표, 사용자 대기에는 포함)
- 사용자 완료 SLI (분리 측정): 업로드 확정 → 결과 저장 → 액션 생성 완료 → 단말 첫 사용 가능 결과. Messages 경로 목표 30초 (첫 결과), Batch는 별도
- 검색 해석 Basic 600ms·Pro 1.5초 (기존 제안)
- 경보 = burn rate. 외부 오류·예산 차단을 분모에서 무조건 빼지 않음. 부하 측정 뒤 조정

## B4 운영자 actuator (D-22)
- `management.server.port` 8081, loopback 바인딩. 수동 조회 = ECS Exec + 관리 포트 읽기 전용 허용 범위 (보안 규칙 별도)
- 앱 포트 = `management.endpoint.health.probes.add-additional-paths=true` (`probes.enabled=true` 명시) → `/livez`·`/readyz` (Boot 4.1.2 문서 확인)
- SecurityConfig: `EndpointRequest.toAdditionalPaths(SERVER, "health")` 공개 matcher, 잘못된 JWT가 health를 막지 않게 기존 공개 요청 예외와 맞춤
- ALB 대상 = 앱 포트, 헬스 체크 `/readyz` 200. liveness에 Claude·RevenueCat 등 외부 의존성 없음
- 지표: OTLP (ADOT 사이드카)

## B5 CI 액션 고정 (D-06)
- full commit SHA + 최소 권한 + Dependabot (`github-actions`, 주 1회, 업데이트 PR 묶어 검토). 긴급 보안 갱신은 주기와 별도

## B6 기기 증명 challenge (D-21)
- `POST /installs/challenge` → 32바이트 난수, 유효 5분, 1회 사용 (DB에 해시·사용 여부). challenge 소비와 설치 생성은 같은 DB 전이. 설치 멱등 재생은 소비한 challenge를 다시 외부 검증하지 않음
- 바인딩 = SHA-256(직렬화 고정 `protocolVersion, operation, platform, challengeId, nonce, trialEvidence`)
  - Android: Play Integrity **Standard** `requestHash` (Classic 혼용 안 함). 서버가 재계산·대조, 앱·패키지·서명 인증서·시간·verdict 검증
  - iOS: App Attest attestation `clientDataHash` (trialEvidence = 이번 DeviceCheck 토큰). 인증서·앱 ID·환경·key ID 검증
- 한계 (명시): 해시는 제출 값의 변조를 막을 뿐, Android ID·DeviceCheck 토큰이 그 물리 기기의 값임을 증명하지 않음. 설치 뒤 새 DeviceCheck 토큰도 등록 해시로 보호되지 않음 → ADR-0029 잔여 위험 유지
- 첫 체험 접수 App Attest assertion = 사용자 결정 2026-11-01 (D-21: 요구, iOS만). 계약은 아래 B6-2

## B6-2 첫 체험 접수의 App Attest assertion (D-21)
### B6-2.E1 대상
- Android는 확대하지 않음 (사용자 결정 2026-11-01). 설치 때 Play Integrity `requestHash`로 체험 기기를 묶는 기존 계약 (B6) 유지
- iOS 접수 중 그 기기의 체험을 **시작해야 하는** 접수: `device_trial_mark` NEW, D-23 "확인 전", `STARTING` 복구. 체험이 시작된 뒤의 접수·Pro 접수·수동 기능은 대상 아님
- 단말은 서버가 challenge를 줄 때만 assertion을 만든다. 생성 실패 = 그 첫 체험만 보류

### B6-2.E2 키 (account 모듈 `device` 행, Flyway)
- 설치 attestation 성공 때 `app_attest_key_id` (유일), `app_attest_public_key`, `app_attest_counter` (0, unsigned 32비트 범위), `app_attest_env`, `app_attest_key_gen` (첫 출시 = 1)
- 범용 재증명·키 교체 API 없음. 키 분실 (앱 재설치·백업 복원 등) = 새 설치 등록 + 기존 DeviceCheck 재설치 정책 (ADR-0029). 기존 설치 데이터 삭제·이용량 초기화를 복구 수단으로 쓰지 않음

### B6-2.E3 흐름 (설계 문서 1.2 보완)
1. 인증·기기 소유 확인 → 기존 멱등 기록의 업무 해시 비교·재생 (최우선)
2. 신규 요청에서 체험 시작이 필요하고 증거가 없으면 = **비저장** 403 `ATTESTATION_REQUIRED` + `challengeId`·`nonce`·`expiresAt`·키 식별 정보. 5d `StartRequired`에서 처음 필요해져도 업무 TX 롤백 뒤 같은 응답
3. 단말은 **같은 멱등 키·같은 본문 바이트**로 다시 보내며 헤더 `App-Attest-Assertion`·`App-Attest-Challenge-Id`·`App-Attest-Nonce` (발급 nonce 그대로)를 붙인다 (모두 멱등 해시에서 제외, ADR-0030 #4와 같은 방식)
4. 서버: nonce 정규 인코딩 검사·디코딩 → 해시를 challenge 행과 대조 → 그 nonce로 E5 서명 입력 재구성 → 암호 검증 (TX 밖) → 짧은 TX에서 `활성 key ID·키 세대·환경·소유 ∧ challenge 미소비·미만료·용도 일치 ∧ counter 증가`를 함께 검사하고 challenge 소비 + counter CAS → 검증 증표를 이 HTTP 요청 안에서 유지 → `recordStart` → 업무 TX. 4→5d 재시도는 증표 재사용 (counter 1회 갱신)
5. 이후 `recordStart`·업무 TX가 실패해도 counter·challenge는 되돌리지 않음. 새 HTTP 재시도는 멱등 기록이 없고 여전히 시작이 필요하면 새 challenge
- Apple 호출 (DeviceCheck) 중에는 DB TX·잠금을 유지하지 않음

### B6-2.E4 challenge
- B6 기반 (32바이트 난수, 5분, 1회, DB에 해시), 용도 `ANALYSIS_TRIAL_START`로 설치용과 분리
- 결합: owner·device·활성 키 세대·멱등 키·본문 바이트 해시·용도
- 발급·검증 속도 제한 (주체·기기), 만료 정리 배치

### B6-2.E5 서명 입력 (구현 고정)
- `clientDataHash` = SHA-256(서명 입력 v1: 고정 필드 순서 `version, purpose, challengeId, nonce, installId, Idempotency-Key, SHA-256(전송 UTF-8 본문 바이트), SHA-256(Device-Check-Token)`, 각 필드 길이 접두 + UTF-8)
- 검증: `rpIdHash` = SHA-256(Team ID prefix + "." + bundle ID), 서명 = SHA-256(authenticatorData ‖ clientDataHash)에 대한 ECDSA 검증과 동등 (라이브러리 내부 해싱 중복 금지), counter 증가
- 헤더 크기 상한, CBOR·base64 형식 오류 = 증거 오류. Swift/Java 공통 검증 벡터를 시험에 포함
- 멱등 해시는 기존 업무 정규화 유지 (assertion 본문 해시와 별개)

### B6-2.E6 증거 오류 (모두 비저장, ADR-0026 저장 규칙의 명시적 예외)
| 상황 | 응답 |
|---|---|
| 증거 없음 (시작 필요) | 403 `ATTESTATION_REQUIRED` + challenge |
| challenge 만료·소비됨·용도 불일치, nonce 누락·변조 (challenge·counter 소비 없음) | 403 `ATTESTATION_REQUIRED` + 새 challenge |
| counter 순서 역전·동시 소비 경합 | 409 `CONFLICT` + `Retry-After` |
| 서명·rpId·key ID·환경 불일치, 형식 오류 | 403 `ATTESTATION_FAILED` |
| 서버에 키 없음 (대역 설치 등) | 403 `ATTESTATION_FAILED` + 새 설치 안내 |
- 거절 직전 멱등 기록 재확인: 같은 키의 완료 결과가 있으면 그것을 재생
- 단말은 첫 시작 시도를 설치별로 직렬화
- 확정 업무 4xx 저장 규칙은 그대로 (이 예외는 증거 오류만)

### B6-2.E7 비용·의존성
- assertion 검증 = 서버 로컬 암호 연산 (이 단계만 Apple 장애와 무관). 첫 시작 전체는 DeviceCheck에 의존 (B7)
- 응답 속도: 첫 시작에만 challenge 1왕복 + 본문 재전송. 평상시 assertion 생성 없음

### B6-2.E8 구현 시 검증 목록
같은 키 동시 재전송, 다른 인스턴스가 DB와 재전송 입력만으로 검증 (nonce 왕복), 낮은 counter 후도착, 4→5d, counter 커밋 뒤 503·프로세스 종료, 변조·만료 challenge, Swift/Java 서명 벡터
- 잔여 위험 유지: DeviceCheck 토큰의 물리 출처, 복수 설치 (ADR-0029)

## B7 DeviceCheck 장애 처리 (사용자 결정 2026-11-01, D-23, ADR-0029 보완)
- 설치 때 조회가 **전송 장애·Apple 5xx**로 실패 = 설치 진행 + 기기 "확인 전" (`TRIAL_STARTED`·`STARTING`과 다른 상태)
- 제공자가 토큰을 거절·형식 오류로 판정, 또는 증명 실패 = 403 유지 (ADR-0029 #18). 요청 필수 필드·플랫폼 모양 오류는 기존 400 계약 유지
- 확인 전 기기는 첫 체험 접수에서 fresh token 조회·기록이 성공해야 체험 시작 (기존 `recordStart` NEW 경로). 접수 때도 조회 실패 = 그 접수만 503
- 효과: Apple 장애 때 새 iOS 사용자가 앱을 설치·사용 가능, 분석 체험만 Apple 복구까지 대기. ADR-0029의 체험 시작 시점은 바꾸지 않음

## B8 DB 복구 런북 (PITR)
1. **모든 writer·외부 전송 격리**: 접수·발급·완료·생성·재제출·알림·배치를 멈추고 구 DB 연결 태스크를 drain·차단 (intake 설정만으로 부족)
2. 복원된 `SENDING` 호출 = 같은 호출 전이로 최대 비용·위험 송신 1회 정산. 복구 조정 중 불명 자동 재호출 잠금. 예산은 호출의 예산 일자·manifest 기준, `ai_spend_day`·주체 일일·기기·`photo_ai_budget` 대조
3. **손실 구간** = 선택한 복원 시각 ~ 구 writer 완전 중단 시각 (논리 오염으로 2시간 전에 복원하면 2시간). 복원 시각·중단 시각·`LatestRestorableTime`을 기록. 원장이 없으므로 제공자 사용량 (Anthropic 콘솔)으로 총비용만 대조. 사진·기기별 횟수는 복원하지 못함 → 영향 접수의 자동 재실행 보류, 그날 남은 비용 허용액을 운영자가 확정. AI 호출별 DB 밖 송신 원장은 두지 않고 손실 구간의 중복 비용을 감수 (사용자 결정: RPO/RTO)
4. 예약: `SUCCEEDED` → 사진별 C/D 전이 확인 (결과 저장 사진 = consume, 삭제 구성원 = release, 단계 5 설계 문서 2.4), `FAILED`·`CANCELLED`·`INELIGIBLE` → 전체 release 확인, 미종료 → 유지 또는 명시 취소. 합계만 맞추지 않고 예약별로 대조. 불일치는 자동 수정하지 않고 격리
5. S3: 생성 시각으로 일괄 삭제하지 않음. 복원된 작업·시도와 맞춰 보고, 모르는 객체는 lifecycle에 맡김. 살아 있는 시도는 단계 5 설계 문서 확정 공통 조건으로 처리
6. **보안 거부 결정 재적용** (사용자 결정 2026-11-01, D-24: S3 원장 사용): 동의 철회·탈퇴·토큰 폐기만 **보안 거부 결정 원장**에 남긴다
   - 저장: S3 객체 1건/결정 (`denials/{yyyy}/{mm}/{dd}/{decisionId}.json`: 결정 ID·주체 ID·종류·범위 (주체 전체 또는 특정 refresh 세션)·복구 세대). 토큰·DeviceCheck 원문·사진 없음. 버킷 = 최소 권한, 버전 관리
   - 순서: 원장 PUT 성공 → DB TX (PUT 뒤 허용 수명 = 요청 제한 시간, 초기 30초, 넘으면 요청 실패) → 커밋되면 결과 표시 객체 `{decisionId}.committed` best-effort PUT → 응답. 원장 PUT 실패 = 503 (DB 미반영, 성공으로 답하지 않음). GRANTED 같은 역방향 결정은 원장으로 복원하지 않음
   - 복구: writer 완전 중단 뒤 **보존된 원장 전체를 열거**해 결정 ID로 멱등 대조 (체크포인트 부분 읽기는 범위 밖에 미확정 결정이 없음을 증명할 수 있을 때만). DB 순번·태스크 시계 순서에 기대지 않음. 적용 전에는 외부 전송 재개 금지
   - 미확정 결정 (결과 표시 없음, DB에도 없음): 동의 철회·토큰 폐기 = 보수적으로 차단 적용. **탈퇴 = 계정·외부 전송 격리까지만**, 물리 삭제는 DB 증거 또는 사용자 재확인 뒤 기존 삭제 흐름 (DB에서 거절된 탈퇴를 지우지 않음)
   - 보관 ≥ 최대 반영 지연 + 허용 최대 복원 과거폭 (RDS 백업 보존 기간) + 여유 → 결정 뒤 1년 (P08 확정, ADR-0033 #10). 일반 진단 로그와 분리, 개인 관련 데이터로 취급
   - 대안 (원장 없음): 손실 구간에 활동한 주체 전체의 외부 전송을 격리하고 수동 확인. 복구가 느리고 넓게 막힘
   - RevenueCat은 REST로 다시 조회 (사진·동의·발송 결과는 복원해 주지 않음)
7. publication 재제출 (단계 5 설계 문서 3.1 규칙), cursor 복구 세대 갱신 (단말의 복원 전 revision을 조용히 받지 않음, 409 `RESYNC_REQUIRED`). 단위별 정합 확인 뒤 단계적으로 writer·전송 재개
- 유니크 제약은 손실 구간에서 사라진 행의 중복까지 막지 못함 (보장 문구로 쓰지 않음)
- 런북 `docs/runbook/db-restore.md`, 분기 1회 복원 연습 (복원 시간 = RTO 근거)

## B9 삭제·재동기화 (단계 9·10)
- tombstone 보관 기간 뒤 오래된 단말 동기화 = 409 `CONFLICT`/`RESYNC_REQUIRED` + retention floor. full sync가 지운 데이터를 되살리지 않음
- 주체 삭제 (탈퇴·비로그인 만료) = 주체 `DELETING` → 신규 작업 차단 → 예약 정산·호출 종료 → 모듈별 멱등 삭제 (결과·작업·생성 행·액션·이벤트·`photo_ai_budget`·벡터·S3 삭제 의도·변경 기록) → cursor floor
- 기기 남용 방지 기록 (`device_trial_mark`)·HMAC 키는 사진 데이터 삭제와 분리 (ADR-0029)

## B10 키 운영
- 운영 JWK Set·HMAC 키 = SSM SecureString. 값 변경은 실행 중 태스크에 반영되지 않음 → 단계마다 api·worker·단독 태스크 재배포 + 키 버전 확인
- JWT: ADR-0030 #6 2단계. 옛 공개 키 제거 = 마지막 옛 서명 인스턴스 종료 + access 최대 수명 + 시계 여유
- Android ID HMAC: 최초 생성 = 가장 낮은 설정 버전 키 HMAC으로 기기 잠금 → 잠금 안에서 전 버전 재조회 → 없을 때만 활성 버전 INSERT. 키 2개 이상이면 `PIK_USAGE_DEVICE_KEY_ACTIVE` 필수. 옛 키는 읽기 키로 유지 (ADR-0029 기동 검사)
- 쓰기 키 전환 전 모든 실행 주체가 새 키 집합을 가졌는지 확인, 롤백 가능한 키 집합 유지
- 유출 대응: 접근 차단 → 새 쓰기 키 → 노출 범위 확인 → 옛 읽기 키 관리. 알려진 후보 ID와 과거 HMAC 대조 위험은 남음 (무단 키 삭제로 기록을 잃지 않음)
- 절차 문서 `docs/runbook/key-rotation.md`
