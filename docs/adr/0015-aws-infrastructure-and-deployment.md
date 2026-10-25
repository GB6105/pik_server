# ADR-0015: AWS 인프라와 배포·비밀 값 주입

- 상태: 채택
- 날짜: 2026-10-25
- 관련: ADR-0004, ADR-0011, ADR-0014, ADR-0018, D-05, D-13, 브랜치 `feature/tech-stack-design`

## 맥락
- ADR-0004는 배포 비밀 값 주입 경로(AWS SSM / GitHub Actions secrets)를 정하지 않았다 (D-05).
- 분석 작업이 몰려도 API 응답이 느려지면 안 된다.
- 외부 API(Claude·Jev·RevenueCat·FCM) 호출을 위한 outbound 연결이 필요하다.

## 결정
| 구성 요소 | 선택 |
|---|---|
| 리전 | AWS 서울 (ap-northeast-2), 2 AZ |
| 컴퓨트 | ECS Fargate, Graviton (arm64). `api` 서비스 (최소 2개, AZ 분산)와 `worker` 서비스 (최소 1개) 분리. 같은 이미지, 다른 Spring 프로필 |
| 확장 기준 | `api` = CPU·요청 수, `worker` = 작업 큐 최대 대기 시간 |
| 진입 | Route 53 + ACM + ALB + WAF (rate 기반 규칙) |
| DB | RDS for PostgreSQL 18, Multi-AZ, gp3, 자동 백업·시점 복구 (PITR) |
| 임시 저장 | S3 (SSE-KMS, 퍼블릭 차단, 수명 주기 만료). 분석용 이미지 전용, 처리 후 즉시 삭제 |
| 네트워크 | ECS·RDS는 private subnet. outbound는 NAT Gateway (AZ별 배치 여부는 D-13), S3는 gateway endpoint |
| 비밀 값 | SSM Parameter Store SecureString → ECS task definition `secrets`로 환경 변수 주입. DB 비밀번호는 RDS 관리형 Secrets Manager |
| CI → AWS | GitHub Actions OIDC로 IAM 역할 위임. 장기 액세스 키 없음 |
| 배포 | ECR 이미지 → Flyway 마이그레이션 단독 태스크 → ECS 롤링 배포 + 배포 circuit breaker 자동 롤백 |
| 이미지 | 멀티 스테이지 Dockerfile, temurin 21 JRE (arm64), layered jar + CDS 아카이브 |
| IaC | Terraform. 환경 = local (compose)·staging·prod |
| 관측 | CloudWatch Logs·알람 + OTLP 수집. 핵심 지표: API p95, 5xx 비율, 분석 큐 대기 시간, 외부 API 오류율, DB 커넥션 포화 |

## 근거
- `api`·`worker` 분리: 분석 부하를 API와 격리하고, 서로 다른 지표로 확장한다.
- Fargate: 서버 패치·용량 관리 없이 롤링 배포와 헬스 체크 기반 교체를 제공한다. Graviton은 같은 성능에서 x86보다 저렴하다.
- RDS Multi-AZ: 장애 시 자동 전환과 시점 복구를 제공한다. RDS for PostgreSQL 18은 2025-11-14에 GA됐다 (AWS What's New).
- SSM + ECS `secrets`: 비밀 값이 파일·이미지·저장소에 남지 않고 IAM으로 접근을 통제한다.
- GitHub OIDC: 저장소에 AWS 키를 보관하지 않는다.
- Flyway 단독 태스크: 마이그레이션 실패가 앱 인스턴스 기동 실패로 번지지 않는다. 무중단 배포를 위해 expand-contract 순서를 따른다.
- CDS: 기동 시간을 줄여 확장·배포 속도를 높인다.

| 대안 | 버린 이유 |
|---|---|
| GitHub Actions secrets로 런타임 비밀 값 주입 | 배포 서버에 `.env` 파일이 필요하고 교체·감사가 어려움. CI에는 OIDC로 충분 |
| EKS | 클러스터 운영 비용 대비 이점 없음 |
| EC2 직접 운영 | OS 패치·용량 관리 부담 |
| Aurora | 초기 규모에서 비용 대비 이점이 작음. 읽기 부하가 늘면 read replica부터 검토 |

## 결과
- ADR-0004의 배포 서버 행 (`.env` ← SSM 또는 GitHub Actions secrets)은 이 ADR로 대체된다. 배포 서버에 `.env` 파일을 두지 않는다.
- 서버 비밀 값: Claude API 키, TypeSafe API 키, RevenueCat secret API 키·웹훅 인증 값, Firebase 서비스 계정, Sign in with Apple 키 (`.p8`)·Key ID·Team ID, Google OAuth client ID, DB 접속 정보. 도입할 때 `.env.example`에 키를 추가한다.
- Apple·Google 결제 자격 증명은 RevenueCat 대시보드에 등록하므로 서버에 두지 않는다 (ADR-0018).
- 인프라 코드와 CD 파이프라인은 배포 전 단계에서 작성한다.
- 출처: 구성은 Claude 설계안이며, 사용자는 이 ADR의 작성 계획을 승인했고 (2026-10-25), 구성 세부는 종료 보고에서 사용자 확인을 받는다. 외부 제품 사실은 이 날짜의 공식 문서·발표 기준이며, 도입할 때 버전별로 다시 확인한다.
- Graviton의 가격 이점은 AWS 공개 가격 기준이다. 실제 비용은 D-13에서 확인한다.
