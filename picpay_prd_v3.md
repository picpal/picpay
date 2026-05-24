# PicPay — 결제 플랫폼 PRD v3.2

> Stripe를 벤치마킹한 MSA 기반 결제 플랫폼  
> **학습 목표:** Kafka, Redis, MSA, AWS 실무 역량 확보  
> **v3.2 변경:** AWS 공식 문서 기반 비용 재검증 및 인스턴스 타입 업데이트

---

## v2 → v3 변경 이력

| # | 항목 | 변경 내용 |
|---|------|----------|
| 1 | ArgoCD | Phase 2에서 분리 → Phase 3로 이동. Phase 2는 Docker Compose + GitHub Actions로 충분 |
| 2 | AWS 비용 | 보수적으로 재산정. ALB 상시 과금 반영, 프리티어 정책 변경($200 크레딧) 반영 |
| 3 | 카드번호/CVC | CVC는 저장하지 않음(PCI-DSS 규정). 카드번호도 토큰 발급 후 원본 미보관 옵션 추가 |
| 4 | AES 키 관리 | 하드코딩/환경변수 → AWS Secrets Manager 또는 별도 키 테이블 분리 관리 |
| 5 | TID 생성 | AtomicLong → Redis INCR + DB UNIQUE 제약으로 변경 (멀티 서버 대응) |
| 6 | Kafka retry/DLQ | billing.retry 재설계 + DLQ(Dead Letter Queue) 추가 |
| 7 | Outbox 실패 재시도 | retry_count, max_retry, last_error 컬럼 추가 |
| 8 | Rate Limiting | Sliding Window Counter 방식으로 통일, 키 설계 정리 |
| 9 | 결제 상태 전이 | 상태 전이표 + partial_cancellations 테이블 추가 |
| 10 | Consumer 멱등성 | processed_events 테이블로 중복 처리 방지 |

---

# 1. 프로젝트 개요

## 목적
컬리 면접에서 미흡했던 4가지 영역(Kafka, Redis, MSA, AWS)을 하나의 프로젝트로 통합 학습

## 핵심 기능

| 기능 | 설명 | 학습 포인트 |
|------|------|------------|
| 신용카드 결제 | 카드 정보 토큰화 → 승인 → 결과 응답 | MSA 서비스 간 통신, 트랜잭션 |
| 계좌이체 | 은행 계좌 인증 → 이체 요청 → 결과 | 비동기 처리, Kafka 이벤트 |
| 빌링 (정기결제) | 카드 토큰 저장 → 스케줄 기반 자동 결제 | Redis 분산 락, 배치 처리 |
| 간편결제 | 결제수단 등록 → 원클릭 결제 | Redis 세션/캐시, API Gateway |

## 기술 스택

| 영역 | 기술 | 선택 이유 |
|------|------|----------|
| 언어/프레임워크 | Java 21 + Spring Boot 3.4 + Gradle 8.10 | 최신 LTS + 컬리 기술 스택 |
| 메시지 브로커 | Kafka 7.5 (Confluent, Docker) | 이벤트 기반 MSA 학습 |
| 캐시/세션/락 | Redis 7 (Docker) + Redisson | 캐시 전략, 분산 락 학습 |
| DB | PostgreSQL 16 (RDS) | 컬리 사용 DB |
| 인프라 | AWS (EC2 + RDS + ALB) | 클라우드 인프라 학습 |
| 배포 (Phase 2) | Docker Compose + GitHub Actions | 단순 CD |
| 배포 (Phase 3) | ArgoCD + GitHub Actions | GitOps 기반 CD |
| 컨테이너 | Docker + Docker Compose | 로컬/운영 환경 일치 |
| 부하 테스트 | k6 | 대량 트래픽 검증 |

### Java 21 + Spring Boot 3.4 활용 포인트

| 기능 | 활용 위치 | 효과 |
|------|----------|------|
| Virtual Threads (가상 스레드) | 전 서비스 | 기존 플랫폼 스레드 대비 경량. 메모리 제약 환경에서 동시 처리 능력 향상. `spring.threads.virtual.enabled=true` 한 줄로 적용 |
| Record 클래스 | DTO, 이벤트 메시지 | 불변 데이터 객체를 간결하게 정의 |
| Pattern Matching (switch) | 상태 전이 로직 | 결제 상태별 분기 코드 간결화 |
| Sealed Classes | 이벤트 타입 정의 | Kafka 이벤트 타입을 컴파일 타임에 검증 |
| Text Blocks | SQL, JSON 템플릿 | 가독성 향상 |
| Spring Boot 3.4 RestClient | 서비스 간 REST 통신 | WebClient 대비 동기 호출 코드 간결화 |
| Spring Boot 3.4 Structured Logging | 전 서비스 | JSON 구조화 로그 기본 지원 |

**Virtual Threads 특히 중요:**
EC2 메모리가 제한적인 환경(t3.small 2GB 등)에서 5개 Spring Boot 서비스를 분산 운영해야 하므로, 기존 플랫폼 스레드(스레드당 ~1MB 스택)보다 가상 스레드(스레드당 ~수KB)가 메모리 효율에서 큰 차이를 만듭니다. Kafka Consumer, Redis 호출, DB 호출 등 I/O 대기가 많은 결제 서비스에서 가상 스레드의 효과가 극대화됩니다.

---

# 2. MSA 서비스 아키텍처

## 전체 아키텍처

```
                         [Client / k6]
                              │
                              ▼
┌──────────────────────────────────────────────────────────────┐
│                      ALB (HTTPS 종단)                        │
└──────────────┬───────────────────────────────────────────────┘
               │
               ▼
┌──────────────────────────┐
│      API Gateway         │
│  (Spring Cloud Gateway)  │
│  - JWT 인증 검증         │
│  - Rate Limiting (Redis) │
│  - 라우팅                │
│  - 서킷브레이커          │
│  Port: 8080              │
└──┬──────┬──────┬─────────┘
   │      │      │
   ▼      ▼      ▼
┌──────┐┌──────┐┌──────┐    ┌──────────────┐
│Pay-  ││Bill- ││Token │    │ Notification │
│ment  ││ing   ││Svc   │    │ Service      │
│Svc   ││Svc   ││      │    │              │
│:8081 ││:8082 ││:8083 │    │ :8084        │
└──┬───┘└──┬───┘└──┬───┘    └──────┬───────┘
   │       │       │               │
   │       │       │          Kafka Consumer
   │       │       │               ▲
   ▼       ▼       ▼               │
┌──────────────────────┐    ┌──────┴───────┐
│    PostgreSQL        │    │    Kafka      │
│  (스키마 분리)        │    │  (이벤트 버스)│
└──────────────────────┘    └──────────────┘
           ▲
     ┌─────┴─────┐
     │   Redis    │
     │ - 캐시     │
     │ - 분산 락  │
     │ - Rate Limit│
     │ - 멱등성 키│
     │ - TID 채번 │  ← v3 추가
     └───────────┘
```

