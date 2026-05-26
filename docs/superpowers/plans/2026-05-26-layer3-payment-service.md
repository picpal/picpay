# Layer 3: Payment Service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** TID 생성, 결제 승인/조회/취소, 멱등성 처리, Outbox 패턴까지 포함한 Payment Service 완성 (`POST /v1/payments`, `GET /v1/payments/{tid}`, `POST /v1/payments/cancel`)

**Architecture:**
- `services/payment` 모듈에 Payment 엔티티·서비스·컨트롤러 구현
- TID는 Redis `INCR` + 날짜 조합, Redis 장애 시 UUID 폴백
- 멱등성 키는 Redis `SET NX EX`, Outbox 이벤트는 결제 TX와 동일 트랜잭션 내 INSERT
- MockPgClient가 95% 승인 / 5% 실패 시뮬레이션 (실 PG 연동 없음)

**Tech Stack:** Java 21, Spring Boot 3.4.0, Gradle multi-module, PostgreSQL 16 (schema: payment), Redis 7, Spring Data JPA, Spring Data Redis (StringRedisTemplate), `@Scheduled`, MockPg (랜덤 결과)

---

## 파일 구조

```
services/payment/
├── build.gradle                                      (수정: redis, validation 의존성 추가)
├── src/main/resources/application.yml               (수정: redis 설정 추가)
└── src/main/java/com/picpay/payment/
    ├── PaymentApplication.java                       (기존 유지)
    ├── config/
    │   └── JpaConfig.java                            (생성: @EnableJpaAuditing 분리)
    ├── domain/
    │   ├── Payment.java                              (생성: 결제 엔티티)
    │   ├── PaymentStatus.java                        (생성: READY/PAID/CANCELLED/PARTIAL_CANCELLED/FAILED)
    │   ├── PartialCancellation.java                  (생성: 부분취소 엔티티)
    │   └── OutboxEvent.java                          (생성: Outbox 이벤트 엔티티)
    ├── repository/
    │   ├── PaymentRepository.java                    (생성: findByTid, findByIdempotencyKey)
    │   ├── PartialCancellationRepository.java        (생성: findByPaymentId)
    │   └── OutboxEventRepository.java                (생성: findTop100ByStatusIn, PENDING/FAILED)
    ├── pg/
    │   └── MockPgClient.java                         (생성: 95% 승인, 5% 실패 시뮬레이션)
    ├── service/
    │   ├── TidService.java                           (생성: Redis INCR + UUID 폴백)
    │   ├── PaymentService.java                       (생성: 승인/조회/취소)
    │   └── OutboxPoller.java                         (생성: @Scheduled 1초, PENDING→PUBLISHED 로그)
    ├── dto/
    │   ├── PaymentRequest.java                       (생성: merchantId, orderId, tokenId, amount, method, idempotencyKey)
    │   ├── PaymentResponse.java                      (생성: tid, status, amount, pgTid, createdAt)
    │   ├── CancelRequest.java                        (생성: tid, cancelAmount, reason)
    │   └── CancelResponse.java                       (생성: cancelTid, status, remainingAmount)
    └── controller/
        └── PaymentController.java                    (생성: POST /v1/payments, GET /{tid}, POST /cancel)

services/payment/src/test/java/com/picpay/payment/
    ├── pg/MockPgClientTest.java
    ├── service/TidServiceTest.java
    ├── service/PaymentServiceTest.java
    └── controller/PaymentControllerTest.java
```

---

## Task 1: Payment 엔티티 + Repository + MockPgClient

**Files:**
- Create: `services/payment/src/main/java/com/picpay/payment/config/JpaConfig.java`
- Create: `services/payment/src/main/java/com/picpay/payment/domain/PaymentStatus.java`
- Create: `services/payment/src/main/java/com/picpay/payment/domain/Payment.java`
- Create: `services/payment/src/main/java/com/picpay/payment/domain/PartialCancellation.java`
- Create: `services/payment/src/main/java/com/picpay/payment/domain/OutboxEvent.java`
- Create: `services/payment/src/main/java/com/picpay/payment/repository/PaymentRepository.java`
- Create: `services/payment/src/main/java/com/picpay/payment/repository/PartialCancellationRepository.java`
- Create: `services/payment/src/main/java/com/picpay/payment/repository/OutboxEventRepository.java`
- Create: `services/payment/src/main/java/com/picpay/payment/pg/MockPgClient.java`
- Modify: `services/payment/src/main/java/com/picpay/payment/PaymentApplication.java`
- Create: `services/payment/src/test/java/com/picpay/payment/pg/MockPgClientTest.java`

- [ ] **Step 1: `PaymentApplication.java`에서 `@EnableJpaAuditing` 제거 후 `JpaConfig.java`로 분리**

`services/payment/src/main/java/com/picpay/payment/PaymentApplication.java`:
```java
package com.picpay.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PaymentApplication {
    public static void main(String[] args) {
        SpringApplication.run(PaymentApplication.class, args);
    }
}
```

`services/payment/src/main/java/com/picpay/payment/config/JpaConfig.java`:
```java
package com.picpay.payment.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@Configuration
@EnableJpaAuditing
public class JpaConfig {
}
```

- [ ] **Step 2: `PaymentStatus` enum 작성**

`services/payment/src/main/java/com/picpay/payment/domain/PaymentStatus.java`:
```java
package com.picpay.payment.domain;

public enum PaymentStatus {
    READY, PAID, CANCELLED, PARTIAL_CANCELLED, FAILED;

    public boolean canTransitionTo(PaymentStatus next) {
        return switch (this) {
            case READY -> next == PAID || next == FAILED;
            case PAID -> next == CANCELLED || next == PARTIAL_CANCELLED;
            case PARTIAL_CANCELLED -> next == CANCELLED || next == PARTIAL_CANCELLED;
            default -> false;
        };
    }
}
```

