# ADR-0002: CLAUDE.md 200줄 제한과 보조 문서 분리

- 상태: 채택
- 날짜: 2026-10-25
- 관련: 커밋 `11fd080`, `6f34be8`, `bf2f3cc`

## 맥락
- CLAUDE.md는 매 세션마다 전량이 컨텍스트에 로드된다.
- 요구사항에서 도출한 규칙(도메인 경계, 일관성, API 계약, 보관 기간, 미정 정책 등)을 전부 넣으면 파일이 커지고, 상황과 무관한 규칙까지 항상 토큰을 소비한다.

## 결정
- CLAUDE.md에는 항상 필요한 협업 계약(명령, 불변 규칙, 금지사항)만 둔다.
- 상황별 세부 규칙은 `.claude/docs/<주제>.md`로 분리하고, CLAUDE.md에는 `상황 → 경로` 표만 유지한다.
- `@import`는 사용하지 않는다.
- PostToolUse 훅(`.claude/hooks/check-claude-md-size.sh`)으로 CLAUDE.md가 200줄을 넘으면 차단한다.

## 근거
| 대안 | 버린 이유 |
|---|---|
| 모든 규칙을 CLAUDE.md에 기재 | 상시 로드되어 토큰이 낭비되고, 핵심 규칙이 묻힘 |
| `@import`로 보조 문서 연결 | import된 파일도 전량 상시 로드되므로 분리한 효과가 없음 |
| 규칙만 문서화하고 강제하지 않음 | 시간이 지나면서 파일이 다시 커짐 → 훅으로 강제 |

## 결과
- 작업 전에 해당 상황의 보조 문서를 읽어야 한다.
- 보조 문서 6종: `fr-scope`, `domain-boundaries`, `consistency-rules`, `api-contract`, `data-retention`, `open-policies`.