## 서비스별 역할

### 1. API Gateway (Spring Cloud Gateway) — Port 8080
- 라우팅: `/v1/payments/**` → Payment, `/v1/billing/**` → Billing 등
- JWT 인증: 가맹점 API Key → JWT 발급, 요청마다 검증
- Rate Limiting: Redis Sliding Window Counter, 가맹점별 분당 100건
- 서킷브레이커: Resilience4j, 하위 서비스 장애 시 폴백
- 로깅: 요청/응답 (TID, 소요시간, 상태코드)

### 2. Payment Service — Port 8081
- 결제 승인 / 취소 / 환불 / 부분취소
- Mock PG 연동 (95% 승인율 시뮬레이션)
- 결제 상태 관리 (상태 전이표 기반)
- TID 생성: **Redis INCR + DB UNIQUE 제약** (v3 변경)
- 멱등성: Redis `idempotency:{key}` TTL 24시간
- 이벤트 발행: 아웃박스 패턴 → Kafka

### 3. Billing Service — Port 8082
- 빌링 플랜 등록 / 수정 / 해지
- 스케줄러 기반 자동 결제 (매 1분 polling)
- Redis 분산 락 (Redisson)
- 실패 재시도: Kafka retry 토픽 + 지수 백오프 (최대 3회)
- DLQ: 최대 재시도 초과 시 billing.dlq 토픽으로 이동
- Kafka Producer: 빌링 결과 이벤트

### 4. Token Service — Port 8083
- 카드 정보 수신 → AES-256-CBC 암호화 → 토큰 발급
- **CVC는 저장하지 않음** (PCI-DSS 3.2 요건, v3 변경)
- **카드 원본 삭제 옵션:** 토큰 발급 후 암호화된 원본을 일정 기간 후 삭제 가능
- 토큰 ↔ 카드 정보 매핑 관리
- 간편결제 수단 등록/삭제
- Redis 캐시: Cache-Aside 패턴 (TTL 5분)
- **AES 키 관리:** 환경변수 분리 → Phase 2에서 AWS Secrets Manager 연동 (v3 변경)

### 5. Notification Service — Port 8084
- Kafka Consumer: 결제/빌링 이벤트 구독
- 수동 커밋 (MANUAL ack)
- **Consumer 멱등성:** processed_events 테이블로 중복 처리 방지 (v3 추가)
- Mock 알림 발송 (로그 출력)

---

# 3. 결제 상태 전이 (v3 추가)

## 상태 전이표

```
        ┌───────────┐
        │   READY   │ (결제 생성 직후)
        └─────┬─────┘
              │ PG 승인 요청
        ┌─────▼─────┐
   ┌────│   PAID    │────────────────────┐
   │    └─────┬─────┘                    │
   │          │                          │
   │    ┌─────▼──────────┐    ┌──────────▼────────┐
   │    │  CANCELLED     │    │ PARTIAL_CANCELLED  │
   │    │  (전체 취소)    │    │ (부분 취소)         │
   │    └────────────────┘    └───────────────────┘
   │
   │    ┌─────────────┐
   └────│   FAILED    │ (PG 거절 / 통신 오류)
        └─────────────┘
```

## 상태 전이 규칙

| 현재 상태 | 가능한 전이 | 조건 |
|----------|------------|------|
| READY | PAID | PG 승인 성공 |
| READY | FAILED | PG 거절 또는 통신 오류 |
| PAID | CANCELLED | 전체 취소 요청 |
| PAID | PARTIAL_CANCELLED | 부분 취소 요청 (cancelAmount < amount) |
| PARTIAL_CANCELLED | PARTIAL_CANCELLED | 추가 부분 취소 (잔액 > 0) |
| PARTIAL_CANCELLED | CANCELLED | 잔액 전체 취소 |
| FAILED | - | 최종 상태 (전이 불가) |
| CANCELLED | - | 최종 상태 (전이 불가) |

## 부분 취소 테이블 (v3 추가)

```
[payment.partial_cancellations]
├── id (PK, BIGSERIAL)
├── payment_id (FK → payments.id)
├── cancel_tid (VARCHAR 64, UNIQUE)     ← 취소 건 별도 TID
├── cancel_amount (BIGINT)
├── remaining_amount (BIGINT)           ← 취소 후 잔액
├── reason (VARCHAR 500)
├── pg_cancel_tid (VARCHAR 64)
├── status (VARCHAR 20)                 ← SUCCESS | FAILED
├── created_at (TIMESTAMP)
└── INDEX: idx_partial_cancel_payment (payment_id)
```

---

# 4. TID 생성 (v3.1 개선)

## 변경 이유

| 문제 | AtomicLong에서의 리스크 |
|------|----------------------|
| 서버 재시작 | AtomicLong 값 초기화 → 기존 순번과 충돌 가능 |
| 다중 인스턴스 | 인스턴스별 동일 순번 생성 가능 |
| 시간 역전 | 서버 시간 불일치(NTP 동기화 오류 등) 시 정렬 오류 가능 |
| 장애 복구 | 재시작 후 이전 순번 복원 불가 |

## 새 방식: Redis INCR + DB UNIQUE

```
TID 형식:
T{serviceId}{yyyyMMddHHmmss}{sequence 6~8자리 zero-padding}

예시:
TSVR0120260519143022000001

Redis Key:
tid:seq:{yyyyMMdd}

처리 방식:
1. 현재 일자 기준 Redis INCR 수행 → 원자적 순번 획득
2. sequence를 6~8자리 zero-padding
3. serviceId + timestamp + sequence 조합으로 TID 생성
4. Redis Key TTL은 2일로 설정 (일자 변경 경계 안전 마진)
5. payment.payments.tid UNIQUE 제약조건으로 최종 중복 방어
```

## 비교

| 항목 | AtomicLong (v2) | Redis INCR (v3.1) |
|------|-----------------|-------------------|
| 멀티 서버 | 서버별 독립 → 충돌 가능 | Redis 중앙 관리 → 충돌 없음 |
| 서버 재시작 | 0부터 재시작 | Redis에 유지 |
| 시간 역전 | 동일 순번 가능 | 순번은 Redis가 보장, 시간과 독립 |
| 성능 | JVM 내 나노초 | 네트워크 1ms 이내 |
| 최종 방어 | 없음 | DB UNIQUE 제약 |

## Redis 장애 시 폴백

```java
private String generateTid() {
    try {
        String key = "tid:seq:" + today();
        Long seq = redisTemplate.opsForValue().increment(key);
        redisTemplate.expire(key, 2, TimeUnit.DAYS); // 2일 TTL (일자 변경 경계 안전)
        return String.format("T%s%s%08d", SERVICE_ID, timestamp(), seq);
    } catch (Exception e) {
        // Redis 장애 시 UUID 기반 폴백
        log.warn("Redis unavailable for TID generation, using UUID fallback");
        return "T" + SERVICE_ID + timestamp() + UUID.randomUUID().toString().substring(0, 8);
    }
    // 최종 방어: DB INSERT 시 UNIQUE 제약 위반이면 재시도
}
```