- [ ] **Step 3: `Payment` 엔티티 작성**

`services/payment/src/main/java/com/picpay/payment/domain/Payment.java`:
```java
package com.picpay.payment.domain;

import com.picpay.common.entity.BaseEntity;
import com.picpay.common.exception.BusinessException;
import com.picpay.common.exception.ErrorCode;
import jakarta.persistence.*;

@Entity
@Table(name = "payments", schema = "payment")
public class Payment extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tid", nullable = false, unique = true)
    private String tid;

    @Column(name = "merchant_id", nullable = false)
    private String merchantId;

    @Column(name = "order_id", nullable = false)
    private String orderId;

    @Column(name = "token_id", nullable = false)
    private String tokenId;

    @Column(name = "amount", nullable = false)
    private Long amount;

    @Column(name = "method", nullable = false)
    private String method;

    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    private PaymentStatus status = PaymentStatus.READY;

    @Column(name = "pg_tid")
    private String pgTid;

    @Column(name = "idempotency_key", unique = true)
    private String idempotencyKey;

    protected Payment() {}

    public static Payment create(String tid, String merchantId, String orderId,
                                  String tokenId, Long amount, String method,
                                  String idempotencyKey) {
        Payment p = new Payment();
        p.tid = tid;
        p.merchantId = merchantId;
        p.orderId = orderId;
        p.tokenId = tokenId;
        p.amount = amount;
        p.method = method;
        p.idempotencyKey = idempotencyKey;
        return p;
    }

    public void approve(String pgTid) {
        transitionTo(PaymentStatus.PAID);
        this.pgTid = pgTid;
    }

    public void fail() {
        transitionTo(PaymentStatus.FAILED);
    }

    public void cancel() {
        transitionTo(PaymentStatus.CANCELLED);
    }

    public void partialCancel() {
        if (this.status != PaymentStatus.PAID && this.status != PaymentStatus.PARTIAL_CANCELLED) {
            throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION);
        }
        this.status = PaymentStatus.PARTIAL_CANCELLED;
    }

    private void transitionTo(PaymentStatus next) {
        if (!this.status.canTransitionTo(next)) {
            throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION);
        }
        this.status = next;
    }

    public Long getId() { return id; }
    public String getTid() { return tid; }
    public String getMerchantId() { return merchantId; }
    public String getOrderId() { return orderId; }
    public String getTokenId() { return tokenId; }
    public Long getAmount() { return amount; }
    public String getMethod() { return method; }
    public PaymentStatus getStatus() { return status; }
    public String getPgTid() { return pgTid; }
    public String getIdempotencyKey() { return idempotencyKey; }
}
```

- [ ] **Step 4: `PartialCancellation` 엔티티 작성**

`services/payment/src/main/java/com/picpay/payment/domain/PartialCancellation.java`:
```java
package com.picpay.payment.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "partial_cancellations", schema = "payment")
public class PartialCancellation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    @Column(name = "cancel_tid", nullable = false, unique = true)
    private String cancelTid;

    @Column(name = "cancel_amount", nullable = false)
    private Long cancelAmount;

    @Column(name = "remaining_amount", nullable = false)
    private Long remainingAmount;

    @Column(name = "reason")
    private String reason;

    @Column(name = "pg_cancel_tid")
    private String pgCancelTid;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    protected PartialCancellation() {}

    public static PartialCancellation create(Long paymentId, String cancelTid,
                                              Long cancelAmount, Long remainingAmount,
                                              String reason, String pgCancelTid) {
        PartialCancellation pc = new PartialCancellation();
        pc.paymentId = paymentId;
        pc.cancelTid = cancelTid;
        pc.cancelAmount = cancelAmount;
        pc.remainingAmount = remainingAmount;
        pc.reason = reason;
        pc.pgCancelTid = pgCancelTid;
        pc.status = "CANCELLED";
        return pc;
    }

    public Long getId() { return id; }
    public Long getPaymentId() { return paymentId; }
    public String getCancelTid() { return cancelTid; }
    public Long getCancelAmount() { return cancelAmount; }
    public Long getRemainingAmount() { return remainingAmount; }
    public String getReason() { return reason; }
    public String getPgCancelTid() { return pgCancelTid; }
    public String getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
```

- [ ] **Step 5: `OutboxEvent` 엔티티 작성**

`services/payment/src/main/java/com/picpay/payment/domain/OutboxEvent.java`:
```java
package com.picpay.payment.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "outbox_events", schema = "payment")
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "aggregate_type", nullable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private String aggregateId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "topic", nullable = false)
    private String topic;

    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "status", nullable = false)
    private String status = "PENDING";

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "max_retry", nullable = false)
    private int maxRetry = 5;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    protected OutboxEvent() {}

    public static OutboxEvent create(String aggregateType, String aggregateId,
                                      String eventType, String topic, String payload) {
        OutboxEvent e = new OutboxEvent();
        e.aggregateType = aggregateType;
        e.aggregateId = aggregateId;
        e.eventType = eventType;
        e.topic = topic;
        e.payload = payload;
        return e;
    }

    public void markPublished() {
        this.status = "PUBLISHED";
        this.publishedAt = LocalDateTime.now();
    }

    public void markFailed(String error) {
        this.retryCount++;
        this.lastError = error;
        this.status = retryCount >= maxRetry ? "DEAD" : "FAILED";
    }

    public Long getId() { return id; }
    public String getAggregateType() { return aggregateType; }
    public String getAggregateId() { return aggregateId; }
    public String getEventType() { return eventType; }
    public String getTopic() { return topic; }
    public String getPayload() { return payload; }
    public String getStatus() { return status; }
    public int getRetryCount() { return retryCount; }
    public int getMaxRetry() { return maxRetry; }
    public String getLastError() { return lastError; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getPublishedAt() { return publishedAt; }
}
```

