# PicPay 구현 계획 설계 문서

> 작성일: 2026-05-25  
> 기반 문서: picpay_prd_v3.md, 2026-05-25-picpay-prd-review-design.md  
> 접근법: 8-Layer 서비스 의존성 순서 기반  
> 예상 기간: 39 sessions × 4h = 약 6~7주 (LLM 보조 기준)

---

## 전제 조건

| 항목 | 내용 |
|------|------|
| 언어/프레임워크 | Java 21 + Spring Boot 3.4 + Gradle 8.10 |
| 로컬 인프라 | Docker Compose (Kafka + Redis + PostgreSQL) |
| AWS | 프리티어 계정, 서울 리전 (ap-northeast-2) |
| 세션 단위 | 하루 4시간 1세션, 세션 내 완료 가능한 태스크 |
| 암호화 | AES-256-GCM (CBC → GCM 변경) |
| 토큰 구분 | 카드 토큰 (Token Service) ≠ JWT 토큰 (API Gateway) |

---

## 레이어 의존성 구조

```
Layer 1: 기반
    └── Layer 2: Token Service (독립)
            └── Layer 3: Payment Service (Token 의존)
                    └── Layer 4: 이벤트 (Payment Outbox 의존)
                    └── Layer 5: Billing Service (Payment + Token 의존)
                            └── Layer 6: API Gateway (전체 서비스 의존)
                                    └── Layer 7: AWS 인프라
                                            └── Layer 8: 부하 테스트
```

---

## Layer 1 — 기반 (Foundation)

**목표:** 모든 서비스가 올라갈 뼈대. 완료 후 `docker-compose up`으로 인프라 전체 실행 가능.

### Gradle 멀티모듈 구조

```
picpay/
├── build.gradle          (루트)
├── settings.gradle
├── common/               (공통 모듈)
├── services/
│   ├── gateway/          (port 8080)
│   ├── payment/          (port 8081)
│   ├── billing/          (port 8082)
│   ├── token/            (port 8083)
│   └── notification/     (port 8084)
└── docker-compose.yml
```

### 세션별 태스크

| Session | 태스크 | 완료 기준 |
|---------|--------|----------|
| S1 | Gradle 멀티모듈 스켈레톤<br>(`root`, `common`, `gateway`, `payment`, `token`, `billing`, `notification`) | `./gradlew build` 전체 통과 |
| S2 | `common` 모듈<br>(`ApiResponse<T>`, `ErrorCode` enum, `GlobalExceptionHandler`, `BaseEntity`) | 공통 클래스 단위 테스트 통과 |
| S3 | Docker Compose<br>(PostgreSQL 16 + Kafka + Zookeeper + Redis 7) | `docker-compose up` 후 각 포트 헬스체크 통과 |
| S4 | DB 스키마 전체 Flyway 마이그레이션<br>(5개 스키마: payment, token, billing, merchant, notification) | `V1__init.sql` 실행 후 전체 테이블 생성 확인 |

---

## Layer 2 — Token Service

**목표:** 의존성 없는 독립 서비스. Payment보다 먼저 구현해야 Payment에서 REST 호출 가능.

### 핵심 설계

- **암호화:** AES-256-GCM (AEAD — 암호화 + 무결성 동시 보장)
- **저장 형식:** `Base64({12B nonce}{16B auth tag}{ciphertext})`
- **CVC:** 메모리에서 즉시 폐기, DB 미저장 (PCI-DSS 3.2)
- **캐시:** `token:{tokenId}` TTL 5분, Cache-Aside 패턴

### 세션별 태스크

| Session | 태스크 | 완료 기준 |
|---------|--------|----------|
| S5 | `VaultService` (AES-256-GCM)<br>+ `CardToken` 엔티티 + 레포지토리 | encrypt/decrypt 단위 테스트, CVC 미저장 확인 |
| S6 | 카드 토큰 발급 API<br>`POST /v1/tokens/card` | 토큰 ID 반환, DB 저장, CVC 필드 없음 확인 |
| S7 | 토큰 조회 API + Redis Cache-Aside<br>`GET /v1/tokens/{tokenId}` (TTL 5분) | 첫 조회 DB hit, 재조회 캐시 hit 로그 확인 |
| S8 | 간편결제 수단 등록/목록/삭제<br>`POST/GET/DELETE /v1/easy-pay/methods` | CRUD 통합 테스트 통과 |

---

## Layer 3 — Payment Service

**목표:** 핵심 도메인. Token Service를 REST로 호출, 결제 전 주기 담당. 완료 후 결제 승인 → 취소 단독 실행 가능.

### 핵심 설계