---

# 5. Kafka 설계 (v3 보강)

## 토픽 구조

| 토픽 | 파티션 | Producer | Consumer | 용도 |
|------|--------|----------|----------|------|
| `payment.completed` | 3 | Payment | Notification | 결제 완료 이벤트 |
| `payment.failed` | 3 | Payment | Notification | 결제 실패 알림 |
| `payment.cancelled` | 3 | Payment | Notification | 결제 취소 알림 |
| `billing.executed` | 3 | Billing | Notification | 빌링 실행 결과 (성공/최종실패 모두) |

> v3.1 변경: `billing.retry`와 `billing.dlq` 토픽을 제거하고 **DB 기반 재시도**로 변경.
> Kafka는 메시지를 특정 미래 시점까지 자동 지연 소비하는 기능을 기본 제공하지 않으므로,
> 재시도는 DB 스케줄러가 더 적합하다.

## 빌링 재시도 설계 (v3.1: DB 기반으로 변경)

### billing.billing_retry_jobs 테이블

```
[billing.billing_retry_jobs]
├── id (PK, BIGSERIAL)
├── plan_id (VARCHAR 64)
├── retry_count (INT, DEFAULT 0)
├── max_retry (INT, DEFAULT 3)
├── next_retry_at (TIMESTAMP)             ← 스케줄러 조회 기준
├── last_error (VARCHAR 500)
├── status (VARCHAR 20)                   ← PENDING | RETRYING | SUCCESS | DEAD
├── created_at (TIMESTAMP)
├── updated_at (TIMESTAMP)
└── INDEX: idx_retry_pending (status, next_retry_at) WHERE status = 'PENDING'
```

### 재시도 흐름

```
빌링 자동 실행
    │
    ├── 성공 → billing.executed (status=SUCCESS) Kafka 발행 → 완료
    │
    └── 실패
         └── billing_retry_jobs에 INSERT
              ├── retry_count: 0
              ├── next_retry_at: now + 30초
              └── status: PENDING

재시도 스케줄러 (매 10초 polling):
  SELECT * FROM billing_retry_jobs
  WHERE status = 'PENDING' AND next_retry_at <= now()

  각 건에 대해:
    ├── Redis 분산 락 획득
    ├── Payment Service 호출 (결제 재시도)
    │
    ├── 성공
    │    ├── status → SUCCESS
    │    ├── billing_plans.retry_count 리셋
    │    └── billing.executed (status=SUCCESS) Kafka 발행
    │
    └── 실패
         ├── retry_count 증가
         ├── retry_count < max_retry (3회)?
         │    ├── Yes: next_retry_at = now + (2^retry_count × 30초)
         │    │        1차: 30초, 2차: 60초, 3차: 120초
         │    └── status: PENDING (다음 폴링에서 재시도)
         │
         └── retry_count >= max_retry
              ├── status → DEAD (수동 처리 필요)
              ├── billing_plans.status → PAUSED
              └── billing.executed (status=FAILED) Kafka 발행 → 고객 알림
```

### Kafka 토픽 방식 대비 DB 방식의 장점

| 항목 | Kafka 토픽 방식 | DB 기반 방식 (v3.1) |
|------|---------------|-------------------|
| 지연 실행 | Kafka 기본 미지원, 별도 구현 필요 | next_retry_at으로 자연스럽게 구현 |
| 재시도 상태 확인 | Kafka 메시지 내부 확인 어려움 | DB 조회로 즉시 확인 가능 |
| 수동 처리 | DLQ 토픽에서 메시지 추출 필요 | DEAD 상태 건을 DB에서 조회/수정 |
| 구현 난이도 | 높음 (Consumer delay 로직) | 낮음 (스케줄러 + DB 조회) |
| 모니터링 | Kafka Consumer Lag 확인 | SQL 한 줄로 현황 파악 |

## 이벤트 메시지 스키마

### payment.completed / payment.failed / payment.cancelled
```json
{
  "eventId": "evt_uuid_001",
  "eventType": "PAYMENT_COMPLETED",
  "timestamp": "2026-05-19T14:30:22",
  "data": {
    "tid": "TSVR0120260519143022000001",
    "orderId": "order_20260519_001",
    "merchantId": "mer_001",
    "amount": 15000,
    "method": "CARD",
    "status": "PAID",
    "pgTid": "PG3A7B2C4D5E6F"
  }
}
```

### billing.executed
```json
{
  "eventId": "evt_uuid_004",
  "eventType": "BILLING_EXECUTED",
  "timestamp": "2026-06-01T00:05:12",
  "data": {
    "planId": "plan_p1q2r3",
    "tid": "TSVR0120260601000512000042",
    "amount": 9900,
    "status": "SUCCESS",
    "failureReason": null
  }
}
```

## Producer/Consumer 설정

| 설정 | 값 | 이유 |
|------|-----|------|
| acks | all | 모든 replica 기록 확인 (데이터 유실 방지) |
| enable.idempotence | true | 네트워크 재시도 시 중복 메시지 방지 |
| auto.commit | false | 수동 커밋으로 처리 완료 보장 |
| auto.offset.reset | earliest | 새 Consumer 합류 시 처음부터 읽기 |
| concurrency | 3 | 파티션 수와 동일하게 병렬 처리 |

## 아웃박스 패턴 (v3 보강)

### outbox_events 테이블 (v3: 재시도 컬럼 추가)

```
[payment.outbox_events]
├── id (PK, BIGSERIAL)
├── aggregate_type (VARCHAR 50)
├── aggregate_id (VARCHAR 64)
├── event_type (VARCHAR 50)
├── topic (VARCHAR 100)
├── payload (JSONB)
├── status (VARCHAR 20)               ← PENDING | PUBLISHED | FAILED
├── retry_count (INT, DEFAULT 0)      ← v3 추가
├── max_retry (INT, DEFAULT 5)        ← v3 추가
├── last_error (VARCHAR 500)          ← v3 추가
├── created_at (TIMESTAMP)
├── published_at (TIMESTAMP)
└── INDEX: idx_outbox_pending (status, created_at) WHERE status IN ('PENDING', 'FAILED')
```

### Outbox Poller 동작 (v3 보강)

```
매 1초 polling:
1. SELECT WHERE status IN ('PENDING', 'FAILED') AND retry_count < max_retry
2. 각 이벤트를 Kafka에 발행 시도
   ├── 성공 → status='PUBLISHED', published_at=now()
   └── 실패 → retry_count++, last_error=에러메시지
              retry_count >= max_retry → status='DEAD' (수동 처리)
```

---

# 6. Redis 설계 (v3 보강)

## 키 설계

