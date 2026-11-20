# 트랜잭션·재시도·멱등성

근거: 요구사항 `## 트랜잭션과 재시도`.

## 트랜잭션
- 한 DB 트랜잭션 = 같은 저장소에서 함께 확정할 상태만
- 외부 시스템(AI·플랫폼 결제·OS)은 트랜잭션 밖. 분석 (5-2): S3 presign·HEAD·GET·DELETE, Claude 호출 = TX 밖. TX 안 = 자격·세대 재검사와 쓰기만 (URL 서명은 TX가 열려 있으면 실패)

## 외부 작업 4단계
1. 실행 의도 저장
2. 외부 호출
3. 실제 결과 확인
4. 결과 기록

- 중단 시 결과 재대조. 미확인 결과 = `확인 필요` 상태 유지
- 서버 저장과 후속 요청이 분리돼 유실 가능 → 실행 의도를 함께 기록 후 재처리
- outbox = Spring Modulith event publication registry (JDBC), 작업 큐 = 업무 테이블 + `SKIP LOCKED` (ADR-0014). 완료된 발행은 즉시 삭제 (ADR-0026)
- 외부 호출 식별 = `external_call` UNIQUE (`job_id`, `run_token`) (ADR-0030의 `request_key`, 5-2 구현 이름, ADR-0035) = 서버 안 호출 의도 식별·배치 결과 매칭용. 제공자 쪽 중복 과금 차단·동기 응답 재조회를 보장하지 않음. 같은 키로 재호출해도 같은 실행으로 취급된다고 가정 금지 (ADR-0030)
- 성공 결과 이용량 ≠ 외부 호출 비용 상한. 실패 반환이 있으므로 호출 시도·속도·일일 비용 예산을 별도로 둠 (구조 = ADR-0031 #9)
- AI 호출 전송 경계 = 허가 TX (동의·예산·기한·manifest 검사 + `external_call` 삽입) 커밋. HTTP 1회, SDK 재시도 끔 (ADR-0031 #6)
- AI 호출 비용·위험 송신·체험 예약 처리 = `docs/design/stage5-analysis-pipeline.md` 2.5 정산표만. 사용량 없이 최대 비용으로 정산하는 응답은 위험 송신도 소진, 예외 = 401·403·404 (ADR-0031 #7)
- AI 호출 결과 불명 = 최대 비용·위험 송신 소진으로 확정. 자동 재호출은 사진 수명 1회 상한 (설정값, 사용자 결정 D-26, ADR-0031 #12)
- 같은 (작업, `run_token`)의 허가가 있으면 그것을 반환하고 보내지 않음 (임대 회수가 불명으로 정산). 정산 = 호출 행 CAS (`SENDING` → 최종)로 1회
- S3 객체 삭제 대기열 = 시도 행 (업무 표, outbox 아님). DB 단계와 S3 단계 분리: ShedLock 단일 실행 → 잠금 없는 선택 → 삭제 시작 시각 기록 → TX 밖 `DeleteObject` → 읽은 `version` 조건부 확인. 최종 (`DELETE_SENT`) = URL 만료 + 업로드 진행 여유 (30분) 뒤 시작한 삭제 성공만, 이른 삭제는 재예약. `DELETE_SENT` ≠ 물리적 삭제 확인, 최종 보장 = 버킷 수명 주기 3일 (설계 2.9)

## 외부 전송 동의 판정
- 접수 = 그 시점 동의 버전을 접수 행에 기록
- 전송 직전·결과 저장 직전 = 현재 동의 유효 + 기준 버전보다 큰 버전의 비동의 결정 없음. 둘 중 하나라도 어기면 전송하지 않거나 응답을 저장하지 않음 (ADR-0021 #5, ADR-0030)
- 판정과 동의 결정 기록의 순서 = 주체별 트랜잭션 범위 잠금 (결정 기록 = 배타, 판정 = 공유). 구현 = advisory 잠금 (`consent:` 접두사), `AiConsents.decide` 첫 줄 배타, `AiConsents.lockGrantedBasis` = 공유 잠금 + 기준 버전 (`ConsentBasis`, 호출자 TX 필수, ADR-0034)
- 5-2 판정 = `AiConsents.basisHolds(주체, 기준 버전)` (공유 잠금, 호출자 TX 필수): 현재 동의 ∧ 기준보다 큰 버전의 비동의 결정 (`WITHDRAWN`·`DENIED`) 없음. 같은 동의 재저장 = 통과, 철회 뒤 재동의 = 실패. 발급·재생·확정·허가·완료가 사용
- 주체 활성 (`Principals.isActive`) = 동의 공유 잠금 아래 잠금 없는 읽기 (새 잠금 없음)
- 탈퇴 계약 (단계 10에서 구현): 탈퇴 TX는 같은 TX에서 동의 배타 잠금을 잡고 비동의 결정을 기록한 뒤 주체를 비활성으로 바꾼다 → 허가·완료의 `basisHolds`와 직렬화 (탈퇴 뒤 결과 저장 없음, 시험 C-21)

## 멱등성
- 변경 요청 = 작업 식별자 + 데이터 버전
- 같은 작업 재전송 → 수행 단계와 대조, 중복 과금·삭제·등록 금지
- 같은 사진 변경 알림 중복 수신 → 분석·액션·이벤트 중복 생성 금지
- 키 = `Idempotency-Key` (UUIDv7), 범위 = (주체, 키), 보관 7일 (설정값), 탈퇴 시 삭제 (ADR-0023)
- 멱등 기록 = 업무 변경과 같은 트랜잭션에 저장. 비정상 종료 시 함께 롤백 (처리 중 기록 없음)
- 같은 키 동시 요청 = 키 단위 advisory 잠금 (트랜잭션 범위) 대기 → 대기 시간 (설정값, 기본 3초) 초과 시 409 `CONFLICT`/`IDEMPOTENCY_IN_PROGRESS` (재시도 가능, ADR-0026)
- 실행 = `IdempotentExecutor.execute`를 트랜잭션 밖에서 호출 (트랜잭션은 실행기가 시작). 단계가 나뉜 Command (분석 접수) = `lookup` (읽기 TX 사전 조회·해시 비교) → TX 밖 검사 실패 = `reject` (키 잠금 → 기록 재확인 → 있으면 재생, 없으면 저장 대상만 저장) → `commit(Supplier<Outcome>)` (업무 TX, 202 등 상태 코드 저장). `execute` = `commit` 위임 (ADR-0034). 저장 대상 4xx = 업무 트랜잭션 전체 롤백 → 새 트랜잭션에서 잠금·기록 재확인 후 저장, 그 사이 저장된 기록이 있으면 그 기록 반환, 본문이 다르면 `IDEMPOTENCY_KEY_REUSED` (ADR-0026)
- 커맨드 record = 업무 변경이 의존하는 입력 전부 (경로 변수·쿼리 포함). 나중에 추가하는 필드는 null 허용 (해시에서 빠짐)
- 재시도마다 새로 받는 일회성 증거 (DeviceCheck 토큰 등) = 본문·커맨드 제외, 헤더로 받음. 같은 키의 기록이 있으면 증거 검증·외부 호출·예약 없이 재생 (ADR-0030)
- 커넥션 격리 수준 = READ COMMITTED 고정 (`application.yml`, 풀 설정 + 커넥션 초기화 SQL). 멱등 실행기가 의존
- 저장 = 결과가 확정된 2xx·4xx → 같은 응답 반환. 4xx는 업무 변경 롤백 + 멱등 기록만 커밋. 401·`IDEMPOTENCY_IN_PROGRESS`·429 `RATE_LIMITED` (ADR-0024)·`Retry-After`가 있는 4xx·503·처리 중 예외·첫 체험 assertion 증거 오류 (`ATTESTATION_REQUIRED`·409 counter 경합·`ATTESTATION_FAILED`, ADR-0032 #6-2)는 저장 안 함. 저장 대상 4xx를 기록에서 빼려면 `PikException.unstored` (예외 지정은 이 플래그로만, ADR-0034). `IDEMPOTENCY_KEY_REUSED`는 원본 기록 유지
- 200 응답 `items[]`의 재시도 가능한 실패 = 그 대상만 새 키로 재전송
- 같은 키 = 같은 작업의 재전송만. 본문 변경·사용자 재시도 = 새 키 (저장된 4xx는 `retryable`이어도 같은 키면 같은 응답). 같은 키·다른 본문 = 400 `INVALID_INPUT`/`IDEMPOTENCY_KEY_REUSED`. 키 누락 = 400 `INVALID_INPUT`/`IDEMPOTENCY_KEY_REQUIRED`
- 장기 작업 접수 재전송 = 저장 응답 대신 현재 작업 상태 (`ReplayProjection`, 2xx 재생만, 최초 상태 코드 유지. 오류 재생은 저장 본문 그대로). 투영 = SQL 문 1개 (READ COMMITTED에서도 한 스냅샷, 첫 응답·재생·상태 조회 공용, 5-2). 접수 응답·저장 본문에 업로드 URL 없음. URL = 시도 발급 API, 같은 키 재생 = 저장된 시도를 같은 만료로 재서명 (ADR-0031 #5)
- 보관 기간 이후 재전송 = 데이터 버전·`client_ref` 유니크·사진 단위 부분 유니크 (`analysis_job`·`usage_reservation`)로 중복 반영·차감 차단 (ADR-0021·0022·0024)

## 잠금 순서 (단계 5, `docs/design/stage5-analysis-pipeline.md` 0.3)
- advisory 잠금 = 문자열 접두사로 네임스페이스 분리 (`hashtextextended('<접두사>' || id, 0)`): 동의 `consent:`, 주체 접수 `analysis.intake:`, 전역 claim `analysis.claim:` (`analysis.claim:global`, TX 첫 잠금으로만. 다른 잠금을 잡은 채 획득 금지). 멱등 키 잠금은 기존 방식
- 전역 순서 (5-2 개정, ADR-0035) = 멱등 키 → 동의 → 주체 접수 → 주체 이벤트 → 전역 claim → photo → `analysis_request` → `analysis_job` → `analysis_upload_attempt` → 입력 속도 (주체 → 체험 기록) → `external_call` → 주체 일일 송신 → 이용량 할당 → 기기 기록 → `photo_ai_budget` → 일일 비용 → `ai_gate` (BUDGET → CONFIG → TRANSIENT) → 생성 행 → `photo_action`·`event` → `owner_change_counter`
- 접수 업무 TX = 멱등 키 → 동의 (공유) → 주체 접수 → `photo` (id 순 `FOR UPDATE`) → 참조할 기존 `analysis_job` (id 순 `FOR SHARE`, 예약 전 재확인) → 이용량 할당 → 기기 기록 → `owner_change_counter`
- 삽입 위치 예외: 접수·묶음·작업·항목 행은 예약 뒤에 삽입 ("이미 잠근 상위 행 아래에서만 충돌하는 삽입"). `uk_analysis_job_photo_active` 충돌은 주체 접수 잠금 아래에서만 생김. 불변 조건 = `analysis_job`을 삽입하는 모든 경로 (5-2 `SERVER_BATCH` 포함)는 주체 접수 잠금을 먼저 잡음 (V5 주석)
- `analysis_job` 잠금: 이용량 잠금보다 먼저 작업 행을 잠그는 TX (5-2 상태 전이·완료·취소·claim)는 `FOR NO KEY UPDATE`보다 강한 잠금 금지. `FOR UPDATE`·`DELETE`·`id`·`group_id` UPDATE 포함. claim = `FOR NO KEY UPDATE SKIP LOCKED`. 예외 = 주체 접수 잠금을 먼저 잡은 TX
  - 이유: `AlreadyReserved`로 생긴 `IN_PROGRESS` 항목은 미리 잠그지 않은 작업을 FK로 참조 → `KEY SHARE`가 이용량 잠금 뒤에 옴. `KEY SHARE`는 `FOR NO KEY UPDATE`와 충돌하지 않음 (상태만 바꾸는 UPDATE도 같은 등급)
  - 작업 잠금 SQL = 네이티브 (`FOR NO KEY UPDATE [SKIP LOCKED]`). JPA `PESSIMISTIC_WRITE` + `SKIP_LOCKED`는 `FOR UPDATE`를 낼 수 있음
  - 고정 시험 = `AnalysisIntakeConcurrencyTests` C-8 (`FOR NO KEY UPDATE` 전이 = 교착 없음, `FOR UPDATE` 대조 = 교착). claim 변형 = C-10 (5-2)
  - `analysis_job` 밖 표 (`ai_send_daily`·`photo_ai_budget`·`ai_spend_day`·`ai_gate`)는 `FOR UPDATE` 허용. 잠금 SELECT 전에 0 행을 넣어 잠금이 항상 행에 걸림

전이별 잠금 (5-2, 잡는 것만 왼쪽부터. 표의 자원은 바꿀 때만 잠금, 표에 없는 쓰기를 넣으면 표를 먼저 고침. 시험 C-17):
| 전이 | 잠금 |
|---|---|
| 접수 (5-1) | 키 → 동의 공유 → 주체 접수 → photo `FOR UPDATE` → 작업 `FOR SHARE` → 할당 → 기기 → 변경 번호 |
| 시도 발급 | 키 → 동의 공유 → 작업 (id 순) → 시도 → 입력 속도 |
| 확정 (API·회수 배치) | (키) → 동의 공유 → 작업 → 시도 (종료 분기 = + 할당 → 기기 `release`) |
| claim | 전역 claim → 작업 `SKIP LOCKED` → `ai_gate` (반개방 원인, probe 지정 때만) |
| 연기 (토큰 폐기, 송신 없음) | 작업 → `ai_gate` (그 토큰이 probe일 때만) |
| 허가 | 동의 공유 → 대표 photo 공유 → 작업 → 일일 → 기기 (`TRIAL`) → `photo_ai_budget` → 일일 비용 → `ai_gate`. 기존 허가 = 잠금 없는 조회, 통과 = 마지막에 호출 행 삽입 (새 행, 유니크 충돌은 작업 잠금이 막음) |
| 허가 거부 종료 (별도 TX) | 동의 공유 → 대표 photo 공유 → 작업 → 시도 → 할당·기기 (`release`) → 사유 재확인 (기기·`photo_ai_budget`) → `ai_gate` (probe 해제) |
| 완료 (승자) | 동의 공유 → 구성 photo (원접수 `ACCEPTED`, id 순) 공유 → 접수 → 작업 → 시도 → 호출 → 일일 → 할당·기기 (`settle`) → 기기 송신 → `photo_ai_budget` → 일일 비용 → `ai_gate` → 결과 → 변경 번호 |
| 응답 실패 정산 (종료 포함) | 작업 → 시도 (종료 때) → 호출 → 일일 → 할당·기기 (`release`, 종료 때) → 기기 송신 → `photo_ai_budget` → 일일 비용 → `ai_gate` |
| 공통 종료 (호출 없음) | 작업 → 시도 → 할당·기기 (`release`) → `photo_ai_budget` (`INPUT_INVALID`만) → `ai_gate` (probe 해제 때만) |
| 불명 확정 (임대 회수·처리 기한) | 작업 → 시도 (종료 때) → 호출 → 일일 → 할당·기기 (`release`, 종료 때) → 기기 송신 → `photo_ai_budget` → 일일 비용 → `ai_gate` |
| 늦은 응답 조정 | 호출 → 일일 비용 |
| 삭제 확인 (객체 삭제 배치) | 시도 1행만: 읽은 `version` 조건부 UPDATE 1문 (autocommit). 작업 잠금·뒤 잠금 없음 → 순환 없음 |
| 예산 갱신 / 회로 감시 (감시 배치) | 일일 비용 → `ai_gate` BUDGET / `ai_gate` CONFIG 또는 TRANSIENT (한 TX 한 원인, 작업 잠금·probe 해제 없음) |
| 수동 재처리 | 결과 → 변경 번호 |
- 허가 거부 중 종료 판정 (동의·주체·대표·기기 한도·사진 한도) = 허가 TX는 아무것도 바꾸지 않고 롤백 → 첫 TX가 끝난 뒤 별도 종료 TX (`REQUIRES_NEW`로 첫 TX를 멈춘 채 실행 금지). 종료 TX가 자기 잠금 아래 사유를 다시 확인, 사라졌으면 롤백 (`ReasonLifted`) → 새 TX에서 `READY` 연기. 허가 TX가 기기를 잡은 채 할당을 잠그는 경로 없음
- 예산 TX = 순서대로 잠그고 읽기 → 판정 → 변경 (판정 전 카운터 변경 없음, 연기는 카운터 불변)
- 잠금 없이 고른 후보 (배치·HEAD 결과·임대 만료) = 잠금 아래 관측한 시도 ID·토큰·상태를 다시 비교한 뒤에만 전이. 승자 = `RUNNING ∧ run_token 일치 ∧ 처리 기한 전`, 진 쪽은 호출만 정산
- `ai_gate` 상태 전이 = 잠금 아래 `generation` CAS + 1 + probe 비움. probe = (작업, `run_token`), 지정은 세대를 올리지 않음. 토큰을 폐기하는 모든 경로가 같은 TX에서 자기 probe를 비움

## 배치 (ShedLock)
- 모든 인스턴스에 예약, ShedLock이 이름마다 한 번에 하나만 실행. 후보는 잠금 없이 고르고, 대상마다 전이 TX에서 잠금 아래 재비교
- 분석 (5-2): `analysis-upload-recovery` (5분), `analysis-processing-deadline` (5분), `analysis-lease-recovery` (1분), `analysis-input-delete` (1분), `analysis-input-reconcile` (6시간), `analysis-input-rate-cleanup` (1시간), `analysis-gate-monitor` (1분). 주기 = `pik.analysis.*` 설정값
- worker claim 루프는 ShedLock이 아님 (인스턴스마다 폴링, 전역 claim 잠금이 슬롯·주체 상한을 지킴)

## 부분 성공
- 목록 작업은 항목별 `성공·실패·취소·확인 필요` 반환
- 재시도 대상에서 이미 성공한 항목 제외

## 동기화
- 로컬 저장 성공 ≠ 서버 보관 성공. 상태 분리
- 변경 번호 (`change_seq`) = `ChangeSequence.next`·`nextBlock(owner, n)` (호출자 트랜잭션 필수, 커밋 순서대로 보임, 발급 전 `EntityManager.flush()`). DB 시퀀스 금지 (ADR-0026, ADR-0034)
- `photo` 생성·`source_change_seq` 증가 = 업무 TX 마지막에 `PhotoObservations.issueChangeSeqs`로 발급 (빠지면 커밋 실패). `observe`는 TX당 1회, 중첩 TX (`REQUIRES_NEW`)에서 호출 = 즉시 실패. 관측은 `photo.version`을 올리지 않음 (ADR-0034)
- 외부 앱 열림 ≠ 통화·구매·예매 완료. 확인 가능한 결과만 반환

## 구독·결제
- 클라이언트 보고만으로 권한 부여 금지. 플랫폼 검증된 거래·유효 상태 기준
- 체험·Pro 제한은 신규 접수에만 적용. 이미 허용된 분석의 결과 생성은 재집계 금지
- 이용량 = 접수 시 대표+구성 수만큼 예약, 분석 실패 시 반환, 같은 사진 재분석 미차감 (수정된 사진도 재분석·재차감 없음, ADR-0024). 체험은 기기·계정 양쪽 기록 (ADR-0021)
- 결과를 만든 체험 칸은 사진 삭제·교체와 관계없이 반환 안 함 (ADR-0024)
- 분석 성공 정산 = 사진별 `usage.settle(principal, job, C, D)` 1회 (결과 저장 사진 C = consume, 처리 중 삭제된 구성원 D = release). 작업 전체 consume/release 순차 호출 금지. 실패·취소·`INELIGIBLE` = 전체 반환 (ADR-0031 #8)
- 구매·복원 직후 = 서버가 RevenueCat REST로 즉시 재조회. RevenueCat 장애로 갱신 미확인 = 확인될 때까지 Pro 유지 (FR-B-05 확인된 만료만 제한). `TRANSFER` (`app_user_id` 없음, 받는 계정 쪽으로 1회) = `transferred_from`·`transferred_to` 계정을 모두 REST로 재조회해 반영, 계정 데이터는 이동 없음 (ADR-0024)
