# ADR-0029: 무료 체험 이용량 구현 방식 (단계 4)

- 상태: 채택
- 날짜: 2026-10-29
- 관련: ADR-0020, ADR-0021 #3·8, ADR-0022, ADR-0024 #1·4-1·6-2, ADR-0027, ADR-0028, FR-B-01, 브랜치 `feature/FR-B-01-usage`

## 맥락
- FR-B-01은 최초 1회 100장 (대표·구성 합계)을 주고 월별로 초기화하지 않으며, 한도·잔여량을 숨기고, 반환으로 실제 분석 진행과 구독 안내 필요 여부를 요구한다.
- ADR-0021은 접수할 때 예약하고 실패하면 반환하며, 체험 이용량을 기기와 계정 양쪽에 기록해 하나라도 소진하면 체험을 주지 않는다고 정했다. ADR-0020은 재설치에 체험을 다시 주지 않고, iOS는 DeviceCheck, Android는 ANDROID_ID로 판별한다고 정했다. ADR-0024는 결과를 만든 체험 칸을 반환하지 않고 사진 단위로 한 번만 센다고 정했다.
- 구현 방식은 남아 있었다. 모듈 위치, 요청을 보낸 기기를 서버가 아는 방법, 기기 판별 값을 받는 시점, 실제 Apple 연동 여부, DeviceCheck 2비트로 표현할 상태, 남은 체험보다 큰 묶음의 처리다.
- DeviceCheck 토큰은 원문 저장이 금지되어 있고 (`data-retention.md`), Apple 문서는 토큰을 일시적 (ephemeral)이라고만 설명한다. 서버가 나중에 비트를 바꾸려면 단말이 그때 새 토큰을 보내야 한다.

## 결정
### 시작 보고에서 사용자가 확인한 항목 (2026-10-29)
| # | 항목 | 결정 |
|---|---|---|
| 1 | 모듈 위치 | 새 `usage` 모듈에 둔다 (`shared`만 의존). `account`는 설치 등록 때 `TrialDevices`를, 분석 접수 (단계 5)는 `TrialUsage`를 호출한다 |
| 2 | 요청을 보낸 기기 | access token에 `did` (기기 ID) 클레임을 넣고 `CurrentPrincipal.deviceId`로 받는다. 동의 결정에 `ai_consent.device_id`를 기록한다 |
| 3 | 기기 판별 값을 받는 시점 | 설치 등록 요청 (`POST /installs`)의 `trialDevice`로 받는다 |
| 4 | 외부 연동 수준 | DeviceCheck는 인터페이스와 가짜 구현만 두고 `local`·`test` 밖에서는 기동을 실패시킨다. ANDROID_ID HMAC은 실제로 구현한다 |
| 5 | iOS 재설치 | DeviceCheck에 체험 시작 기록이 있으면 일부만 썼어도 체험을 주지 않는다 |
| 5-1 | 시작 기록 시점 | 첫 체험 접수 때 단말이 보낸 새 DeviceCheck 토큰으로, 접수 트랜잭션 전에 기록한다 (사용자 선택. 시작 보고의 "커밋 뒤 outbox로 갱신"은 토큰을 저장해야 해서 버렸다) |
| 6 | 남은 체험보다 큰 묶음 | 묶음 (대표+구성) 전체가 남은 체험 안에 들어갈 때만 예약한다 |
| 7 | 범위 | 체험 (`TRIAL`)만 구현한다. Pro 한도는 P03이 정해진 뒤 구독 (단계 8)과 함께 추가한다 |
| 8 | 노출 | 모듈 공개 서비스만 두고 HTTP API는 추가하지 않는다 |
| 9 | 로그인 때 이어받기, 재가입 판별 | 계정 로그인 (FR-A-01) 구현 때 한다 |