| 용도 | 키 패턴 | Value | TTL | 자료구조 |
|------|---------|-------|-----|---------|
| Rate Limiting | `rate:{merchantId}:{windowKey}` | 요청 횟수 | 2분 | Sorted Set (v3 변경) |
| 멱등성 | `idempotency:{key}` | 결제 응답 JSON | 24시간 | String |
| 토큰 캐시 | `token:{tokenId}` | 카드 정보 JSON | 5분 | String |
| 분산 락 | `lock:billing:{planId}` | Lock 메타데이터 | 30초 | Redisson Lock |
| TID 채번 | `tid:sequence:{yyyyMMdd}` | 일별 시퀀스 | 24시간 | String (INCR) |
| 핫키 분산 | `merchant:{merchantId}:shard_{N}` | 가맹점 정보 | 10분 | String |

## Rate Limiting — Sliding Window Counter (v3 통일)

```
방식: Sorted Set + 타임스탬프 기반 Sliding Window

요청마다:
1. ZADD rate:{merchantId} {현재timestamp} {requestId}
2. ZREMRANGEBYSCORE rate:{merchantId} 0 {현재timestamp - 60초}
3. ZCARD rate:{merchantId}
4. 결과 > 100 → 429 Too Many Requests

장점:
- Fixed Window의 경계 문제 해결 (00:59에 100건 + 01:00에 100건 = 1초간 200건 문제)
- 정확한 1분 기준 카운팅
```

```java
// API Gateway에서 Rate Limiting 구현
public boolean isAllowed(String merchantId, int limit) {
    String key = "rate:" + merchantId;
    long now = System.currentTimeMillis();
    long windowStart = now - 60_000; // 1분 전

    redisTemplate.opsForZSet().add(key, UUID.randomUUID().toString(), now);
    redisTemplate.opsForZSet().removeRangeByScore(key, 0, windowStart);
    Long count = redisTemplate.opsForZSet().zCard(key);
    redisTemplate.expire(key, 2, TimeUnit.MINUTES); // 윈도우 + 버퍼

    return count != null && count <= limit;
}
```

## 캐시 무효화

| 이벤트 | 동작 |
|--------|------|
| 토큰 정보 변경 | `DEL token:{tokenId}` → 다음 조회 시 DB 재로드 |
| 토큰 비활성화 | `DEL token:{tokenId}` + DB 업데이트 |
| 가맹점 정보 변경 | `DEL merchant:{merchantId}:shard_*` |

---

# 7. Consumer 멱등성 (v3 추가)

## 문제

Kafka는 at-least-once 전달을 보장. Consumer 처리 중 장애 발생 시 같은 메시지를 재수신할 수 있음. 알림이 두 번 발송되거나, 빌링이 두 번 실행되면 안 됨.

## 해결: processed_events 테이블

```
[notification.processed_events]
├── event_id (PK, VARCHAR 64)         ← Kafka 메시지의 eventId
├── topic (VARCHAR 100)
├── processed_at (TIMESTAMP)
└── UNIQUE: event_id (중복 INSERT 시 예외 → skip)
```

```java
@KafkaListener(topics = "payment.completed")
public void handle(ConsumerRecord<String, String> record, Acknowledgment ack) {
    PaymentEvent event = parseEvent(record.value());

    // 멱등성 체크: 이미 처리된 이벤트인가?
    if (processedEventRepository.existsById(event.getEventId())) {
        log.info("Duplicate event skipped. eventId={}", event.getEventId());
        ack.acknowledge();
        return;
    }

    // 처리
    sendNotification(event);

    // 처리 완료 기록
    processedEventRepository.save(new ProcessedEvent(event.getEventId(), record.topic()));

    ack.acknowledge();
}
```

---

# 8. 카드 정보 저장 및 암호화 정책 (v3.1 개선)

## 설계 원칙

실무 결제 시스템에서는 카드번호를 직접 저장하면 PCI-DSS 범위가 크게 확대된다. 본 프로젝트는 학습용이므로 **Mock Vault**를 구현하되, 운영 기준의 설계 원칙은 준수한다.

| 원칙 | 설명 |
|------|------|
| CVC는 절대 저장하지 않음 | PCI-DSS 3.2: 인증 후 CVC 보관 금지 |
| 카드번호는 Mock Vault를 통해 토큰화 | 운영에서는 외부 PG/VAN/Vault/KMS/HSM 사용 |
| 암호화 키는 소스코드/yml에 두지 않음 | 환경변수 → Secrets Manager → KMS 단계적 적용 |
| IV는 매 암호화마다 랜덤 생성 | CBC 모드에서 동일 평문의 패턴 노출 방지 |

## Phase별 키 관리 전략

| Phase | 방식 | 설명 |
|-------|------|------|
| Phase 1 (로컬) | `.env` 환경변수 | AES 키를 `.env` 파일에 저장, `.gitignore`에 등록 |
| Phase 2 (AWS) | AWS Secrets Manager | 키를 Secrets Manager에 저장, EC2에서 IAM Role로 접근 |
| Phase 3 (확장) | AWS KMS | KMS로 KEK 관리, DEK를 KMS로 암복호화 |

## 암호화 알고리즘 선택

| 알고리즘 | 특징 | 본 프로젝트 |
|---------|------|------------|
| AES-256-CBC | 가장 널리 사용, IV 필요, 패딩 필요(PKCS7) | 기본 구현 |
| AES-256-GCM | 암호화 + 무결성 검증 동시, 최신 권장 | 선택적 확장 |

> CBC 구현 시 IV를 암호문 앞에 붙여서 함께 저장: `{16byte IV}{암호문}`
> GCM 확장 시 인증 태그(Authentication Tag)도 함께 저장

## 카드 데이터 저장 정책

| 항목 | 저장 여부 | 이유 |
|------|----------|------|
| 카드번호 | Mock Vault 암호화 저장 | 빌링/재결제에 필요. 운영에서는 외부 토큰화 서비스 사용 |
| 유효기간 | 암호화 저장 | 빌링/재결제에 필요 |
| CVC | **저장하지 않음** | PCI-DSS 3.2: 인증 후 즉시 폐기 |
| 카드 뒷 4자리 | 평문 저장 | 고객 표시용 (마스킹된 정보) |

## 카드 등록 플로우

```
1. 클라이언트 → Token Service: 카드번호 + 유효기간 + CVC 전송 (TLS)
2. Token Service: CVC로 Mock PG 인증 → 성공
3. Token Service: CVC는 메모리에서 즉시 폐기 (변수 null 처리)
4. Token Service: 카드번호 + 유효기간을 Mock Vault로 암호화 → DB 저장
   - IV 랜덤 생성 → {IV + 암호문} 형태로 card_number_enc에 저장
5. Token Service → 클라이언트: 토큰 ID 반환 (카드 원본 미반환)
```