- **TID 형식:** `T{serviceId}{yyyyMMddHHmmss}{seq 8자리}` (e.g. `TSVR0120260525143022000001`)
- **TID 생성:** Redis `INCR tid:seq:{yyyyMMdd}` TTL 2일 + DB UNIQUE 제약 최종 방어
- **멱등성:** `idempotency:{key}` TTL 24h (String, 결제 응답 JSON 저장)
- **상태 전이:** `READY → PAID → CANCELLED / PARTIAL_CANCELLED`, `READY → FAILED`
- **Outbox:** 결제 TX와 동일 `@Transactional` 내 `outbox_events` INSERT

### 세션별 태스크

| Session | 태스크 | 완료 기준 |
|---------|--------|----------|
| S9 | `Payment` 엔티티 + 레포지토리<br>+ `MockPgClient` (95% 승인율, 5% FAILED 랜덤) | MockPg 100회 호출 시 승인율 90~100% 범위 확인 |
| S10 | TID 생성 (`Redis INCR` + DB UNIQUE)<br>+ Redis 장애 시 UUID 폴백 | Redis 중단 후에도 TID 생성 확인 |
| S11 | 결제 승인 API `POST /v1/payments`<br>+ 멱등성 (`idempotency:{key}` TTL 24h) | 동일 키 2회 호출 시 동일 응답, DB 1건만 저장 |
| S12 | 결제 조회 `GET /v1/payments/{tid}`<br>+ 상태 전이 검증 | 잘못된 전이 시 `INVALID_STATUS_TRANSITION` 400 |
| S13 | 결제 취소 `POST /v1/payments/cancel`<br>전액/부분취소 + `partial_cancellations` 저장 | 부분취소 2회 후 잔액 전액취소 → `CANCELLED` 확인 |
| S14 | Outbox 패턴<br>결제 TX와 동일 `@Transactional`로 `outbox_events` INSERT<br>+ `@Scheduled` Poller (1초 주기) | 결제 후 outbox `PENDING→PUBLISHED` 확인 (Kafka 연결 전 로그) |

---

## Layer 4 — 이벤트 레이어 (Kafka + Notification)

**목표:** Layer 3 Outbox를 실제 Kafka와 연결, at-least-once + 멱등성 소비. 완료 후 결제 → 알림 E2E 동작.

### Kafka 토픽 구성

| 토픽 | 파티션 | Producer | Consumer |
|------|--------|----------|----------|
| `payment.completed` | 3 | Payment | Notification |
| `payment.failed` | 3 | Payment | Notification |
| `payment.cancelled` | 3 | Payment | Notification |
| `billing.executed` | 3 | Billing | Notification |

### Producer 설정

```yaml
acks: all
enable.idempotence: true
```

### Consumer 설정

```yaml
enable.auto.commit: false
auto.offset.reset: earliest
concurrency: 3
```

### 세션별 태스크

| Session | 태스크 | 완료 기준 |
|---------|--------|----------|
| S15 | Kafka 설정<br>4개 토픽 생성, Producer 설정 (`acks=all`, `idempotence=true`) | `kafka-topics.sh --list`로 4개 토픽 확인 |
| S16 | Outbox Poller → Kafka 실제 발행 연동<br>`PENDING` 조회 → `KafkaTemplate.send()` → `PUBLISHED`<br>실패 시 `retry_count++`, `max_retry` 초과 시 `DEAD` | 결제 후 Outbox `PUBLISHED` + Kafka 메시지 수신 확인 |
| S17 | Notification Service 기반<br>`@KafkaListener` 3개 토픽 구독<br>`enable.auto.commit=false`, `concurrency=3` | Consumer 기동 후 `payment.completed` 수신 + 로그 출력 |
| S18 | 수동 커밋 + Consumer 멱등성<br>`processed_events` INSERT ON CONFLICT DO NOTHING<br>중복 수신 시 skip + `ack.acknowledge()` | 동일 `eventId` 2회 발행 시 알림 1회만 처리 |
| S19 | E2E 통합 검증<br>결제 승인 → Outbox → Kafka → Notification 전체 플로우 | 결제 1건 후 Notification 로그, Consumer Lag = 0 |

---

## Layer 5 — Billing Service

**목표:** 정기결제. Payment Service REST 호출, 분산 락 중복 방지, DB 기반 재시도. 완료 후 자동결제 전 주기 동작.

### 핵심 설계

- **스케줄러:** `@Scheduled` 매 1분, `ACTIVE` 플랜 조회 → Payment REST 호출
- **분산 락:** Redisson `RLock`, 키 `lock:billing:{planId}`, TTL 30초
- **재시도:** `billing_retry_jobs`, 지수 백오프 `2^n × 30초` (1차 30초, 2차 60초, 3차 120초)
- **재시도 스케줄러:** 매 10초 polling, `max_retry(3)` 초과 시 `DEAD` + `billing_plans.status=PAUSED`

