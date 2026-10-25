# API 계약

근거: 요구사항 `## 공통 오류와 결과`. REST 경로·DTO는 별도 설계.

## 호출 구분
| 구분 | 규칙 |
|---|---|
| Query | 상태 변경 금지. 비용 발생 조회(웹 탐색 등)는 해당 FR에서 별도 검증 |
| Command | 사용자 상태·외부 작업 변경. 작업 식별자 + 버전 필수 |
| Internal Event | 저장된 변화에 반응하는 내부 처리 |
| Batch | 누락·기한·잔여 대상 반복 점검 |

## 오류 코드
| 코드 | 의미 | HTTP |
|---|---|---|
| INVALID_INPUT | 필수 입력 누락·잘못된 값 | 400 |
| UNAUTHENTICATED | 세션 부재·만료 | 401 |
| FORBIDDEN / PERMISSION_REQUIRED | 기능 권한·동의 부재 | 403 |
| NOT_FOUND | 소유 범위 내 대상 부재 | 404 |
| CONFLICT | 버전 충돌·반영 중·허용 안 되는 상태 전이 | 409 |
| UNSUPPORTED_OPERATION | 지원하지 않는 동작 | 422 |
| TEMPORARY_FAILURE | 통신·외부 처리·일시 저장 장애 | 503 |

## 공통 응답 필드
- 작업 ID, 결과 상태, 대상별 사유, 재시도 가능 여부

## 형식 (ADR-0014)
- 오류 응답 = RFC 9457 `ProblemDetail` + `code` (위 오류 코드 표)
- API 버전 = 요청 헤더 (Spring Framework 7 API versioning)
- 작업 식별자 = `Idempotency-Key` 헤더 (UUIDv7). 데이터 버전 전달 위치는 구현 시 확정
- 장기 작업 접수 = 202 + 작업 ID + 진행 상태

## 정상 결과로 취급
- 빈 검색 결과
- 사용자 취소
- 장기 작업 접수 (완료 아님)