> **Mock Vault:** Token Service 내부에 `VaultService` 클래스를 두고, encrypt/decrypt를 담당.
> Phase 1에서는 로컬 AES 암복호화, Phase 2에서는 AWS Secrets Manager에서 키를 가져오는 구조.
> 운영에서는 이 VaultService를 외부 HashiCorp Vault나 AWS KMS로 교체.

---

# 9. DB 설계 (ERD) — v3 보강

## 스키마 분리

```
PostgreSQL (PicPay)
├── payment 스키마  → Payment Service
├── token 스키마    → Token Service
├── billing 스키마  → Billing Service
├── merchant 스키마 → API Gateway
└── notification 스키마 → Notification Service (v3 추가)
```

## v3.1 추가/변경 테이블

### payment.partial_cancellations (v3 추가)
```
├── id (PK, BIGSERIAL)
├── payment_id (BIGINT, FK → payments.id)
├── cancel_tid (VARCHAR 64, UNIQUE)
├── cancel_amount (BIGINT)
├── remaining_amount (BIGINT)
├── reason (VARCHAR 500)
├── pg_cancel_tid (VARCHAR 64)
├── status (VARCHAR 20)               ← SUCCESS | FAILED
├── created_at (TIMESTAMP)
└── INDEX: idx_partial_cancel_payment (payment_id)
```

### billing.billing_retry_jobs (v3.1 추가)
```
├── id (PK, BIGSERIAL)
├── plan_id (VARCHAR 64)
├── retry_count (INT, DEFAULT 0)
├── max_retry (INT, DEFAULT 3)
├── next_retry_at (TIMESTAMP)
├── last_error (VARCHAR 500)
├── status (VARCHAR 20)               ← PENDING | RETRYING | SUCCESS | DEAD
├── created_at (TIMESTAMP)
├── updated_at (TIMESTAMP)
└── INDEX: idx_retry_pending (status, next_retry_at) WHERE status = 'PENDING'
```

### token.card_tokens (v3.1 변경)
```
변경: card_cvc_enc 컬럼 제거 (CVC 미저장, PCI-DSS)
추가: card_number_deleted_at (TIMESTAMP, NULLABLE) ← 카드 원본 삭제 시점
```

### payment.outbox_events (v3 컬럼 추가)
```
기존 컬럼 + 아래 추가:
├── retry_count (INT, DEFAULT 0)
├── max_retry (INT, DEFAULT 5)
├── last_error (VARCHAR 500)
└── INDEX 변경: WHERE status IN ('PENDING', 'FAILED') AND retry_count < max_retry
```

### notification.processed_events (v3 추가)
```
├── event_id (PK, VARCHAR 64)
├── topic (VARCHAR 100)
└── processed_at (TIMESTAMP, DEFAULT NOW())
```

### token.card_tokens (v3 변경)
```
변경: card_cvc_enc 컬럼 제거 (CVC 미저장)
추가: card_number_deleted_at (TIMESTAMP, NULLABLE) ← 원본 삭제 시점
```

## 서비스 간 참조 규칙

| 호출 방향 | 방식 | 예시 |
|----------|------|------|
| Payment → Token | **REST API** | 결제 시 토큰 유효성 검증 |
| Billing → Payment | **REST API** | 자동 결제 실행 |
| Billing → Token | **REST API** | 토큰 유효성 검증 |
| Payment → Notification | **Kafka 이벤트** | 결제 완료/실패/취소 알림 |
| Billing → Notification | **Kafka 이벤트** | 빌링 결과 알림 |
| Billing → Billing | **Kafka 이벤트** | 빌링 재시도 (billing.retry) |

**규칙:** 다른 서비스의 DB를 직접 조회하지 않음. 동기 호출은 REST, 비동기 통보는 Kafka.

---

# 10. API 명세

## Payment Service

| 메서드 | 경로 | 설명 |
|--------|------|------|
| POST | `/v1/payments` | 결제 승인 |
| GET | `/v1/payments/{tid}` | 결제 조회 |
| POST | `/v1/payments/cancel` | 결제 취소 (전체/부분) |

### POST /v1/payments — 결제 승인
```json
// Request
{
  "merchantId": "mer_001",
  "orderId": "order_20260519_001",
  "amount": 15000,
  "method": "CARD",
  "tokenId": "tok_abc123",
  "idempotencyKey": "idem_unique_001"
}
// Response (200)
{
  "tid": "TSVR0120260519143022_0000000001",
  "orderId": "order_20260519_001",
  "merchantId": "mer_001",
  "amount": 15000,
  "method": "CARD",
  "status": "PAID",
  "pgTid": "PG3A7B2C4D5E6F",
  "createdAt": "2026-05-19T14:30:22"
}
```

### POST /v1/payments/cancel — 결제 취소
```json
// Request
{
  "tid": "TSVR0120260519143022_0000000001",
  "cancelAmount": 5000,           // null이면 전액 취소
  "reason": "부분 환불 요청"
}
// Response (200) — 부분 취소 시
{
  "tid": "TSVR0120260519143022_0000000001",
  "status": "PARTIAL_CANCELLED",
  "amount": 15000,
  "cancelledAmount": 5000,
  "remainingAmount": 10000
}
```

## Token Service

| 메서드 | 경로 | 설명 |
|--------|------|------|
| POST | `/v1/tokens/card` | 카드 토큰 발급 |
| GET | `/v1/tokens/{tokenId}` | 토큰 조회 (내부 서비스 전용) |
| POST | `/v1/easy-pay/methods` | 간편결제 수단 등록 |
| GET | `/v1/easy-pay/methods` | 간편결제 수단 목록 |
| DELETE | `/v1/easy-pay/methods/{methodId}` | 간편결제 수단 삭제 |

## Billing Service

| 메서드 | 경로 | 설명 |
|--------|------|------|
| POST | `/v1/billing/plans` | 빌링 플랜 등록 |
| GET | `/v1/billing/plans/{planId}` | 빌링 플랜 조회 |
| POST | `/v1/billing/plans/{planId}/cancel` | 빌링 해지 |
| GET | `/v1/billing/plans/{planId}/history` | 빌링 이력 조회 |

## 공통 에러 응답

```json
{
  "error": {
    "code": "DUPLICATE_ORDER",
    "message": "Duplicate orderId: order_20260519_001",
    "timestamp": "2026-05-19T14:30:22"
  }
}
```

| 에러 코드 | HTTP | 설명 |
|----------|------|------|
| DUPLICATE_ORDER | 409 | 중복 주문번호 |
| PAYMENT_NOT_FOUND | 404 | 결제 건 없음 |
| INVALID_STATUS_TRANSITION | 400 | 상태 전이 불가 |
| TOKEN_NOT_FOUND | 404 | 토큰 없음 |
| PLAN_NOT_FOUND | 404 | 빌링 플랜 없음 |
| RATE_LIMIT_EXCEEDED | 429 | API 호출 한도 초과 |
| UNAUTHORIZED | 401 | 인증 실패 |
| IDEMPOTENCY_CONFLICT | 409 | 멱등성 키 충돌 (다른 요청에 사용된 키) |