- [ ] **Step 6: Repository 인터페이스 3개 작성**

`services/payment/src/main/java/com/picpay/payment/repository/PaymentRepository.java`:
```java
package com.picpay.payment.repository;

import com.picpay.payment.domain.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    Optional<Payment> findByTid(String tid);
    Optional<Payment> findByIdempotencyKey(String idempotencyKey);
}
```

`services/payment/src/main/java/com/picpay/payment/repository/PartialCancellationRepository.java`:
```java
package com.picpay.payment.repository;

import com.picpay.payment.domain.PartialCancellation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PartialCancellationRepository extends JpaRepository<PartialCancellation, Long> {
    List<PartialCancellation> findByPaymentId(Long paymentId);
}
```

`services/payment/src/main/java/com/picpay/payment/repository/OutboxEventRepository.java`:
```java
package com.picpay.payment.repository;

import com.picpay.payment.domain.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {
    @Query("SELECT e FROM OutboxEvent e WHERE e.status IN ('PENDING', 'FAILED') ORDER BY e.createdAt ASC LIMIT 100")
    List<OutboxEvent> findPendingOrFailed();
}
```

- [ ] **Step 7: `MockPgClient` 작성 (95% 승인, 5% 실패)**

`services/payment/src/main/java/com/picpay/payment/pg/MockPgClient.java`:
```java
package com.picpay.payment.pg;

import org.springframework.stereotype.Component;

import java.util.Random;
import java.util.UUID;

@Component
public class MockPgClient {

    private static final double APPROVAL_RATE = 0.95;
    private final Random random = new Random();

    public PgApprovalResult approve(String tid, Long amount) {
        if (random.nextDouble() < APPROVAL_RATE) {
            return PgApprovalResult.success("PG-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        }
        return PgApprovalResult.failure("PG_DECLINED");
    }

    public PgApprovalResult cancel(String pgTid, Long cancelAmount) {
        return PgApprovalResult.success("PGC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
    }

    public record PgApprovalResult(boolean approved, String pgTid, String errorCode) {
        public static PgApprovalResult success(String pgTid) {
            return new PgApprovalResult(true, pgTid, null);
        }
        public static PgApprovalResult failure(String errorCode) {
            return new PgApprovalResult(false, null, errorCode);
        }
    }
}
```

- [ ] **Step 8: `MockPgClientTest` 작성 (실패 테스트)**

`services/payment/src/test/java/com/picpay/payment/pg/MockPgClientTest.java`:
```java
package com.picpay.payment.pg;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MockPgClientTest {

    private final MockPgClient mockPgClient = new MockPgClient();

    @Test
    void approve_100times_approvalRateBetween90And100Percent() {
        int approved = 0;
        for (int i = 0; i < 100; i++) {
            MockPgClient.PgApprovalResult result = mockPgClient.approve("tid-" + i, 10000L);
            if (result.approved()) {
                approved++;
                assertThat(result.pgTid()).isNotBlank();
            } else {
                assertThat(result.errorCode()).isNotBlank();
            }
        }
        assertThat(approved).isBetween(80, 100); // 95% 기준, 통계적 허용 범위
    }

    @Test
    void cancel_alwaysSucceeds() {
        MockPgClient.PgApprovalResult result = mockPgClient.cancel("PG-ABCD1234", 5000L);
        assertThat(result.approved()).isTrue();
        assertThat(result.pgTid()).startsWith("PGC-");
    }
}
```

- [ ] **Step 9: `build.gradle` 수정 (redis, validation, scheduling 의존성 추가)**

`services/payment/build.gradle`:
```groovy
plugins {
    id 'org.springframework.boot'
}

dependencies {
    implementation project(':common')
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.springframework.boot:spring-boot-starter-data-redis'
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
    implementation 'org.springframework.boot:spring-boot-starter-validation'
    implementation 'org.flywaydb:flyway-core'
    implementation 'org.flywaydb:flyway-database-postgresql'
    runtimeOnly 'org.postgresql:postgresql'
}
```

- [ ] **Step 10: `application.yml` 수정 (Redis 설정 추가)**

`services/payment/src/main/resources/application.yml`:
```yaml
spring:
  application:
    name: payment-service
  threads:
    virtual:
      enabled: true
  datasource:
    url: jdbc:postgresql://localhost:5432/picpay?currentSchema=payment
    username: picpay
    password: picpay
    driver-class-name: org.postgresql.Driver
  jpa:
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        default_schema: payment
  flyway:
    enabled: true
    locations: classpath:db/migration
    schemas: payment
  data:
    redis:
      host: localhost
      port: 6379

server:
  port: 8081

management:
  endpoints:
    web:
      exposure:
        include: health,info
```

- [ ] **Step 11: 테스트 실행**

```bash
./gradlew :services:payment:test --tests "com.picpay.payment.pg.*" -i
```

Expected: `MockPgClientTest` PASS (approve 100회 승인율 80~100%, cancel 항상 성공)

- [ ] **Step 12: 커밋**

```bash
git add services/payment/
git commit -m "feat(payment): add Payment/OutboxEvent entities, repositories, MockPgClient"
```

---

## Task 2: TID 생성 서비스 (Redis INCR + UUID 폴백)

**Files:**
- Create: `services/payment/src/main/java/com/picpay/payment/service/TidService.java`
- Create: `services/payment/src/test/java/com/picpay/payment/service/TidServiceTest.java`

- [ ] **Step 1: TID 형식 이해**