### 세션별 태스크

| Session | 태스크 | 완료 기준 |
|---------|--------|----------|
| S20 | `BillingPlan` 엔티티 + 레포지토리<br>플랜 등록/조회/해지 API | CRUD + `ACTIVE→CANCELLED` 상태 전이 테스트 |
| S21 | 빌링 스케줄러<br>`@Scheduled` 매 1분 + Payment Service RestClient 호출 | 스케줄러 실행 로그 + Payment 호출 결과 확인 |
| S22 | Redis 분산 락 (Redisson)<br>`lock:billing:{planId}` TTL 30초, 실패 시 skip | 동일 planId 동시 실행 시 1개만 처리 확인 |
| S23 | DB 기반 재시도 (`billing_retry_jobs`)<br>지수 백오프 + 매 10초 스케줄러<br>`DEAD` + `PAUSED` 전이 | 의도적 실패 후 3회 재시도 → DEAD 전이 확인 |
| S24 | `billing.executed` Kafka 발행 (SUCCESS/FAILED)<br>+ 빌링 이력 조회 `GET /v1/billing/plans/{planId}/history` | 자동결제 후 이력 조회 + Notification 수신 확인 |

---

## Layer 6 — API Gateway

**목표:** 외부 진입점. JWT 인증 + Rate Limit + 서킷브레이커로 하위 서비스 보호. 완료 후 전체 MSA 플로우 완성.

### 핵심 설계

- **라우팅:** `/v1/payments/**` → `:8081`, `/v1/billing/**` → `:8082`, `/v1/tokens/**` → `:8083`
- **JWT:** 가맹점 API Key → JWT 발급, `GlobalFilter`로 모든 요청 검증
- **Rate Limiting:** Redis Sorted Set Sliding Window, 가맹점별 분당 100건
- **서킷브레이커:** Resilience4j, `OPEN` 상태 시 즉시 폴백 응답
- **로깅:** TID 헤더 전파 (`X-Transaction-Id`), 소요시간, 상태코드 구조화 로그

### 세션별 태스크

| Session | 태스크 | 완료 기준 |
|---------|--------|----------|
| S25 | Spring Cloud Gateway 기본 설정 + 5개 서비스 라우팅 | `curl gateway:8080/v1/payments` → Payment 응답 통과 |
| S26 | API Key → JWT 발급 엔드포인트<br>+ `GlobalFilter` JWT 검증, 미인증 401 | 유효 JWT 통과, 없으면 401 확인 |
| S27 | Redis Sliding Window Rate Limiting<br>`ZADD` + `ZREMRANGEBYSCORE` + `ZCARD`<br>분당 100건 초과 시 429 | 101번째 요청 시 `RATE_LIMIT_EXCEEDED` 429 확인 |
| S28 | Resilience4j 서킷브레이커<br>하위 서비스 장애 시 폴백 응답 | Payment 강제 중단 후 Gateway 폴백 응답 확인 |
| S29 | 요청/응답 로깅 필터<br>TID 헤더 전파, 소요시간, 상태코드 구조화 로그 | 결제 요청 후 Gateway 로그에 TID, 소요시간 출력 |

---

## Layer 7 — AWS 인프라

**목표:** 로컬 MSA를 AWS에 배포. VPC 설계부터 CI/CD까지. 완료 후 인터넷에서 API 호출 가능.

### 인프라 구성

```
VPC: 10.0.0.0/16 (ap-northeast-2a)
├── Public Subnet: 10.0.1.0/24
│   └── EC2-A (t3.micro) — 5개 Spring Boot
└── Private Subnet: 10.0.2.0/24
    ├── EC2-B (t3.micro) — Kafka + Redis
    └── RDS (db.t3.micro) — PostgreSQL 16
```

### Security Group 규칙

| SG | 포트 | 소스 | 용도 |
|----|------|------|------|
| sg-was | 8080 | 개발자 IP | 직접 접속 (개발 시) |
| sg-was | 8080-8084 | sg-alb | 부하 테스트 시 ALB 경유 |
| sg-was | 22 | 개발자 IP | SSH |
| sg-middleware | 9092 | sg-was | Kafka |
| sg-middleware | 6379 | sg-was | Redis |
| sg-db | 5432 | sg-was | PostgreSQL |

### 세션별 태스크