---

# 11. 시퀀스 다이어그램

## 카드 결제 승인

```
Client      Gateway     Payment     Token    Redis     MockPG    DB       Kafka
  │            │           │          │        │         │        │         │
  │─POST /payments─▶│      │          │        │         │        │         │
  │            │─JWT검증──▶│          │        │         │        │         │
  │            │─RateLimit────────────────▶│   │         │        │         │
  │            │─라우팅────────▶│       │   │   │         │        │         │
  │            │           │          │        │         │        │         │
  │            │           │─멱등성체크────▶│   │         │        │         │
  │            │           │          │  (hit→기존응답반환)│        │         │
  │            │           │          │        │         │        │         │
  │            │           │─토큰검증─────▶│   │         │        │         │
  │            │           │◀─카드정보(캐시/DB)│ │         │        │         │
  │            │           │          │        │         │        │         │
  │            │           │─TID생성──────────▶│(INCR)   │        │         │
  │            │           │          │        │         │        │         │
  │            │           │─PG승인─────────────────────▶│        │         │
  │            │           │◀─PG응답─────────────────────│        │         │
  │            │           │          │        │         │        │         │
  │            │           │─BEGIN TX──────────────────────────▶│         │
  │            │           │  INSERT payments                   │         │
  │            │           │  INSERT outbox_events (PENDING)    │         │
  │            │           │─COMMIT────────────────────────────▶│         │
  │            │           │          │        │         │        │         │
  │            │           │─멱등성캐시저장────▶│(24h TTL)│        │         │
  │            │           │          │        │         │        │         │
  │◀───응답──────────────────│          │        │         │        │         │
  │            │           │          │        │         │        │         │
  │            │           │[Outbox Poller, 1초 주기]    │        │         │
  │            │           │──PENDING 조회──────────────────────▶│         │
  │            │           │──Kafka 발행───────────────────────────────▶│
  │            │           │──PUBLISHED 업데이트────────────────▶│         │
```

## 빌링 자동 결제

```
Scheduler   Billing    Redis      Payment    DB        Kafka
  │           │          │           │        │          │
  │─매1분실행─▶│          │           │        │          │
  │           │─ACTIVE 플랜 조회──────────────▶│          │
  │           │          │           │        │          │
  │           │[각 플랜에 대해]       │        │          │
  │           │─분산 락 획득─────▶│   │        │          │
  │           │◀─lock 성공────────│   │        │          │
  │           │          │           │        │          │
  │           │─결제 요청 (REST)─────▶│        │          │
  │           │◀─결제 결과───────────│        │          │
  │           │          │           │        │          │
  │           │─이력 저장 + next_billing_at 갱신────▶│    │
  │           │          │           │        │          │
  │           │─[성공] billing.executed 발행──────────────▶│
  │           │          │           │        │          │
  │           │─[실패, retry < 3]    │        │          │
  │           │  billing.retry 발행 (지수 백오프)─────────▶│
  │           │          │           │        │          │
  │           │─[실패, retry >= 3]   │        │          │
  │           │  status=PAUSED       │        │          │
  │           │  billing.dlq 발행────────────────────────▶│
  │           │          │           │        │          │
  │           │─분산 락 해제─────▶│   │        │          │
```

---

# 12. AWS 아키텍처 (v3 보강)

## 3단계 구성 (v3: ArgoCD 분리)

### Phase 1: 로컬 개발 (비용 $0)
- Docker Compose로 전체 MSA 구동
- 코드 레벨 학습에 집중 (Kafka, Redis, MSA 패턴)

### Phase 2: AWS 배포 — EC2 분리 (비용 ~$15/월)
- EC2 2대 (WAS / 미들웨어) + RDS + ALB
- Docker Compose로 각 EC2에 서비스 배포
- GitHub Actions CI → SSH 배포
- Security Group, Subnet 분리 경험

### Phase 3: GitOps 배포 (비용 ~$20/월)
- ArgoCD 도입 (EC2-A에 설치)
- GitHub Actions CI → ECR → ArgoCD CD
- 선언적 배포, 자동 롤백 경험

## Phase 2 인프라 상세

```
┌─ AWS ap-northeast-2 (서울), AZ: ap-northeast-2a ──────────────────┐
│                                                                    │
│  ┌─ VPC: 10.0.0.0/16 ──────────────────────────────────────────┐  │
│  │                                                              │  │
│  │  ┌─ Public Subnet: 10.0.1.0/24 ──────────────────────────┐  │  │
│  │  │                                                        │  │  │
│  │  │  ALB                                                   │  │  │
│  │  │  ├── Listener: HTTPS:443 (ACM 인증서)                 │  │  │
│  │  │  ├── Target Group: EC2-A:8080 (API Gateway)           │  │  │
│  │  │  └── Health Check: GET /actuator/health, 30초 간격     │  │  │
│  │  │                                                        │  │  │
│  │  │  EC2-A: t3.small (권장) 또는 t3.micro — WAS 서버                            │  │  │
│  │  │  ├── Docker: API Gateway (:8080)                       │  │  │
│  │  │  ├── Docker: Payment Service (:8081)                   │  │  │
│  │  │  ├── Docker: Billing Service (:8082)                   │  │  │
│  │  │  ├── Docker: Token Service (:8083)                     │  │  │
│  │  │  └── Docker: Notification Service (:8084)              │  │  │
│  │  │  EBS: 30GB gp2                                         │  │  │
│  │  │  Elastic IP: 고정 퍼블릭 IP                             │  │  │
│  │  │                                                        │  │  │
│  │  └────────────────────────────────────────────────────────┘  │  │
│  │                                                              │  │
│  │  ┌─ Private Subnet: 10.0.2.0/24 ─────────────────────────┐  │  │
│  │  │                                                        │  │  │
│  │  │  EC2-B: t3.small (권장) 또는 t3.micro — 미들웨어 서버                       │  │  │
│  │  │  ├── Docker: Kafka + Zookeeper                         │  │  │
│  │  │  └── Docker: Redis                                     │  │  │
│  │  │  EBS: 20GB gp2                                         │  │  │
│  │  │                                                        │  │  │
│  │  │  RDS: db.t3.micro (db.t2.micro 단종) — DB 서버                            │  │  │
│  │  │  └── PostgreSQL 16, 20GB gp2, 단일 AZ                  │  │  │
│  │  │                                                        │  │  │
│  │  └────────────────────────────────────────────────────────┘  │  │
│  │                                                              │  │
│  └──────────────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────────────┘
```

## Security Group