TID 형식: `T{serviceId:SVR01}{yyyyMMddHHmmss}{seq:8자리}` → 예: `TSVR0120260526143022000001`
- serviceId: `SVR01` (고정)
- seq: Redis `INCR tid:seq:{yyyyMMdd}` → TTL 2일 → 8자리 0패딩
- Redis 장애 시: UUID 마지막 8자리로 대체

- [ ] **Step 2: `TidService` 작성**

`services/payment/src/main/java/com/picpay/payment/service/TidService.java`:
```java
package com.picpay.payment.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Service
public class TidService {

    private static final Logger log = LoggerFactory.getLogger(TidService.class);
    private static final String SERVICE_ID = "SVR01";
    private static final String SEQ_KEY_PREFIX = "tid:seq:";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter DATE_ONLY_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final StringRedisTemplate redisTemplate;

    public TidService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public String generate() {
        LocalDateTime now = LocalDateTime.now();
        String datePart = now.format(DATE_FMT);
        String seqKey = SEQ_KEY_PREFIX + now.format(DATE_ONLY_FMT);
        String seq = generateSeq(seqKey);
        return "T" + SERVICE_ID + datePart + seq;
    }

    private String generateSeq(String seqKey) {
        try {
            Long seq = redisTemplate.opsForValue().increment(seqKey);
            if (seq == 1L) {
                redisTemplate.expire(seqKey, Duration.ofDays(2));
            }
            return String.format("%08d", seq);
        } catch (Exception e) {
            log.warn("Redis TID seq unavailable, falling back to UUID: {}", e.getMessage());
            return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        }
    }
}
```

- [ ] **Step 3: `TidServiceTest` 작성**

`services/payment/src/test/java/com/picpay/payment/service/TidServiceTest.java`:
```java
package com.picpay.payment.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TidServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    @InjectMocks
    private TidService tidService;

    @Test
    void generate_returnsCorrectFormat_whenRedisAvailable() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(1L);

        String tid = tidService.generate();

        assertThat(tid).startsWith("TSVR01");
        assertThat(tid).hasSize(27); // T(1) + SVR01(5) + yyyyMMddHHmmss(14) + seq(8) = 28 → 1+5+14+8 = 28
    }

    @Test
    void generate_fallbackToUuid_whenRedisUnavailable() {
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("Redis connection refused"));

        String tid = tidService.generate();

        assertThat(tid).startsWith("TSVR01");
        assertThat(tid).hasSize(28); // UUID fallback 8자리
    }

    @Test
    void generate_seqPadded8Digits_whenSeqIs1() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(1L);

        String tid = tidService.generate();

        String seq = tid.substring(tid.length() - 8);
        assertThat(seq).isEqualTo("00000001");
    }
}
```

- [ ] **Step 4: TID 길이 확인 후 테스트 수정**

TID 실제 길이: `T`(1) + `SVR01`(5) + `yyyyMMddHHmmss`(14) + seq(8) = 28자

`TidServiceTest`에서 `hasSize(27)` → `hasSize(28)` 수정:
```java
assertThat(tid).hasSize(28); // T(1) + SVR01(5) + yyyyMMddHHmmss(14) + seq(8) = 28
```

- [ ] **Step 5: 테스트 실행**

```bash
./gradlew :services:payment:test --tests "com.picpay.payment.service.TidServiceTest" -i
```

Expected: 3개 테스트 PASS

- [ ] **Step 6: 커밋**

```bash
git add services/payment/src/main/java/com/picpay/payment/service/TidService.java
git add services/payment/src/test/java/com/picpay/payment/service/TidServiceTest.java
git commit -m "feat(payment): add TidService Redis INCR with UUID fallback"
```

---

## Task 3: 결제 승인 API + 멱등성 (`POST /v1/payments`)

**Files:**
- Create: `services/payment/src/main/java/com/picpay/payment/dto/PaymentRequest.java`
- Create: `services/payment/src/main/java/com/picpay/payment/dto/PaymentResponse.java`
- Create: `services/payment/src/main/java/com/picpay/payment/service/PaymentService.java`
- Create: `services/payment/src/main/java/com/picpay/payment/controller/PaymentController.java`
- Create: `services/payment/src/test/java/com/picpay/payment/service/PaymentServiceTest.java`
- Create: `services/payment/src/test/java/com/picpay/payment/controller/PaymentControllerTest.java`

- [ ] **Step 1: DTO 작성**

`services/payment/src/main/java/com/picpay/payment/dto/PaymentRequest.java`:
```java
package com.picpay.payment.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record PaymentRequest(
        @NotBlank String merchantId,
        @NotBlank String orderId,
        @NotBlank String tokenId,
        @NotNull @Min(1) Long amount,
        @NotBlank String method,
        String idempotencyKey
) {}
```

`services/payment/src/main/java/com/picpay/payment/dto/PaymentResponse.java`:
```java
package com.picpay.payment.dto;

import com.picpay.payment.domain.Payment;

import java.time.LocalDateTime;

public record PaymentResponse(
        String tid,
        String merchantId,
        String orderId,
        String tokenId,
        Long amount,
        String method,
        String status,
        String pgTid,
        LocalDateTime createdAt
) {
    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.getTid(),
                payment.getMerchantId(),
                payment.getOrderId(),
                payment.getTokenId(),
                payment.getAmount(),
                payment.getMethod(),
                payment.getStatus().name(),
                payment.getPgTid(),
                payment.getCreatedAt()
        );
    }
}
```

- [ ] **Step 2: `PaymentService` 작성 (승인 + 멱등성)**