### 구현하면서 정한 관례 (Claude 제안)
| # | 항목 | 결정 |
|---|---|---|
| 10 | 설치 요청 | `trialDevice: {androidId?, deviceCheckToken?}`에 플랫폼의 값 하나만 보낸다. 값이 없거나 비었거나 다른 플랫폼 값이 있으면 400 `INVALID_INPUT` / `TRIAL_DEVICE_INVALID`, 객체가 없으면 400 `VALIDATION_FAILED`다. 처리 순서는 형식 검사 → 기기 증명 → DeviceCheck 조회 (트랜잭션 밖) → 주체·설치·기기·체험 기록 저장 (한 트랜잭션)이다 |
| 11 | DeviceCheck 오류 | Apple이 토큰을 받지 않으면 403 `FORBIDDEN` / `DEVICE_CHECK_FAILED`, 연결할 수 없으면 503 `TEMPORARY_FAILURE` / `DEVICE_CHECK_UNAVAILABLE` (재시도 가능)이다. 실제 연동 때 조회 실패를 어떻게 다룰지는 D-23에서 정한다 |
| 12 | HMAC 키 | HMAC-SHA256, 키 32바이트 이상. `PIK_USAGE_DEVICE_KEYS` = `<버전>:<base64 키>`를 쉼표로 나열하고 가장 큰 버전이 현재 키다. 찾을 때는 모든 버전으로, 새로 만들 때는 현재 버전으로 계산한다. 이전 버전 키는 그 버전으로 만든 기록이 남아 있는 동안 지우지 않는다 (재계산은 구현하지 않음). 비어 있으면 `local`·`test`에서만 임시 키를 쓴다 |
| 13 | 기기 기록 생성 | Android는 (플랫폼, 키 버전, HMAC) 부분 유니크와 `ON CONFLICT DO NOTHING` 뒤 다시 읽기로, 동시에 설치해도 같은 기록을 쓴다. iOS는 설치마다 새 기록을 만들고, DeviceCheck에 시작 기록이 있으면 `device_check_state` = `TRIAL_STARTED`, `exhausted_at` = 설치 시각으로 둔다 |
| 14 | DeviceCheck 비트 | bit0만 쓴다 (체험 시작). 시작 기록만으로 체험이 없으므로 소진을 따로 기록하지 않는다. `device_check_state` = `STARTING` (Apple에 쓰기 직전) / `TRIAL_STARTED`. ERD의 `TRIAL_EXHAUSTED` 상태를 뺐다 |
| 15 | 한도 기록 | `usage_quota.limit_count`와 `device_trial_mark.limit_count` (ERD에 열 추가)에 행을 만들 때의 설정값 (`pik.usage.trial.limit`, 기본 100)을 기록한다. 설정을 바꿔도 기존 행은 그대로다. DB CHECK가 `reserved + consumed ≤ limit`과 음수 금지를 보장한다 |
| 16 | 잠금 순서 | 주체의 할당량 행 (없으면 `ON CONFLICT DO NOTHING`으로 만든 뒤 `SELECT … FOR UPDATE`) → 기기 기록 행 순서로 잠근다. 예약·완료·반환이 모두 이 순서를 따른다. 완료·반환은 할당량을 잠근 뒤 예약 행을 읽어, 같은 작업을 두 번 처리하지 않는다. 트랜잭션 하나에서는 주체 하나·기기 기록 하나만 다룬다 (어기면 `IllegalStateException`, 코드 검토 반영) |
| 17 | 공개 API | `TrialDevices.identify` (트랜잭션 밖) → `register` (호출자 트랜잭션 필수). `TrialUsage.recordStart(주체, 기기 기록, 사진 수, 토큰)` (트랜잭션 밖) → `reserve`·`consume`·`release` (호출자 트랜잭션 필수). `reserve` 결과 = `Reserved` / `NotCovered` (구독 안내 필요) / `AlreadyReserved` (사진별 작업 ID) / `StartRequired` (iOS 시작 기록 없음, 기록 뒤 다시 예약). `consume`·`release`는 이번에 처리한 사진 수를 돌려준다 (반환된 작업의 늦은 결과 = 0). 반복해도 바뀌지 않고, 완료된 예약은 반환하지 않는다 |
| 18 | 시작 기록 절차 | `recordStart`는 iOS 기록이 아직 없고 기기·주체 모두 접수할 사진 수만큼 체험이 남았을 때만 Apple을 호출한다. 토큰이 없으면 400 `INVALID_INPUT` / `DEVICE_CHECK_TOKEN_REQUIRED`다. 먼저 비트를 조회해, 이 설치를 등록한 뒤 다른 설치가 시작했으면 이 기록을 체험 없음 (`TRIAL_STARTED` + `exhausted_at`)으로 둔다. 비어 있으면 `STARTING`을 저장하고 비트를 쓴 뒤 `TRIAL_STARTED`로 바꾼다. Apple이 토큰을 거부하면 (403) `STARTING`을 되돌리고, 그 밖의 실패는 `STARTING`을 남겨 재시도가 조회 없이 다시 쓴다 (자기가 쓴 비트를 다른 설치의 시작으로 오인하지 않도록). Apple 기록 뒤 접수가 실패해도 체험을 시작한 것으로 본다 (코드 검토 반영) |
| 19 | 스키마 | V4 (`usage_quota`, `device_trial_mark`, `usage_reservation`). 같은 모듈 안에서는 FK를 둔다 (예약 → 할당량, 예약 → 기기 기록). 다른 모듈의 ID (주체·사진·작업)에는 FK를 두지 않는다. `period_key`는 체험 고정값 `ONCE`다. 완료·반환 조회용 `ix_usage_reservation_job` (`principal_id`, `job_id`)과 플랫폼별 열 CHECK를 둔다 |
| 20 | 토큰 | `did` = 기기 ID, UUID 필수. 없거나 형식이 틀리면 401 `ACCESS_TOKEN_INVALID`다. 익명 주체는 설치 때 만든 기기 하나의 ID를 받는다 |
| 21 | HMAC 키 버전 검사 | 저장된 Android 기록이 설정에 없는 키 버전을 쓰면 기동을 실패시킨다 (`DeviceKeyCheck`). 키를 지워 기기를 잃고 체험을 다시 주는 사고를 막는다 (코드 검토 반영) |
| 22 | Android ID 형식 | 앞뒤 공백을 지우고 소문자로 바꾼 뒤 16진수 1~16자리만 받는다 (64비트 값). 아니면 400 `TRIAL_DEVICE_INVALID`. 원문 값을 담는 record의 `toString`은 값을 가린다 (코드 검토 반영) |

