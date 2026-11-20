# ADR-0035: 분석 업로드·실행·결과·정산 구현 방식 (단계 5-2)

- 상태: 채택 (착수 = 사용자 선택, #1~#39 구현 관례 = Claude 판단 + Codex 검토, ADR-0034와 같은 방식). 운영 값·D-07 측정은 미결
- 날짜: 2026-11-20
- 관련: FR-I-03, FR-I-01·02 (후속), ADR-0024 #1·#4-1·#6-3, ADR-0026, ADR-0027, ADR-0029, ADR-0030 #1, ADR-0031 (#5~#16, #18, #20), ADR-0032 #1·#8, ADR-0033 #5·#6·#10, ADR-0034 (#3, #13, #14, #37, 5-2 조건), 설계 문서 `docs/design/stage5-analysis-pipeline.md` 0.3·1.2·2·4.3, `docs/design/operations-infrastructure.md` B11, 브랜치 `feature/FR-I-03-analysis-run`, 커밋 `377dc41`~`09ef949`

## 맥락
- ADR-0031과 설계 문서가 5-2 (업로드·실행·결과·정산) 계약을 정했다. ADR-0034가 5-2 조건 (재생 투영 한 스냅샷, claim 잠금 등급, `AlreadyReserved` 종료 작업 해석, 5c 판정 4종·재사용 열·`retry_at`·`basisHolds`·`settle`·manifest 확장, 구성원 = 원접수 `ACCEPTED`)을 남겼다.
- 이미 정해진 사용자 결정 (다시 묻지 않음): 모델 = Haiku 5.5 (D-07로 재결정), 모델과 무관한 계약, PR 5-1/5-2/5-3 (5-2는 PR 1개), 체험 첫 실행 Messages (D-25), 불명 = 사진마다 자동 재호출 1회 (D-26), presigned PUT + 작업당 발급 5회 (ADR-0031 #5), 송신 경계 = 허가 TX 커밋 (#6), 정산표 (#7, 예외 = 401·403·404), 사진별 `settle` (#8), 단말 OCR 본문 미전송 (D-09, ADR-0033 #6), 임시 이미지 안내 (P08, ADR-0033 #10), `pik.analysis.intake-enabled=false` 유지 (5-3 병합 뒤 켬).
- 설계에 없던 것: 전이별 잠금 표, 관문 (`ai_gate`) 상태 전이 (세대·probe), 객체 최종 삭제 규칙, 정산표 밖 HTTP 상태, 입력 속도 저장소 (설계의 "기존 요청 속도 제한 저장소"는 없었음).

## 결정
### 결정 기록
| 단계 | 내용 | 결과 |
|---|---|---|
| 계획 | Claude가 구현 계획 작성 (`.omc/plans/stage5-2-analysis-run.md`). Codex 검토 R1 24건 → R2 9건 → R3 4건 → R4 "구현 착수 가능, 미해결 0건". 모든 발견을 Claude가 판정 (계획 §17~§20) | 수용. R1-21·R1-17 잔여 = 방법 변경, R3-04 = 방법 선택, R1-23 = 일부 수용 |
| 착수 | Claude 추천 = 구현 시작 (PR 1개, 검토점 A = 순서 1~4 뒤, 검토점 B = 순서 8 뒤, 접수 꺼짐 유지, D-07 측정 제외), 사용자 선택 = 구현 시작 | 사용자 결정 |
| 검토점 A | Codex 코드 검토 `b5fdf52..fd3f851`: blocker 0, major 3, minor 2. 5건 모두 수용·수정 (`2c4c066`). 확인 재검토 = 모두 해결, 새 결함 없음, "순서 5 진행" | 기록 `.omc/codex/stage5-2-code-review-a.md` |
| 검토점 B | Codex 코드 검토 `2c4c066..06b8b11`: blocker 0, major 3, minor 3. 6건 모두 수용·수정 | 기록 `.omc/codex/stage5-2-code-review-b.md` |

| 검토점 A | 발견 | 수정 (`2c4c066`) |
|---|---|---|
| A-01 (major) | `TRIAL` 확정에 예약이 없으면 저장된 409 → 작업 `AWAITING_UPLOAD`·객체 `RETAINED`가 회수 배치까지 남음 | 공통 종료 `FAILED(RESERVATION_MISSING)` (새 실패 코드, V6 CHECK. V6 미병합이라 V7 없이 V6 수정), API·회수 배치 같은 경로, 200 종료 응답 |
| A-02 (major) | 상태 조회 `jobs[]`가 항목을 거쳐서만 작업을 찾음 → 항목 90일 정리 뒤 `SUCCEEDED` 작업이 사라짐 (R2-08) | `j.request_id = r.id` ∪ 항목이 참조한 작업, SQL 문 1개 유지 |
| A-03 (major) | presign 유효 시간을 한 시각으로 계산, SDK는 더 늦은 시각에 더함 → URL이 `url_expires_at`보다 오래 삶 | 서명 만료 ≤ `url_expires_at` 검사, 새 시각으로 최대 3회 재서명, 실패 = 503 `STORAGE_UNAVAILABLE` |
| A-04 (minor) | `upload.max-attempts` 상한 없음 (V6 CHECK는 5까지) | 기동 검사 1..5 |
| A-05 (minor) | `upload.url-ttl` 하한 없음 (`PT1S`면 발급이 카운터만 쓰고 서명 실패) | 1분 (재생 최소 10초 + 서명 여유 50초) ~ 7일 |

| 검토점 B | 발견 | 수정 |
|---|---|---|
| B-01 (major) | `maxRetries(0)`에도 OkHttp 4.12가 503 + `Retry-After: 0` 뒤 재송신, 307·308은 POST 본문째 다시 보냄 → 허가 1개에 송신 2번 | SDK 인터셉터 (`Interceptor.syncOnly`) = `RequestOptions.followRedirects(false)` + 반복 불가 본문 (OkHttp `isOneShot`). 연결 실패·408 재시도는 SDK가 이미 끔 (`retryOnConnectionFailure(false)`). 3xx = `UNKNOWN`, 503 `Retry-After` 유지 |
| B-02 (major) | 주체별 순번이 창보다 먼저 자르므로 "후보 < 창"이 전부 본 것이 아님 → 앞 후보가 잠기면 뒤 작업을 못 잡음 | 슬롯이 남고 직전 회차에 후보가 있으면 `claim-rounds`까지 계속 (본 ID 제외·주체 남은 상한 재계산 유지) |
| B-03 (major) | 응답 정산이 작업 잠금 전에 `now`를 읽음 → 잠금 대기 중 처리 기한이 지나도 승자 | 작업 잠금 뒤 `now` (완료 TX와 같음). 같은 문제인 허가 TX (처리 기한·송신 여유 판정)도 고침 |
| B-04 (minor) | 늦은 응답 조정이 행의 정산 비용 (불명 = 최대, 429 = 0)을 씀 | 사용량 × 호출 manifest 단가 (입력·캐시 생성·캐시 읽기·출력, 마이크로달러 올림) = `CallOutcome.usageCostMicros` |
| B-05 (minor) | 2xx 본문이 끊기면 빈 `NoAnswer` → HTTP 상태·`request-id` 유실 | `NoAnswer`가 상태·`request-id`를 지녀 호출 행에 기록 (`responded_at` 포함) |
| B-06 (minor) | STATUS·이 문서가 ERD 반영을 미완료로 적음 | `49ecbe7` 완료로 고침 |

### ADR-0034 5-2 조건 충족
| 조건 | 방법 |
|---|---|
| 재생 투영 한 스냅샷 | 투영 = SQL 문 1개 (항목·묶음·작업 JSON 하위 조회). 첫 응답·재생·상태 조회 공용, READ COMMITTED에서도 한 문장 스냅샷 (REPEATABLE READ TX 불필요) |
| claim·작업 잠금 등급 | 네이티브 `FOR NO KEY UPDATE OF j SKIP LOCKED` (창 함수 하위 조회에 잠금절 없음). 시험 C-10 (`FOR UPDATE` 대조 = 대기) |
| `AlreadyReserved` 종료 작업 해석 | 항목 `IN_PROGRESS` + 그 작업은 종료 (`phase` 계산에서 `DONE`). 설계 경로로 생기지 않는 방어 분기로 `api-contract.md`에 기록 |
| #13·#14·`retry_at`·`basisHolds`·`settle`·manifest 확장 | 5c 판정 4종, V6 열·CHECK, `AiConsents.basisHolds`, `TrialUsage.settle`, manifest 내용 확장 (새 ID) |
| 구성원 = 원접수 `ACCEPTED`만 | 완료 TX 구성원 스냅샷 (`IN_PROGRESS` 참조 항목 제외, D-28 O2) |

### 구현 관례 (Claude 판단, Codex 검토)
| # | 항목 | 결정 |
|---|---|---|
| 1 | 잠금 순서 개정 | 설계 0.3 개정: 주체 일일 송신을 이용량 할당 앞으로, 시도·입력 속도·`ai_gate` 위치 추가. 전이별 잠금 표 (`consistency-rules.md`) = 계약, 표 밖 쓰기 금지 (시험 C-17) |
| 2 | 비작업 표 잠금 | `ai_send_daily`·`photo_ai_budget`·`ai_spend_day`·`ai_gate`는 `FOR UPDATE` 허용 (금지는 `analysis_job`만). 잠금 SELECT 전에 0 행 삽입 → 잠금이 항상 행에 걸림 |
| 3 | 완료·응답 실패 TX의 시도 | 시도 쓰기 = 작업 바로 뒤 (전역 순서 위치) |
| 4 | 허가 거부 종료 | 허가 TX는 바꾸지 않고 롤백 → 첫 TX가 끝난 뒤 별도 종료 TX (`REQUIRES_NEW` 금지). 종료 TX가 자기 잠금 아래 사유 재확인, 사라졌으면 `ReasonLifted`로 롤백 → 새 TX에서 `READY` |
| 5 | 삭제 확인 | 읽은 `version` 조건부 UPDATE 1문 (autocommit), 작업 잠금 없음. 시도 행만 잠그고 뒤 잠금이 없어 순환 없음 |
| 6 | 탈퇴 계약 (K-8) | 주체 활성 = 동의 공유 잠금 아래 잠금 없는 읽기. 단계 10 탈퇴 TX = 같은 TX에서 동의 배타 잠금 + 비동의 결정 기록 → 주체 비활성 (새 잠금 없음, 시험 C-21) |
| 7 | 버킷·엔드포인트 | 버킷·리전 = 모든 프로필 기동 필수. `upload.endpoint` (S3Mock) = `local`·`test`만 (ADR-0027 방식) |
| 8 | presigned PUT | 서명 만료 ≤ `url_expires_at` (A-03). Content-Type `image/jpeg` 상수. 응답 만료 2개: `item.expiresAt` = 시도 고정, `upload.expiresAt` = 서명 (더 늦지 않음). 남은 유효 10초 미만 = 발급·재생 안 함 |
| 9 | 업로드 설정 | `url-ttl` 1분~7일, `max-attempts` 1..5 (A-04·A-05). 새 설정 `upload.not-found-retry-after` (PT5S, `UPLOAD_NOT_FOUND`의 `Retry-After`) |
| 10 | S3 호출 | `getIfMatch` = 최대 `maxBytes`까지만 읽음. 없는 키 삭제 = 성공, 버킷 없음 404 = 실패. `upload.storage-call-timeout`·`storage-attempt-timeout` + SDK 표준 재시도, 항상 TX 밖 |
| 11 | 확정 API | 요청 기기 확인 (접수와 같음, B6-2.E3). HEAD = 관측값으로 실행기 안 판정 (R2-07). 늦은 업로드 (`url_expires_at` 뒤 저장) = 그 시도만 `REJECTED(LATE_UPLOAD)`/`UPLOAD_INVALID`, 작업 유지. 예약 없음 = `FAILED(RESERVATION_MISSING)` (A-01, API·회수 배치 같은 경로) |
| 12 | 발급 사유 | 같은 작업 2번 = 400 `DUPLICATE_ANALYSIS_JOB` (비저장) |
| 13 | 상태 조회 | TX 없이 SQL 문 1개. `jobs[]` = 접수가 만든 작업 ∪ 항목 참조 작업 (A-02), `jobs[].reason` = `failure_code`. ETag = 본문 SHA-256 |
| 14 | 배치 설정·이름 | 새 설정 `cleanup.initial-delay`·`recovery-interval` (PT5M)·`reconcile-interval` (PT6H)·`rate-window-interval` (PT1H)·`batch-size` (500), `worker.lease-recovery-interval` (PT1M)·`deadline-interval` (PT5M)·`initial-delay`·`retry-backoff`·`retry-jitter`·`completion-retries`·`claim-rounds` (3), `circuit.monitor-interval` (PT1M), `budget.reserved-retry`, `claude.key-version`. ShedLock 이름 7개 (`consistency-rules.md` 배치) |
| 15 | 청소 대조 | 종료 작업의 `ISSUED` 시도도 `ABANDONED` (공통 종료와 같음). 미삭제 경보 = WARN 로그 건수만 (지표 등록소 없음). 입력 속도 창 보존 2시간 = 상수 (설계 7.5 고정) |
| 16 | 임대 회수 | 처리 기한이 지난 작업은 건너뜀 (처리 기한 배치가 종료) |
| 17 | 반개방 경보 | `blocked_until`부터 셈 (반개방 시각 열 없음). 감시 배치가 설정 회로를 반개방으로 바꿀 때 `blocked_until = GREATEST(기존, now)` |
| 18 | API 키 헤더 | `anthropic-java` 3.10.0은 `Authorization: Bearer <key>`로 보냄 (SDK `StaticAuthHeaders`, 바이트코드 확인. `X-Api-Key` 아님). 첫 실제 호출 (D-07)에서 수락 확인 |
| 19 | Claude 어댑터 빈 | `worker.enabled=true`일 때만 생성, 그때 빈 `ANTHROPIC_API_KEY` = 기동 실패. `claude.base-url` = `local`·`test`만. 키 = Spring 속성 `pik.analysis.claude.api-key` (`.env.local`이 Spring 속성으로 읽힘) |
| 20 | manifest 내용 | 계획 6.1 + `prompt` (자원 디렉터리)·`userText`. `maxPixels` = 최상위, `uploadPolicy` = 5-1 모양. OCR 키 없음 (D-09) |
| 21 | 최대 비용 | 캐시 꺼짐에도 프롬프트 상한에 캐시 생성 단가 (두 경우 모두 상한). 기본값 `maxCostMicros` 3,527, `imageTokenCap` 4,784, `promptTokenCap` 8,000 (D-07 개발값). 시스템 프롬프트 추정 1,300~1,600 토큰 (캐시 최소 512 이상) |
| 22 | 전송 실패 분류 | `NoRouteToHostException`도 송신 전 실패 (`NOT_SENT`). 2xx 본문을 읽다 끊김 = 불명 (상태·`request-id` 유지, B-05) |
| 23 | 검증기 | 목록을 먼저 자르고 남은 항목을 검사 (잘린 항목은 "전부 INVALID"에 세지 않음, 항목 없는 출력 = 사용 가능). 모르는 키 제거. 실행 값은 자르지 않음. 날짜 범위 = UTC 날짜, 양 끝 포함. `search` 타입 오류 = 검색 블록 전체 버림 |
| 24 | 사진 존재 확인 | 새 photo API `PhotoObservations.holdExisting` (id 순 `FOR SHARE`) |
| 25 | 오래된 관문 증거 | `CLOSED` 회로는 `reset_at` 전에 허가한 호출의 응답을 무시 |
| 26 | 처리 기한 지난 실행 | 그 호출만 정산, 작업 종료는 처리 기한 배치 |
| 27 | 이미 있는 허가 | 같은 (작업, 토큰)의 허가가 있으면 보내지 않음, 임대 회수가 불명으로 정산 |
| 28 | probe 제외 | 증거 없이 `READY`로 돌아간 probe 실행 = `probe_excluded_until` 설정 |
| 29 | 402 | 400처럼 정산 (위험 송신 소진), 회로에는 설정 오류 (K-7) |
| 30 | 늦은 응답 조정 | 비용만 (`ADJUSTED`) = 사용량 × 호출 manifest 단가 (B-04). `responded_at`·`http_status` 유지 → 400 비율 창에 들어가지 않음 |
| 31 | 이벤트 발행 | `AnalysisCompleted` = 완료 TX 안 `ApplicationEventPublisher` (`PhotoSourceChanged`와 같은 방식) |
| 32 | 5c 코드 | `INPUT_RETRY_LATER` = `RATE_LIMITED` (재시도 가능), `ATTEMPTS_EXHAUSTED`·`ANALYSIS_UNAVAILABLE` = `FORBIDDEN` (불가) |
| 33 | `retryAfter` | 접수 때 `ceil(retry_at − created_at)`로 고정 → 재생·상태 조회 같은 값 (줄어들지 않음) |
| 34 | 기기 송신 빠른 판정 | 접수 TX 전에 읽음. 최종 판정 = 허가 |
| 35 | 체험 시작 크기 | 사진 예산·기기 소진으로 거절될 사진도 뺌 → 보낼 것이 없는 요청은 체험 시작·iOS 토큰 요청 없음 |
| 36 | 수동 재처리 | `AnalysisResults.regenerate` = HTTP 없음 (진입점 5-3). 새 사유 `EPOCH_CONFLICT` (409) |
| 37 | 외부 호출 식별 | ADR-0030의 `external_call.request_key` 열 대신 UNIQUE (`job_id`, `run_token`) |
| 38 | usage API 모양 | `lockDeviceSend`·`deviceSendExhausted`에 `multiplier` 인자 (한도 = 체험 한도 × analysis 설정값, 검토점 A "수용 가능"). 새 `holdsReservation` (확정·발급의 예약 확인) |
| 39 | 의존성 | `com.anthropic:anthropic-java` 3.10.0, AWS SDK v2 BOM 2.59.4 + `s3`, 시험 `com.squareup.okhttp3:mockwebserver` 4.12.0 (SDK의 OkHttp 버전, JUnit 4를 끌어오나 JUnit Platform 유지), S3Mock 컨테이너 `adobe/s3mock:5.1.0`. 라이선스 = 모두 Apache-2.0 |

## 근거
- 1: 이전 순서 (할당 → 일일 → 기기)면 `settle`을 둘로 나눠야 해서 사진별 consume/release가 한 번의 할당·기기 잠금 아래에서 끝나지 않는다.
- 4: 허가 TX가 기기를 잡은 채 할당을 잠그면 순서가 뒤집힌다 (R1-02). 종료 판정은 롤백한 스냅샷 위의 판단이라 종료 TX에서 다시 확인해야 한다.
- 5: 조건부 UPDATE 하나는 뒤에 잡는 잠금이 없어 교착 경로가 없다 (검토점 A "수용 가능").
- 8: `url_expires_at`이 최종 삭제 판정의 기준 시각 (설계 2.9)이라 URL이 더 오래 살면 판정이 틀린다 (A-03).
- 11: 늦은 업로드는 그 시도의 문제다. 발급 한도 안에서 새 시도로 다시 올릴 수 있다 (검토점 A "수용 가능"). 예약 없는 확정은 다른 확정 조건 실패와 같이 공통 종료로 보내야 객체가 남지 않는다 (A-01).
- 13: `fk_analysis_job_request`가 `SUCCEEDED` 작업이 있는 동안 접수 행을 남기므로, 항목 삭제 뒤에도 상태 조회가 작업을 보여 줄 수 있다 (A-02).
- 18·21: SDK 기본 동작이고 실제 API 응답은 측정 전이다. 최대 비용이 실제 비용보다 작으면 일일 비용 예약이 한도를 지키지 못한다.
- 22: 연결 수립 전 실패면 요청이 나가지 않았음이 확실하다. 그 밖은 송신 여부를 몰라 보수적으로 불명이다 (K-6).
- 30: 늦은 응답이 400 비율 표본에 들어가면 복구 뒤 과거 결함이 회로를 다시 연다 (R3-01).
- 33: 남은 시간으로 주면 상태 조회 본문·ETag가 매번 바뀌어 304가 나오지 않는다.
- 34: TX 안에서 읽으면 뒤의 예약 잠금이 같은 JPA 엔티티를 다시 읽지 않아 잠금 아래 값이 아니다.
- 37: (`job_id`, `run_token`)은 실행마다 유일하고, 같은 토큰의 허가 재사용 판정에 그대로 쓰인다. 별도 키 열이 필요 없다.
- `DELETE_SENT` 이름 (R3-04): 서버는 물리적 삭제를 확인할 수 없다. 이름을 "삭제 요청 성공"으로 좁히고 부재는 수명 주기 3일이 보장한다. 2 MiB PUT이 30분 넘게 이어지는 경우는 S3 유휴 연결 종료로 사실상 없고, 있어도 수명 주기가 지운다.

| 대안 | 버린 이유 |
|---|---|
| 잠금 순서 유지 + `settle` 분할 (#1) | 사진별 정산이 한 잠금 아래에서 끝나지 않음 |
| 허가 TX 안에서 `REQUIRES_NEW` 종료 (#4) | 첫 TX가 기기 잠금을 쥔 채 할당을 잠금 (순서 역전). usage도 금지 |
| 삭제 확인을 작업 잠금 아래에서 (#5) | 배치가 작업 잠금을 기다리고, 얻는 보장이 없음 |
| `RESERVATION_MISSING` 저장 409 (A-01 전) | 작업·객체가 회수 배치까지 남음 |
| 늦은 업로드 = 작업 종료 (#11) | 발급 한도 안의 재업로드 기회를 없앰 |
| 첫 삭제 성공 = 최종 (R1-09) | URL 만료 전 재PUT이 객체를 되살림 |
| `DELETED` + 반복 재삭제 (R3-04) | 서버가 물리적 삭제를 확인할 수 없음. 수명 주기가 보장 |
| 상태 조회 REPEATABLE READ TX (#13) | SQL 문 1개로 같은 보장, TX 불필요 |
| `retryAfter` = 남은 시간 (#33) | 상태 조회 ETag가 매번 바뀜 |
| `messages.parse()` 엄격 파싱 | 부분 위반에도 응답 전체 거절 (계획 Must NOT have) |
| 프롬프트 캐시 끔 (K-19, R1-23) | 접수 1회에 최대 100장이 5분 안에 같은 prefix로 이어짐. 손익분기 (적중률 약 21.7%)는 D-07에서 판정 |

## 결과
- V6 마이그레이션 (`analysis_job` 실행 열, `analysis_upload_attempt`, `external_call`, `analysis_result`, `photo_ai_budget`, `ai_spend_day`, `ai_send_daily`, `ai_gate`, `analysis_input_rate`, 항목 `reused_*`·`retry_at`, `device_trial_mark.send_count`, manifest 상한 열). 요약 = 설계 문서 4.3. ERD 반영 완료 (`49ecbe7`, erd-editor).
- 코드: `analysis` (업로드 API 3개, worker·claim·준비·허가·송신·완료·응답 정산·불명·관문, 배치 7개, Claude 어댑터, manifest v1·프롬프트·스키마 자원, 공통 검증기, 5c 판정, 수동 재처리), `usage` (`settle`·`holdsReservation`·기기 송신), `consent` (`basisHolds`), `account` (`Principals.isActive`), `photo` (`holdExisting`). `.env.example` = `PIK_ANALYSIS_BUCKET`·`AWS_REGION`·`ANTHROPIC_API_KEY` (값 없음)
- 시험: 마지막 전체 빌드 846개, 실패 0 (동시성 C-9~C-21 포함). 접수 꺼짐이 기본이라 운영 작업·이벤트 발행은 생기지 않는다.
- 문서: 설계 문서 0.3·1.2·2.1~2.9·4.3, `operations-infrastructure.md` B11, `.claude/docs` (`api-contract`·`consistency-rules`·`domain-boundaries`·`data-retention`)
- 설계와 다른 점 (코드 기준으로 설계 문서를 고침): `max_tokens` 1,024 → 4,096 (K-16), 입력 속도 저장소 신규, claim 관문 판정 (최대 `blocked_until` → 원인별 상태·probe), 완료 TX 잠금 순서 (0.3 개정), `external_call.request_key` → (`job_id`, `run_token`), 최대 비용 식 (프롬프트 상한 × 캐시 생성 단가), manifest에 OCR 키 없음 (D-09)
- 계획과 다른 점: `RESERVATION_MISSING` (A-01), 늦은 업로드 = 시도만 거절 (#11), 확정 API 기기 확인, `DUPLICATE_ANALYSIS_JOB`, `holdsReservation`·`holdExisting`·`multiplier` 인자 (#24·#38), 상태 조회 `jobs[]` 합집합 (A-02)
- 미결 (`docs/STATUS.md`):
  - D-07 측정 (실제 사진, 비용 발생, 사용자 승인 필요): manifest 토큰 상한, thinking A/B (adaptive·`low`·4,096 vs 끔·`low`·2,048), 해상도, 캐시 손익분기, API 키 헤더 수락
  - 접수 활성화 전 (5-3 병합 뒤) 사용자 결정: 전역 일일 비용 한도, 전역 슬롯·송신 속도, 시간당 업로드 한도, D-07 합격 기준 (ADR-0033 #5 초안). `max_tokens`를 올리면 예약과 실제 출력 상한이 함께 커짐 → D-07 결과와 함께 제시
  - 실제 S3 계약 시험 (운영 배포 전, `operations-infrastructure.md` B11.5)
  - D-28: 5-2는 O2 구성원 기준만 구현, 영속 묶음은 단계 9
  - 보관 단계: 정리 배치 (5-2는 삭제 순서가 가능하도록 스키마만 맞춤)
  - PR (사용자 승인 후)
- 출처: 착수는 사용자 선택이다. #1~#39는 Claude가 계획·구현 중 정한 관례이고, Codex가 계획 (R1~R4)과 코드 (검토점 A·B)에서 검토했다.