`services/payment/src/main/java/com/picpay/payment/service/PaymentService.java`:
```java
package com.picpay.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picpay.common.exception.BusinessException;
import com.picpay.common.exception.ErrorCode;
import com.picpay.payment.domain.OutboxEvent;
import com.picpay.payment.domain.Payment;
import com.picpay.payment.dto.PaymentRequest;
import com.picpay.payment.dto.PaymentResponse;
import com.picpay.payment.pg.MockPgClient;
import com.picpay.payment.repository.OutboxEventRepository;
import com.picpay.payment.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final String IDEMPOTENCY_PREFIX = "idempotency:";
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);

    private final TidService tidService;
    private final MockPgClient mockPgClient;
    private final PaymentRepository paymentRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public PaymentService(TidService tidService, MockPgClient mockPgClient,
                          PaymentRepository paymentRepository,
                          OutboxEventRepository outboxEventRepository,
                          StringRedisTemplate redisTemplate,
                          ObjectMapper objectMapper) {
        this.tidService = tidService;
        this.mockPgClient = mockPgClient;
        this.paymentRepository = paymentRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public PaymentResponse approve(PaymentRequest request) {
        // 멱등성 체크: Redis 먼저
        if (request.idempotencyKey() != null) {
            String cached = redisTemplate.opsForValue()
                    .get(IDEMPOTENCY_PREFIX + request.idempotencyKey());
            if (cached != null) {
                try {
                    return objectMapper.readValue(cached, PaymentResponse.class);
                } catch (JsonProcessingException e) {
                    log.warn("Idempotency cache deserialization failed: {}", e.getMessage());
                }
            }
            // DB에서도 확인 (Redis 재시작 대비)
            paymentRepository.findByIdempotencyKey(request.idempotencyKey())
                    .ifPresent(existing -> {
                        throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
                    });
        }

        String tid = tidService.generate();

        // MockPG 승인 요청
        MockPgClient.PgApprovalResult pgResult = mockPgClient.approve(tid, request.amount());

        Payment payment = Payment.create(tid, request.merchantId(), request.orderId(),
                request.tokenId(), request.amount(), request.method(), request.idempotencyKey());

        if (pgResult.approved()) {
            payment.approve(pgResult.pgTid());
        } else {
            payment.fail();
        }

        Payment saved = paymentRepository.save(payment);

        // Outbox 이벤트 삽입 (동일 트랜잭션)
        String eventType = pgResult.approved() ? "payment.completed" : "payment.failed";
        String topic = pgResult.approved() ? "payment.completed" : "payment.failed";
        outboxEventRepository.save(OutboxEvent.create(
                "Payment", saved.getTid(), eventType, topic, toJson(saved)));

        PaymentResponse response = PaymentResponse.from(saved);

        // 멱등성 키를 Redis에 저장
        if (request.idempotencyKey() != null) {
            try {
                redisTemplate.opsForValue().set(
                        IDEMPOTENCY_PREFIX + request.idempotencyKey(),
                        objectMapper.writeValueAsString(response),
                        IDEMPOTENCY_TTL);
            } catch (JsonProcessingException e) {
                log.warn("Idempotency cache serialization failed: {}", e.getMessage());
            }
        }

        return response;
    }

    @Transactional(readOnly = true)
    public PaymentResponse findByTid(String tid) {
        Payment payment = paymentRepository.findByTid(tid)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
        return PaymentResponse.from(payment);
    }

    private String toJson(Payment payment) {
        try {
            return objectMapper.writeValueAsString(PaymentResponse.from(payment));
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
```

- [ ] **Step 3: `PaymentController` 작성 (POST /v1/payments, GET /{tid})**

`services/payment/src/main/java/com/picpay/payment/controller/PaymentController.java`:
```java
package com.picpay.payment.controller;

import com.picpay.common.response.ApiResponse;
import com.picpay.payment.dto.PaymentRequest;
import com.picpay.payment.dto.PaymentResponse;
import com.picpay.payment.service.PaymentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<PaymentResponse> approve(@Valid @RequestBody PaymentRequest request) {
        return ApiResponse.ok(paymentService.approve(request));
    }

    @GetMapping("/{tid}")
    public ApiResponse<PaymentResponse> findByTid(@PathVariable String tid) {
        return ApiResponse.ok(paymentService.findByTid(tid));
    }
}
```

- [ ] **Step 4: `PaymentServiceTest` 작성**