| SG 이름 | 포트 | 소스 | 용도 |
|---------|------|------|------|
| sg-alb | 443 HTTPS | 0.0.0.0/0 | 외부 트래픽 |
| sg-was | 8080-8084 | sg-alb | ALB → WAS |
| sg-was | 22 SSH | 내 IP만 | 관리자 접속 |
| sg-middleware | 9092 Kafka | sg-was | WAS → Kafka |
| sg-middleware | 6379 Redis | sg-was | WAS → Redis |
| sg-middleware | 22 SSH | sg-was | WAS → 미들웨어 SSH |
| sg-db | 5432 PostgreSQL | sg-was | WAS → DB |

---

# 13. 비용 시뮬레이션 (v3.2 AWS 공식 가격 기반)

> ⚠️ AWS 공식 요금 페이지 기준 (서울 리전 ap-northeast-2, 2026년 5월 시점).
> 상세 검증 내용은 별도 문서 `aws_cost_verification.md` 참조.

## 핵심 변경 사항 (v3.1 → v3.2)

| 항목 | v3.1 (잘못된 수치) | v3.2 (공식 가격) |
|------|------------------|----------------|
| t2.micro 월 비용 | $9.5 | **$10.51** |
| 권장 인스턴스 | t2.micro | **t3.micro ($9.49)** |
| RDS 인스턴스 | db.t2.micro | **db.t3.micro** (db.t2.micro 단종) |
| EBS 권장 | gp2 | **gp3 (20% 저렴)** |
| Elastic IP 연결 시 | 무료 | **$3.65/월** (2024.02.01 정책 변경) |
| 데이터 전송 프리티어 | 월 1GB | **월 100GB** (2024.02.01 확대) |

## AWS 프리티어 정책

- **7/15 이전 생성:** 기존 프리티어 (EC2 750h, RDS 750h, ALB 750h, 12개월)
- **7/15 이후 생성:** $200 크레딧 (6개월간 자유 사용)

## ALB 과금 구조

ALB는 **상시 존재 시간 기반 과금 + LCU 사용량 과금**. 트래픽이 없어도 ALB가 존재하면 과금.
- 기본: **$0.0225/hr × 730h = 월 $16.43**
- LCU: $0.008/LCU-hr (트래픽 비례)
- 프리티어(7/15 이전): 750h + 15 LCU, 12개월

**결론:** ALB는 프리티어 만료 시 가장 큰 비용 부담. **부하 테스트 시에만 생성·삭제 권장**.

## 인스턴스 권장 선택

| 용도 | 인스턴스 | 시간당 | 월 비용 | 비고 |
|------|---------|--------:|--------:|------|
| WAS (Spring Boot 5개) | t3.small (2GB RAM) | $0.0260 | $18.98 | 권장 |
| WAS (학습 최소) | t3.micro (1GB RAM) | $0.0130 | $9.49 | 메모리 빠듯 |
| 미들웨어 (Kafka+Redis) | t3.small 또는 t3.medium | $0.0260~$0.0520 | $18.98~$37.96 | 권장 |
| RDS (PostgreSQL) | db.t3.micro | $0.026 | $18.98 | 프리티어 대상 |
| RDS (만료 후 최저가) | db.t4g.micro (ARM) | $0.023 | $16.79 | 11% 절감 |

> **t3.micro 1GB로 Spring Boot 5개 + Kafka + Redis 구동은 불가능.** 5개 서비스를 분산하거나 t3.small 이상 사용 필요.

## 비용 요약 표

| 구분 | 예상 비용 | 설명 |
|------|---------:|------|
| 로컬 개발 (Phase 1) | **$0** | Docker Compose 기반 |
| AWS 최소 학습 (프리티어, 7/15 이전) | **~$15/월** | EC2 2대 + RDS + ALB 프리티어 활용 |
| AWS 학습 ($200 크레딧 계정, ALB 제외) | **~$49/월** | 6개월 ÷ $200 ≈ $33/월 한도 초과 |
| AWS 학습 ($200 크레딧 계정, ALB 포함) | **~$65/월** | 크레딧 약 3개월 소진 |
| 프리티어 만료 후 절감형 | **~$47/월** | EC2 2대 + RDS, ALB 없음 (Nginx 대체) |
| 최저 비용 (EC2 1대 통합, 하루 8시간) | **~$21/월** | t3.medium 1대, Docker로 모두 통합 |

## 시나리오별 상세 비용

### 시나리오 A: 프리티어 (7/15 이전 계정)

| 항목 | 월 비용 |
|------|--------:|
| EC2-A (t3.micro) | $0 |
| EC2-B (t3.micro, 2대째) | **$9.49** |
| RDS (db.t3.micro) | $0 |
| ALB (프리티어 내) | $0 |
| EBS (EC2-A 30GB gp3) | $0 |
| EBS (EC2-B 20GB gp3) | **$1.82** |
| Elastic IP (연결 중) | **$3.65** |
| 데이터 전송 (< 100GB) | $0 |
| **합계** | **~$15/월** |

### 시나리오 B: $200 크레딧 계정 (7/15 이후)

| 항목 | 월 비용 |
|------|--------:|
| EC2 2대 (t3.micro) | $18.98 |
| RDS (db.t3.micro + 20GB) | $21.60 |
| ALB (상시) | $16.43 |
| EBS (2대, gp3) | $4.56 |
| Elastic IP | $3.65 |
| **합계 (ALB 포함)** | **~$65/월** (크레딧 ~3개월) |
| **합계 (ALB 제외, Nginx)** | **~$49/월** (크레딧 ~4개월) |

### 시나리오 C: 프리티어 만료 후 절감형

| 항목 | 월 비용 |
|------|--------:|
| EC2 2대 (t3.micro) | $18.98 |
| RDS (db.t4g.micro + 20GB gp3) | $19.09 |
| EBS (2대, gp3) | $4.56 |
| Elastic IP | $3.65 |
| 데이터 전송 | ~$1 |
| **합계** | **~$47/월** (약 62,000원) |

## 비용 절감 전략

| 전략 | 절감 효과 | 트레이드오프 |
|------|----------|------------|
| EC2 사용 안 할 때 중지 | EC2 비용 최대 66% | Elastic IP는 그대로 과금 |
| ALB는 부하 테스트 시에만 생성·삭제 | 월 $16 | 평소 직접 IP 접속 |
| Elastic IP 해제 | 월 $3.65 | 재시작 시 IP 변경 |
| Spot Instance (EC2-B) | 60~70% 할인 | 중단 가능성 |
| RDS → Docker PostgreSQL | 월 $19 | 관리형 DB 경험 포기 |
| db.t4g.micro 사용 | 월 $2 | ARM 호환성 확인 필요 |
| EBS gp2 → gp3 | 20% 절감 | 없음 (gp3 권장) |
| 데이터 전송 100GB 이내 | 사실상 무료 | 학습용으로 충분 |

## ⚠️ 비용 함정 주의사항