## 근거
- 1: `domain-boundaries.md`가 '체험·이용 자격'을 별도 aggregate로 둔다. `usage`가 `account`에 의존하지 않으므로 `account` → `usage`, `analysis` → `account`·`usage` 방향으로 순환이 없다. 기기와 체험 기록의 연결 (`device.trial_mark_id`)은 `account`가 가지고, 분석 접수가 꺼내 넘긴다.
- 2: 토큰은 설치마다 발급되므로 서버가 이미 아는 값이다. 헤더로 받으면 위조할 수 있어 요청마다 소유를 확인해야 한다. 주체에서 유도하면 계정에 기기가 여러 대일 때 맞지 않는다.
- 3: 재설치는 새 설치 등록이고, 기기 증명도 이때 한다. 별도 API는 단말이 호출을 빠뜨리면 판별하지 못한다.
- 4: ADR-0027의 기기 증명 대역과 같다. DeviceCheck에는 Apple 키와 실제 기기가 필요하다.
- 5: DeviceCheck는 기기당 2비트라 남은 장수를 기록하지 못한다. 소진만 막으면 99장을 쓰고 재설치하기를 반복해 체험을 무제한으로 받는다. Android는 HMAC으로 같은 기록을 찾아 남은 장수를 이어 간다.
- 5-1: 토큰을 저장할 수 없으므로 비트를 바꾸는 시점에 단말이 새 토큰을 보내야 한다. 설치 때 기록하면 체험을 쓰지 않고 재설치한 사용자도 체험을 잃는다. 접수 트랜잭션 전에 기록하므로 외부 호출이 DB 트랜잭션에 들어가지 않는다.
- 6: "100장"을 그대로 지키고 DB 제약으로 초과를 막는다. 일부만 예약하면 묶음의 구성 사진 중 일부만 결과를 받는다. 잔여량은 숨기므로 쓰이지 않은 몇 장은 사용자에게 보이지 않는다.
- 13: 같은 기기의 동시 설치가 유니크 위반으로 설치 전체를 실패시키지 않게 한다.
- 15: 기기 기록도 한도를 행에 두어야 같은 CHECK로 동시 예약의 초과를 막을 수 있다.
- 16: 할당량 행 잠금이 주체의 예약 변경을 줄 세우므로, 사진 중복 확인과 한도 확인이 경합 없이 이루어진다. 여러 주체가 한 기기 기록을 함께 쓸 때도 잠금 순서가 같아 교착이 없다.
- 16: 호출 하나 안에서는 순서가 지켜지지만, 두 주체 (또는 두 기기 기록)를 한 트랜잭션에서 다루는 트랜잭션 두 개가 서로 반대 순서로 잠그면 교착이 생긴다 (코드 검토에서 재현). 단계 5의 일괄 처리가 이를 어기면 테스트에서 바로 드러나도록 검사한다.
- 18: 기록 전에 조회하지 않으면, 같은 기기에서 시작 전에 설치를 여러 번 등록해 둔 뒤 설치마다 체험을 받을 수 있다 (코드 검토에서 재현). 사진 1장 기준으로 판단하면 묶음이 들어가지 않아 접수되지 않아도 비트가 써져, 0장을 쓴 기기가 재설치 때 체험을 잃는다. 예약이 시작 기록 없는 iOS 기기를 거부하므로 단계 5가 `recordStart`를 빠뜨려도 남용이 생기지 않고, 반환 경합으로 생기는 경우는 `StartRequired`로 알려 503이 되지 않는다.
- 21: 키 버전을 설정에서 빠뜨리면 그 기기들이 조용히 체험을 다시 받는다.