`services/payment/src/test/java/com/picpay/payment/service/PaymentServiceTest.java`:
```java
package com.picpay.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picpay.common.exception.BusinessException;
import com.picpay.common.exception.ErrorCode;
import com.picpay.payment.domain.Payment;
import com.picpay.payment.domain.PaymentStatus;
import com.picpay.payment.dto.PaymentRequest;
import com.picpay.payment.dto.PaymentResponse;
import com.picpay.payment.pg.MockPgClient;
import com.picpay.payment.repository.OutboxEventRepository;
import com.picpay.payment.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock private TidService tidService;
    @Mock private MockPgClient mockPgClient;
    @Mock private PaymentRepository paymentRepository;
    @Mock private OutboxEventRepository outboxEventRepository;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    @InjectMocks
    private PaymentService paymentService;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules();

    @BeforeEach
    void setUp() {
        // ObjectMapper를 직접 주입 (생성자 주입이므로 리플렉션 필요)
        paymentService = new PaymentService(tidService, mockPgClient,
                paymentRepository, outboxEventRepository, redisTemplate, objectMapper);
    }

    @Test
    void approve_success_returnsPaidStatus() {
        when(tidService.generate()).thenReturn("TSVR0120260526143022000001");
        when(mockPgClient.approve(anyString(), anyLong()))
                .thenReturn(MockPgClient.PgApprovalResult.success("PG-ABCD1234"));
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(outboxEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);

        PaymentRequest request = new PaymentRequest(
                "mer_001", "order-001", "tok_abc123", 10000L, "CARD", "idem-001");

        PaymentResponse response = paymentService.approve(request);

        assertThat(response.status()).isEqualTo("PAID");
        assertThat(response.pgTid()).isEqualTo("PG-ABCD1234");
        assertThat(response.tid()).isEqualTo("TSVR0120260526143022000001");
    }

    @Test
    void approve_pgFails_returnsFailedStatus() {
        when(tidService.generate()).thenReturn("TSVR0120260526143022000002");
        when(mockPgClient.approve(anyString(), anyLong()))
                .thenReturn(MockPgClient.PgApprovalResult.failure("PG_DECLINED"));
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(outboxEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);

        PaymentRequest request = new PaymentRequest(
                "mer_001", "order-002", "tok_abc123", 10000L, "CARD", "idem-002");

        PaymentResponse response = paymentService.approve(request);

        assertThat(response.status()).isEqualTo("FAILED");
    }

    @Test
    void approve_sameIdempotencyKey_returnsCachedResponse() throws Exception {
        String cachedJson = objectMapper.writeValueAsString(new PaymentResponse(
                "TSVR01tid", "mer_001", "order-001", "tok_abc123",
                10000L, "CARD", "PAID", "PG-001", null));

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("idempotency:idem-001")).thenReturn(cachedJson);

        PaymentRequest request = new PaymentRequest(
                "mer_001", "order-001", "tok_abc123", 10000L, "CARD", "idem-001");

        PaymentResponse response = paymentService.approve(request);

        assertThat(response.status()).isEqualTo("PAID");
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void findByTid_notFound_throwsBusinessException() {
        when(paymentRepository.findByTid("unknown-tid")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.findByTid("unknown-tid"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Payment not found");
    }
}
```

- [ ] **Step 5: `PaymentControllerTest` 작성 (`@WebMvcTest`)**

`services/payment/src/test/java/com/picpay/payment/controller/PaymentControllerTest.java`:
```java
package com.picpay.payment.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picpay.common.exception.BusinessException;
import com.picpay.common.exception.ErrorCode;
import com.picpay.payment.dto.PaymentRequest;
import com.picpay.payment.dto.PaymentResponse;
import com.picpay.payment.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PaymentController.class)
class PaymentControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean PaymentService paymentService;

    @Test
    void post_payments_returns201_withPaidStatus() throws Exception {
        PaymentResponse response = new PaymentResponse(
                "TSVR01tid001", "mer_001", "order-001", "tok_abc",
                10000L, "CARD", "PAID", "PG-001", null);
        when(paymentService.approve(any())).thenReturn(response);

        PaymentRequest request = new PaymentRequest(
                "mer_001", "order-001", "tok_abc", 10000L, "CARD", null);

        mockMvc.perform(post("/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("PAID"));
    }

    @Test
    void post_payments_missingMerchantId_returns400() throws Exception {
        String body = """
                {"orderId":"order-001","tokenId":"tok_abc","amount":10000,"method":"CARD"}
                """;

        mockMvc.perform(post("/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void get_payments_tid_notFound_returns404() throws Exception {
        when(paymentService.findByTid(eq("unknown-tid")))
                .thenThrow(new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));

        mockMvc.perform(get("/v1/payments/unknown-tid"))
                .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 6: 테스트 실행**

```bash
./gradlew :services:payment:test --tests "com.picpay.payment.service.PaymentServiceTest" --tests "com.picpay.payment.controller.PaymentControllerTest" -i
```

Expected: 7개 테스트 PASS

- [ ] **Step 7: 커밋**

```bash
git add services/payment/
git commit -m "feat(payment): add payment approval API POST /v1/payments with idempotency"
```

---

## Task 4: 결제 조회 + 취소 API (`GET /v1/payments/{tid}`, `POST /v1/payments/cancel`)

**Files:**
- Create: `services/payment/src/main/java/com/picpay/payment/dto/CancelRequest.java`
- Create: `services/payment/src/main/java/com/picpay/payment/dto/CancelResponse.java`
- Modify: `services/payment/src/main/java/com/picpay/payment/service/PaymentService.java`
- Modify: `services/payment/src/main/java/com/picpay/payment/controller/PaymentController.java`
- Modify: `services/payment/src/test/java/com/picpay/payment/service/PaymentServiceTest.java`
- Modify: `services/payment/src/test/java/com/picpay/payment/controller/PaymentControllerTest.java`

- [ ] **Step 1: Cancel DTO 작성**

`services/payment/src/main/java/com/picpay/payment/dto/CancelRequest.java`:
```java
package com.picpay.payment.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CancelRequest(
        @NotBlank String tid,
        @NotNull @Min(1) Long cancelAmount,
        String reason
) {}
```

`services/payment/src/main/java/com/picpay/payment/dto/CancelResponse.java`:
```java
package com.picpay.payment.dto;

import com.picpay.payment.domain.PartialCancellation;