- **NAT Gateway 절대 사용 금지:** 월 $43 + 데이터 처리 별도
- **Elastic IP는 연결 중에도 과금:** 2024.02.01 정책 변경
- **ALB는 트래픽 0이어도 과금:** 시간당 과금 구조
- **다른 AZ 트래픽 과금:** $0.01/GB 양방향. 모든 리소스 같은 AZ 배치
- **t3.micro 1GB RAM으로 5개 Spring Boot 불가:** t3.small 이상 권장

---

# 14. 배포 파이프라인

## Phase 2: GitHub Actions + SSH

```
코드 Push (GitHub)
     │
     ▼
GitHub Actions (CI)
     ├── ./gradlew :services:{service}:build
     ├── ./gradlew :services:{service}:test
     ├── docker build + docker save (tar)
     └── SSH → EC2-A
          ├── docker load (tar → image)
          ├── docker-compose down {service}
          └── docker-compose up -d {service}
```

## Phase 3: EKS 또는 k3s + ArgoCD (별도 스프린트)

> ArgoCD는 Kubernetes 기반 GitOps 도구이므로, EC2 + Docker Compose 구조(Phase 2)와 맞지 않는다.
> Phase 3에서는 EKS(관리형) 또는 EC2 위 k3s(경량 K8s)를 도입해야 ArgoCD가 동작한다.

```
Phase 3 배포 흐름:

코드 Push (GitHub)
     │
     ▼
GitHub Actions (CI)
     ├── 빌드 + 테스트
     ├── Docker build → ECR push
     └── k8s manifest image tag 업데이트
              │
              ▼
ArgoCD (CD) — k3s 또는 EKS 클러스터에 설치
     ├── Git repo의 k8s/ 디렉토리 변경 감지
     ├── Kubernetes Rolling Update
     ├── Actuator Health Check
     └── 실패 시 자동 롤백
```

| 옵션 | 비용 | 장점 | 단점 |
|------|------|------|------|
| k3s (EC2 위) | EC2 비용만 | 가볍고 학습에 적합 | 직접 설치·관리 |
| EKS | $0.10/hr ≈ 월 $72 + EC2 | 관리형, 실무에 가까움 | 학습용으로는 비쌈 |

> 학습용 권장: EC2 위에 k3s 설치 → ArgoCD 배포 → GitOps 경험

---

# 15. 부하 테스트 계획

## 테스트 시나리오

| 시나리오 | 목표 TPS | 지속 시간 | 검증 포인트 |
|---------|---------|----------|------------|
| 단건 결제 | 100 | 3분 | P95 < 500ms |
| 동시 결제 (같은 주문) | 50 | 1분 | 멱등성 보장, 중복 0건 |
| 빌링 동시 실행 | 30 | 1분 | Redis 분산 락 정상 |
| 토큰 조회 (핫키) | 500 | 2분 | 캐시 히트율 > 95% |
| 복합 시나리오 | 200 | 5분 | Kafka 이벤트 유실 0건 |
| 피크 트래픽 | 500 | 1분 | 시스템 한계점 확인 |

## 모니터링 지표

| 지표 | 임계치 |
|------|--------|
| API 응답시간 P95 | < 500ms |
| 에러율 | < 1% |
| Kafka Consumer Lag | < 100 |
| Redis 히트율 | > 95% |
| DB 커넥션 사용률 | < 80% |
| JVM Heap 사용률 | < 80% |

---

# 16. 구현 우선순위 (스프린트) — v3 수정

## Sprint 1: 기반 구축 (1주)
- [ ] 멀티모듈 Gradle 프로젝트 스켈레톤
- [ ] Docker Compose (PostgreSQL + Kafka + Redis)
- [ ] DB 스키마 (v3: partial_cancellations, processed_events 포함)
- [ ] Payment Service: 결제 승인/조회/취소 API
- [ ] Mock PG Client
- [ ] TID 생성 (v3: Redis INCR + DB UNIQUE)

## Sprint 2: 이벤트 기반 + 캐시 (1주)
- [ ] 아웃박스 패턴 (v3: retry_count 포함)
- [ ] Notification Service: Kafka Consumer + 수동 커밋
- [ ] Consumer 멱등성 (v3: processed_events)
- [ ] Token Service: 토큰 발급/조회 + AES-256-CBC (v3: CVC 미저장)
- [ ] Redis 캐시 (Cache-Aside) + 멱등성 키
- [ ] 간편결제 수단 등록/목록

## Sprint 3: 빌링 + API Gateway (1주)
- [ ] Billing Service: 플랜 등록/실행/해지
- [ ] Redis 분산 락 (Redisson)
- [ ] 빌링 재시도 + DLQ (v3: 지수 백오프 + billing.dlq)
- [ ] API Gateway: 라우팅 + JWT 인증
- [ ] Rate Limiting (v3: Sliding Window Counter)
- [ ] 서킷브레이커 (Resilience4j)
- [ ] 부분 취소 (v3: partial_cancellations)

## Sprint 4: AWS 배포 + 부하 테스트 (1주)
- [ ] AWS VPC + Subnet + Security Group
- [ ] EC2 2대 + RDS + ALB
- [ ] GitHub Actions CI + SSH 배포
- [ ] k6 부하 테스트 (6개 시나리오)
- [ ] CloudWatch 모니터링 + 알림
- [ ] 병목 분석 및 튜닝

## Sprint 5 (선택): ArgoCD GitOps
- [ ] EC2-A에 ArgoCD 설치
- [ ] ECR 레지스트리 구성
- [ ] GitHub Actions → ECR → ArgoCD 파이프라인
- [ ] 자동 롤백 테스트

---

# 17. 면접 활용 매핑

| 면접 질문 | 답변 근거 |
|----------|----------|
| "Kafka 경험?" | 아웃박스 패턴(재시도 포함), DLQ, Consumer Group, 수동 커밋, Consumer 멱등성 |
| "Redis 경험?" | 분산 락(Redisson), Cache-Aside, Sliding Window Rate Limiting, TID 채번, 멱등성 키 |
| "MSA 설계?" | 5개 서비스 DDD 분리, API Gateway, 서킷브레이커, REST + 이벤트 기반 통신 |
| "AWS 경험?" | VPC/Subnet/SG 설계, EC2 + RDS + ALB, Phase별 배포 전략 |
| "대량 트래픽?" | k6 500 TPS 부하 테스트, 병목 분석, 커넥션 풀 튜닝 |
| "이벤트 기반 아키텍처?" | 아웃박스 패턴 + at-least-once + Consumer 멱등성 |
| "트랜잭션 관리?" | JPA @Transactional, 아웃박스 동일 TX, 분산 락, 멱등성 |
| "보안?" | AES-256-CBC, CVC 미저장(PCI-DSS), JWT, SG 계층화, Secrets Manager |
| "DLQ가 뭔가?" | 재시도 초과 건을 별도 토픽에 격리, 수동 처리 후 재투입 |
| "부분 취소?" | partial_cancellations 테이블, 잔액 관리, 상태 전이 규칙 |