| 대안 | 버린 이유 |
|---|---|
| `account` 모듈 안에 둠 | 분석·클릭 집계·구독이 모두 계정 모듈에 의존 |
| 기기 ID를 요청 헤더로 받음 | 위조 가능, 요청마다 소유 확인 필요 |
| 첫 체험 때 별도 API로 기기 판별 | 호출 누락 시 판별 불가 |
| Apple DeviceCheck 지금 구현 | Apple 키·실제 기기 필요, 테스트 불가 |
| iOS 소진 기록만 체험 차단 | 재설치 반복으로 체험 무제한 |
| 커밋 뒤 outbox로 비트 갱신 | 토큰 원문을 저장해야 함 (금지) |
| 설치 때 비트 기록 | 쓰지 않고 재설치해도 iOS만 체험을 잃음 |
| 1장이라도 남으면 묶음 전체 예약 | 100장 초과, DB 제약으로 상한 보장 불가 |
| 남은 만큼 일부 예약 | 묶음 일부만 결과를 받음 |
| Pro 한도 함께 구현 | P03 값 미정 (하드코딩 금지), 구독은 단계 8 |
| 기기 기록 한도 = 현재 설정값 | 설정 변경 때 할당량 행과 기준이 갈리고, DB CHECK를 둘 수 없음 |
| 소진 비트 (bit1) 기록 | 시작 기록만으로 판단하므로 쓸 곳이 없고 Apple 호출만 늘어남 |
| 조회 없이 바로 비트 기록 | 시작 전에 등록해 둔 여러 설치가 모두 체험을 받음 |
| `STARTING` 없이 조회 후 기록 | 응답을 잃은 쓰기를 재시도할 때 자기 비트를 보고 자기 체험을 잃음 |
| 주체가 여럿인 트랜잭션은 문서로만 금지 | 교착은 운영 부하에서만 드러남 |
| 이전 키로 찾으면 현재 키로 다시 해시 | 다시 설치하지 않은 기기는 여전히 이전 키에 남아 키를 지우는 순간 잃음 |