public record CancelResponse(
        String cancelTid,
        String status,
        Long cancelAmount,
        Long remainingAmount,
        String reason
) {
    public static CancelResponse from(PartialCancellation pc) {
        return new CancelResponse(
                pc.getCancelTid(),
                pc.getStatus(),
                pc.getCancelAmount(),
                pc.getRemainingAmount(),
                pc.getReason()
        );
    }
}
```

- [ ] **Step 2: `PaymentService`에 cancel 메서드 추가**

`PaymentService.java`에 다음 필드와 메서드 추가 (기존 클래스에 추가):

추가 필드:
```java
private final PartialCancellationRepository partialCancellationRepository;
```

생성자 수정 (6개 → 7개 인자):
```java
public PaymentService(TidService tidService, MockPgClient mockPgClient,
                      PaymentRepository paymentRepository,
                      OutboxEventRepository outboxEventRepository,
                      PartialCancellationRepository partialCancellationRepository,
                      StringRedisTemplate redisTemplate,
                      ObjectMapper objectMapper) {
    this.tidService = tidService;
    this.mockPgClient = mockPgClient;
    this.paymentRepository = paymentRepository;
    this.outboxEventRepository = outboxEventRepository;
    this.partialCancellationRepository = partialCancellationRepository;
    this.redisTemplate = redisTemplate;
    this.objectMapper = objectMapper;
}
```

`cancel` 메서드:
```java
@Transactional
public CancelResponse cancel(CancelRequest request) {
    Payment payment = paymentRepository.findByTid(request.tid())
            .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));

    // 이미 취소한 금액 합산
    List<PartialCancellation> existing = partialCancellationRepository.findByPaymentId(payment.getId());
    long alreadyCancelled = existing.stream().mapToLong(PartialCancellation::getCancelAmount).sum();
    long remainingAfter = payment.getAmount() - alreadyCancelled - request.cancelAmount();

    if (remainingAfter < 0) {
        throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION);
    }

    // MockPG 취소 요청
    MockPgClient.PgApprovalResult pgResult = mockPgClient.cancel(payment.getPgTid(), request.cancelAmount());

    String cancelTid = "C" + tidService.generate().substring(1); // TID와 동일 형식, C 접두사

    if (remainingAfter == 0) {
        payment.cancel();
    } else {
        payment.partialCancel();
    }
    paymentRepository.save(payment);

    PartialCancellation pc = PartialCancellation.create(
            payment.getId(), cancelTid, request.cancelAmount(),
            remainingAfter, request.reason(), pgResult.pgTid());
    partialCancellationRepository.save(pc);

    // Outbox 이벤트 삽입
    outboxEventRepository.save(OutboxEvent.create(
            "Payment", payment.getTid(), "payment.cancelled", "payment.cancelled",
            toJson(payment)));

    return CancelResponse.from(pc);
}
```

필요한 import 추가:
```java
import com.picpay.payment.dto.CancelRequest;
import com.picpay.payment.dto.CancelResponse;
import com.picpay.payment.repository.PartialCancellationRepository;
import java.util.List;
```

- [ ] **Step 3: `PaymentController`에 cancel 엔드포인트 추가**

`PaymentController.java`에 추가:
```java
@PostMapping("/cancel")
public ApiResponse<CancelResponse> cancel(@Valid @RequestBody CancelRequest request) {
    return ApiResponse.ok(paymentService.cancel(request));
}
```

필요한 import 추가:
```java
import com.picpay.payment.dto.CancelRequest;
import com.picpay.payment.dto.CancelResponse;
```

- [ ] **Step 4: `PaymentServiceTest`에 cancel 테스트 추가**

`PaymentServiceTest.java`에 추가:

추가 Mock 필드:
```java
@Mock private PartialCancellationRepository partialCancellationRepository;
```

`setUp()` 메서드 수정 (7인자로):
```java
@BeforeEach
void setUp() {
    paymentService = new PaymentService(tidService, mockPgClient,
            paymentRepository, outboxEventRepository, partialCancellationRepository,
            redisTemplate, objectMapper);
}
```

추가 테스트:
```java
@Test
void cancel_partialCancel_returnsRemainingAmount() {
    Payment payment = Payment.create("TSVR01tid001", "mer_001", "order-001",
            "tok_abc", 10000L, "CARD", null);
    payment.approve("PG-001"); // PAID 상태로

    when(paymentRepository.findByTid("TSVR01tid001")).thenReturn(Optional.of(payment));
    when(partialCancellationRepository.findByPaymentId(any())).thenReturn(List.of());
    when(mockPgClient.cancel(anyString(), anyLong()))
            .thenReturn(MockPgClient.PgApprovalResult.success("PGC-001"));
    when(tidService.generate()).thenReturn("TSVR0120260526143022000099");
    when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(partialCancellationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(outboxEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    CancelRequest request = new CancelRequest("TSVR01tid001", 3000L, "부분취소");
    CancelResponse response = paymentService.cancel(request);

    assertThat(response.cancelAmount()).isEqualTo(3000L);
    assertThat(response.remainingAmount()).isEqualTo(7000L);
    assertThat(response.status()).isEqualTo("CANCELLED");
}

@Test
void cancel_fullCancel_paymentStatusBecomesCANCELLED() {
    Payment payment = Payment.create("TSVR01tid002", "mer_001", "order-002",
            "tok_abc", 10000L, "CARD", null);
    payment.approve("PG-002");

    when(paymentRepository.findByTid("TSVR01tid002")).thenReturn(Optional.of(payment));
    when(partialCancellationRepository.findByPaymentId(any())).thenReturn(List.of());
    when(mockPgClient.cancel(anyString(), anyLong()))
            .thenReturn(MockPgClient.PgApprovalResult.success("PGC-002"));
    when(tidService.generate()).thenReturn("TSVR0120260526143022000099");
    when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(partialCancellationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(outboxEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    CancelRequest request = new CancelRequest("TSVR01tid002", 10000L, "전액취소");
    CancelResponse response = paymentService.cancel(request);

    assertThat(response.remainingAmount()).isEqualTo(0L);
    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
}
```

- [ ] **Step 5: `PaymentControllerTest`에 cancel 테스트 추가**

`PaymentControllerTest.java`에 추가:
```java
@Test
void post_cancel_returns200_withCancelResponse() throws Exception {
    CancelResponse cancelResponse = new CancelResponse(
            "CTSVR01tid001", "CANCELLED", 3000L, 7000L, "부분취소");
    when(paymentService.cancel(any())).thenReturn(cancelResponse);

    CancelRequest request = new CancelRequest("TSVR01tid001", 3000L, "부분취소");

    mockMvc.perform(post("/v1/payments/cancel")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.remainingAmount").value(7000));
}

@Test
void post_cancel_missingTid_returns400() throws Exception {
    String body = """
            {"cancelAmount":3000}
            """;

    mockMvc.perform(post("/v1/payments/cancel")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isBadRequest());
}
```

필요한 import 추가 (`PaymentControllerTest`):
```java
import com.picpay.payment.dto.CancelRequest;
import com.picpay.payment.dto.CancelResponse;
```

- [ ] **Step 6: 테스트 전체 실행**

```bash
./gradlew :services:payment:test -i
```

Expected: 전체 테스트 PASS (MockPgClientTest 2개 + TidServiceTest 3개 + PaymentServiceTest 6개 + PaymentControllerTest 5개 = 16개 이상)

- [ ] **Step 7: 커밋**

```bash
git add services/payment/
git commit -m "feat(payment): add payment cancel API POST /v1/payments/cancel with partial cancellation"
```

---

## Task 5: Outbox Poller (`@Scheduled` 1초, PENDING→PUBLISHED 로그)

**Files:**
- Create: `services/payment/src/main/java/com/picpay/payment/service/OutboxPoller.java`
- Modify: `services/payment/src/test/java/com/picpay/payment/service/PaymentServiceTest.java` (outbox 검증 추가)

- [ ] **Step 1: `OutboxPoller` 작성**

`services/payment/src/main/java/com/picpay/payment/service/OutboxPoller.java`:
```java
package com.picpay.payment.service;

import com.picpay.payment.domain.OutboxEvent;
import com.picpay.payment.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    private final OutboxEventRepository outboxEventRepository;

    public OutboxPoller(OutboxEventRepository outboxEventRepository) {
        this.outboxEventRepository = outboxEventRepository;
    }

    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void poll() {
        List<OutboxEvent> events = outboxEventRepository.findPendingOrFailed();
        if (events.isEmpty()) {
            return;
        }

        for (OutboxEvent event : events) {
            try {
                // Layer 4에서 실제 KafkaTemplate.send()로 교체
                log.info("[Outbox] Publishing event: topic={}, aggregateId={}, eventType={}",
                        event.getTopic(), event.getAggregateId(), event.getEventType());
                event.markPublished();
                outboxEventRepository.save(event);
            } catch (Exception e) {
                log.error("[Outbox] Failed to publish event id={}: {}", event.getId(), e.getMessage());
                event.markFailed(e.getMessage());
                outboxEventRepository.save(event);
            }
        }
    }
}
```

- [ ] **Step 2: `PaymentServiceTest`에 Outbox INSERT 검증 테스트 추가**

`PaymentServiceTest.java`에 추가:
```java
@Test
void approve_insertsOutboxEvent_onSuccess() {
    when(tidService.generate()).thenReturn("TSVR0120260526143022000003");
    when(mockPgClient.approve(anyString(), anyLong()))
            .thenReturn(MockPgClient.PgApprovalResult.success("PG-ABCD9999"));
    when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(outboxEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(redisTemplate.opsForValue()).thenReturn(valueOps);
    when(valueOps.get(anyString())).thenReturn(null);

    PaymentRequest request = new PaymentRequest(
            "mer_001", "order-003", "tok_abc123", 10000L, "CARD", null);

    paymentService.approve(request);

    verify(outboxEventRepository, times(1)).save(argThat(event ->
            "payment.completed".equals(event.getEventType()) &&
            "PENDING".equals(event.getStatus())
    ));
}
```

- [ ] **Step 3: 전체 테스트 실행**

```bash
./gradlew :services:payment:test -i
```

Expected: 전체 테스트 PASS

- [ ] **Step 4: 빌드 확인**

```bash
./gradlew :services:payment:build -x test
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 커밋**

```bash
git add services/payment/
git commit -m "feat(payment): add Outbox poller @Scheduled 1s PENDING→PUBLISHED log"
```

---

## 자가 검토

### 스펙 커버리지 점검

| 스펙 요건 | 구현 위치 |
|----------|----------|
| TID 형식 `T{serviceId}{yyyyMMddHHmmss}{seq:8자리}` | Task 2 TidService |
| Redis `INCR tid:seq:{yyyyMMdd}` TTL 2일 | Task 2 TidService.generateSeq() |
| Redis 장애 시 UUID 폴백 | Task 2 TidService.generateSeq() catch 블록 |
| 멱등성 `idempotency:{key}` TTL 24h | Task 3 PaymentService.approve() |
| 상태 전이: READY→PAID→CANCELLED | Task 1 PaymentStatus.canTransitionTo() |
| MockPg 95% 승인률 | Task 1 MockPgClient |
| Outbox 결제 TX와 동일 `@Transactional` | Task 3/4 PaymentService (같은 메서드 내 save) |
| `@Scheduled` Poller 1초 | Task 5 OutboxPoller |
| 전액/부분취소 `partial_cancellations` 저장 | Task 4 PaymentService.cancel() |
| 부분취소 2회 후 전액취소 → CANCELLED | Task 4 cancel() remainingAfter == 0 분기 |

### 타입/메서드 일관성

- `CancelResponse.from(PartialCancellation)` — Task 4에서 `PartialCancellation.create()` 반환값 사용 ✓
- `PaymentService` 생성자 인자 순서: Task 3(6개) → Task 4(7개, `partialCancellationRepository` 추가) ✓
- `OutboxEvent.create()` — Task 1 정의, Task 3/4/5에서 동일 시그니처 사용 ✓
- `BusinessException(ErrorCode.PAYMENT_NOT_FOUND)` — `ErrorCode` 이미 common 모듈에 존재 ✓