| Session | 태스크 | 완료 기준 |
|---------|--------|----------|
| S30 | VPC + Subnet + Security Group + Key Pair<br>sg-was에 개발자 IP → 8080 임시 개방 | AWS Console VPC 리소스 맵 확인 |
| S31 | EC2-A + EC2-B 생성, Docker 설치<br>`start-ec2.sh` 작성 + 테스트 (EC2-A/B 동시 시작)<br>EC2-B Private IP → `application.yml` 반영 | `ssh picpay-was` + `docker ps` 확인 |
| S32 | RDS (db.t3.micro) 생성, Private Subnet 배치<br>EC2-A에서 Flyway 마이그레이션 실행 | EC2-A → RDS 5432 연결 + 전체 테이블 확인 |
| S33 | EC2-A용 `docker-compose.yml` 작성<br>JVM 힙 설정 포함 (서비스별 `-Xmx` 적용)<br>`docker stats`로 메모리 여유 확인 | 5개 서비스 `/actuator/health` 200 응답 |
| S34 | GitHub Actions CI 파이프라인<br>빌드 + 테스트 + Docker build + SSH 배포<br>`start-ec2.sh`로 IP 자동 갱신 연동 | `git push` 후 Actions 성공 + EC2-A 자동 배포 |

---

## Layer 8 — 부하 테스트 + 모니터링

**목표:** 실제 트래픽으로 성능 검증 및 병목 식별. ALB는 이 레이어에서만 임시 생성·삭제.

### k6 시나리오 6개

| # | 시나리오 | 목표 TPS | 지속 | 통과 기준 |
|---|---------|---------|------|----------|
| 1 | 단건 결제 | 100 | 3분 | P95 < 500ms |
| 2 | 동시 결제 (멱등성) | 50 | 1분 | 중복 결제 0건 |
| 3 | 빌링 동시 실행 | 30 | 1분 | 분산 락 정상 |
| 4 | 토큰 핫키 조회 | 500 | 2분 | 캐시 히트율 > 95% |
| 5 | 복합 시나리오 | 200 | 5분 | Kafka Lag < 100 |
| 6 | 피크 트래픽 | 500 | 1분 | 한계점 확인 |

### 세션별 태스크

| Session | 태스크 | 완료 기준 |
|---------|--------|----------|
| S35 | `alb-create.sh` 실행 (Target Group + Listener 포함)<br>CloudWatch 대시보드 구성 (CPU, RDS 커넥션, JVM Heap, Kafka Lag) | ALB DNS로 `POST /v1/payments` 응답 확인 |
| S36 | k6 시나리오 1~3 실행<br>단건 결제 / 멱등성 / 빌링 분산 락 | 각 통과 기준 달성 + 결과 저장 |
| S37 | k6 시나리오 4~6 실행<br>토큰 핫키 / 복합 / 피크 트래픽 | 에러율 < 1%, Consumer Lag < 100 |
| S38 | 병목 분석 + 튜닝<br>CloudWatch 분석 → HikariCP 커넥션 풀 조정<br>t3.micro OOM 시 t3.small 전환 결정 | 튜닝 전/후 P95 비교 결과 기록 |
| S39 | `alb-delete.sh` 실행<br>k6 HTML 리포트 → `docs/load-test/` 저장<br>면접 답변용 수치 정리 (TPS, P95, 에러율, 캐시 히트율) | ALB 삭제 완료 + 리포트 커밋 |

---

## 전체 로드맵

```
Layer 1: 기반           S1~S4   ████░░░░░░░░░░░░░░░░  4 sessions
Layer 2: Token Svc     S5~S8   ████░░░░░░░░░░░░░░░░  4 sessions
Layer 3: Payment Svc   S9~S14  ██████░░░░░░░░░░░░░░  6 sessions
Layer 4: 이벤트         S15~S19 █████░░░░░░░░░░░░░░░  5 sessions
Layer 5: Billing Svc   S20~S24 █████░░░░░░░░░░░░░░░  5 sessions
Layer 6: API Gateway   S25~S29 █████░░░░░░░░░░░░░░░  5 sessions
Layer 7: AWS 인프라     S30~S34 █████░░░░░░░░░░░░░░░  5 sessions
Layer 8: 부하 테스트    S35~S39 █████░░░░░░░░░░░░░░░  5 sessions
────────────────────────────────────────────────────
합계: 39 sessions × 4h ≈ 6~7주 (LLM 보조 기준 4~5주)
```

---

## 변경 이력

| 항목 | PRD 원본 | 이 문서 |
|------|---------|---------|
| 암호화 | AES-256-CBC | **AES-256-GCM** |
| 토큰 구분 | 혼용 | 카드 토큰(Token Svc) ≠ JWT(Gateway) 명시 |
| 레이어 순서 | Sprint 기반 | **서비스 의존성 순서** (Token → Payment → Billing) |
| 세션 단위 | 없음 | **4h 단위 세션 태스크** |
| AWS | 상시 ALB | **ALB 부하 테스트 시만 임시 생성** |