## 결과
- V4 마이그레이션이 `usage_quota`, `device_trial_mark`, `usage_reservation`을 만든다. ERD의 `device_trial_mark`에 `limit_count`를 추가하고 `device_check_state`에서 `TRIAL_EXHAUSTED`를 뺐다.
- 코드: `usage` 모듈 (엔티티·저장소·`TrialDevices`·`TrialUsage`·HMAC 키·DeviceCheck 대역), `account` 설치 등록 (`trialDevice`, `device.trial_mark_id`), 토큰 `did`, `ai_consent.device_id`.
- 새 비밀 값 `PIK_USAGE_DEVICE_KEYS` (`.env.example`). 운영 전에 SSM에 등록한다 (ADR-0015).
- 단계 5 (분석 접수): 요청 기기 (`CurrentPrincipal.deviceId`)와 주체로 찾은 기기 행의 `trial_mark_id`를 `account`에서 받아 넘긴다 (입력 ID 사용 금지). `trial_mark_id`가 없는 기기의 처리도 이때 정한다. iOS 접수 요청에는 DeviceCheck 토큰 필드를 두고, 접수 트랜잭션 전에 접수할 사진 수로 `recordStart`를 호출한다. 작업 행과 같은 트랜잭션에서 `reserve`, 결과 저장 때 `consume`, 실패·취소·대상 아님에서 `release`를 호출한다. `NotCovered`면 구독 안내 필요로 응답한다. `consume`이 0이면 (반환된 작업의 늦은 결과) 결과를 버릴지 정한다. 시간 초과 반환 같은 일괄 처리는 주체·기기마다 트랜잭션을 나눈다 (#16).
- 단말이 다른 기기의 DeviceCheck 토큰을 보내면 그 기기에 비트가 기록되고 자기 기기는 기록되지 않는다. ANDROID_ID도 단말이 보낸 값이다. 둘 다 기기 증명과 묶는 방법을 실제 기기 증명 구현 때 정한다 (D-21).
- 남는 위험: Apple에는 비교 후 쓰기가 없어, 같은 기기의 두 설치가 동시에 처음 시작하면 둘 다 비트가 비었다고 보고 체험을 받는다. App Attest 위험 지표 등 기기 증명 쪽 대책과 함께 본다 (D-21).
- 알려진 한계: Android 다중 사용자·업무 프로필은 ANDROID_ID가 달라 체험을 따로 받는다 (공장 초기화와 같은 한계, ADR-0020).
- iOS 기기 기록은 설치마다 한 행이 생기고 탈퇴 후에도 남는다. 보관 기한은 P08·탈퇴 (단계 10)와 함께 정한다.
- 실제 DeviceCheck 연동 (Apple 키, 조회 실패 시 설치를 503으로 끝낼지)은 운영 배포 전에 한다 (D-23). 그 전에는 `local`·`test` 밖에서 기동이 실패한다.
- 계정 로그인 (FR-A-01) 구현 때: 새 계정의 게스트 이용량 이어받기 (ADR-0021 #8), 재가입 판별 (`provider_identity_mark`), 로그인 기기의 `trial_mark_id` 연결.
- 3회 클릭 집계 (`trial_click`, `usage_quota.guided_at`)는 단계 6이다.
- 출처: 1~9는 시작 보고에서 사용자가 확인했다 (모두 Claude 추천안, 5-1은 시작 보고 뒤 토큰 저장 금지를 확인하고 다시 물어 사용자가 고름). 10~22는 Claude가 구현·코드 검토 반영 중 정한 관례다 (16·17·18은 검토 반영으로 바뀜, 21·22는 검토 반영으로 추가).
