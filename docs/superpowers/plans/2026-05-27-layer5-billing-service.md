# Layer 5: Billing Service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the automated recurring billing service: BillingPlan CRUD, @Scheduled executor calling Payment Service via RestClient, Redisson distributed lock, DB-based exponential-backoff retry, and Kafka event publish + billing history API.

**Architecture:** `BillingScheduler` runs every 60 seconds, queries `ACTIVE` plans whose `next_billing_at <= NOW()`, acquires a per-plan Redisson lock, then calls the Payment Service REST API. On failure it creates a `BillingRetryJob` and a `RetryScheduler` polls every 10 seconds for exponential-backoff retries. After 3 failed retries the job becomes `DEAD` and the plan is `PAUSED`. On success (initial or retry) a `billing.executed` event is published to Kafka.

**Tech Stack:** Java 21 + Spring Boot 3.4 + Gradle multi-module, Spring Scheduling, Spring RestClient, Redisson 3.27.2 (Redisson Spring Boot Starter), Spring Kafka, PostgreSQL (schema already exists in V1__init.sql), JUnit 5 + Mockito

---

## File Structure

**Modify:**
- `services/billing/build.gradle` — add Redisson, Kafka, H2, spring-kafka-test, awaitility
- `services/billing/src/main/resources/application.yml` — add Redis, Kafka, payment.service.url
- `services/billing/src/main/java/com/picpay/billing/BillingApplication.java` — add @EnableScheduling

**Create (domain):**
- `services/billing/src/main/java/com/picpay/billing/domain/BillingStatus.java` — ACTIVE/PAUSED/CANCELLED enum
- `services/billing/src/main/java/com/picpay/billing/domain/BillingPlan.java` — entity, extends BaseEntity
- `services/billing/src/main/java/com/picpay/billing/domain/BillingHistory.java` — entity, no BaseEntity (no updated_at in schema)
- `services/billing/src/main/java/com/picpay/billing/domain/RetryStatus.java` — PENDING/DONE/DEAD enum
- `services/billing/src/main/java/com/picpay/billing/domain/BillingRetryJob.java` — entity

**Create (repository):**
- `services/billing/src/main/java/com/picpay/billing/repository/BillingPlanRepository.java`
- `services/billing/src/main/java/com/picpay/billing/repository/BillingHistoryRepository.java`
- `services/billing/src/main/java/com/picpay/billing/repository/BillingRetryJobRepository.java`

**Create (DTO):**
- `services/billing/src/main/java/com/picpay/billing/dto/CreateBillingPlanRequest.java`
- `services/billing/src/main/java/com/picpay/billing/dto/BillingPlanResponse.java`
- `services/billing/src/main/java/com/picpay/billing/dto/BillingHistoryResponse.java`

**Create (config):**
- `services/billing/src/main/java/com/picpay/billing/config/RestClientConfig.java`
- `services/billing/src/main/java/com/picpay/billing/config/RedissonConfig.java`
- `services/billing/src/main/java/com/picpay/billing/config/KafkaConfig.java`

**Create (service):**
- `services/billing/src/main/java/com/picpay/billing/service/BillingPlanService.java`
- `services/billing/src/main/java/com/picpay/billing/service/BillingHistoryService.java`
- `services/billing/src/main/java/com/picpay/billing/service/PaymentClient.java`
- `services/billing/src/main/java/com/picpay/billing/service/BillingScheduler.java` — evolves across Tasks 2→3→4→5
- `services/billing/src/main/java/com/picpay/billing/service/RetryScheduler.java`

**Create (controller):**
- `services/billing/src/main/java/com/picpay/billing/controller/BillingController.java` — grows in Task 5

**Tests:**
- `services/billing/src/test/java/com/picpay/billing/service/BillingPlanServiceTest.java`
- `services/billing/src/test/java/com/picpay/billing/controller/BillingControllerTest.java`
- `services/billing/src/test/java/com/picpay/billing/service/PaymentClientTest.java`
- `services/billing/src/test/java/com/picpay/billing/service/BillingSchedulerTest.java`
- `services/billing/src/test/java/com/picpay/billing/service/RetrySchedulerTest.java`

---

## Context: Existing Billing DB Schema (V1__init.sql)

The following tables already exist — do NOT recreate them:

```sql
billing.billing_plans (id, plan_id, merchant_id, token_id, amount, cycle,
  next_billing_at, status DEFAULT 'ACTIVE', retry_count DEFAULT 0,
  created_at, updated_at)

billing.billing_histories (id, plan_id, tid, amount, status,
  failure_reason, created_at)

billing.billing_retry_jobs (id, plan_id, retry_count DEFAULT 0, max_retry DEFAULT 3,
  next_retry_at, last_error, status DEFAULT 'PENDING', created_at, updated_at)
```

## Context: ApiResponse

`ApiResponse` in `com.picpay.common.response` uses `ApiResponse.ok(data)` (not `.success(data)`):

```java
public record ApiResponse<T>(boolean success, T data, ErrorDetail error) {
    public static <T> ApiResponse<T> ok(T data) { ... }
    public static <T> ApiResponse<T> error(String code, String message) { ... }
}
```

---

### Task 1: Build Config + BillingPlan Entity + CRUD API (S20)

**Files:**
- Modify: `services/billing/build.gradle`
- Modify: `services/billing/src/main/resources/application.yml`
- Modify: `services/billing/src/main/java/com/picpay/billing/BillingApplication.java`
- Create: `services/billing/src/main/java/com/picpay/billing/domain/BillingStatus.java`
- Create: `services/billing/src/main/java/com/picpay/billing/domain/BillingPlan.java`
- Create: `services/billing/src/main/java/com/picpay/billing/repository/BillingPlanRepository.java`
- Create: `services/billing/src/main/java/com/picpay/billing/dto/CreateBillingPlanRequest.java`
- Create: `services/billing/src/main/java/com/picpay/billing/dto/BillingPlanResponse.java`
- Create: `services/billing/src/main/java/com/picpay/billing/service/BillingPlanService.java`
- Create: `services/billing/src/main/java/com/picpay/billing/controller/BillingController.java`
- Test: `services/billing/src/test/java/com/picpay/billing/service/BillingPlanServiceTest.java`
- Test: `services/billing/src/test/java/com/picpay/billing/controller/BillingControllerTest.java`

- [ ] **Step 1: Write the failing BillingPlanService tests**

```java
// services/billing/src/test/java/com/picpay/billing/service/BillingPlanServiceTest.java
package com.picpay.billing.service;

import com.picpay.billing.domain.BillingPlan;
import com.picpay.billing.domain.BillingStatus;
import com.picpay.billing.dto.BillingPlanResponse;
import com.picpay.billing.dto.CreateBillingPlanRequest;
import com.picpay.billing.repository.BillingPlanRepository;
import com.picpay.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BillingPlanServiceTest {

    @Mock BillingPlanRepository billingPlanRepository;
    @InjectMocks BillingPlanService billingPlanService;

    @Test
    void create_savesAndReturnsPlan() {
        CreateBillingPlanRequest request = new CreateBillingPlanRequest(
                "mer_001", "tok_abc", 10000L, "MONTHLY",
                LocalDateTime.of(2026, 6, 1, 0, 0));
        when(billingPlanRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BillingPlanResponse response = billingPlanService.create(request);

        assertThat(response.merchantId()).isEqualTo("mer_001");
        assertThat(response.amount()).isEqualTo(10000L);
        assertThat(response.status()).isEqualTo("ACTIVE");
        verify(billingPlanRepository).save(any(BillingPlan.class));
    }

    @Test
    void findByPlanId_notFound_throwsBusinessException() {
        when(billingPlanRepository.findByPlanId("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> billingPlanService.findByPlanId("unknown"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void cancel_activePlan_setsStatusCancelled() {
        BillingPlan plan = BillingPlan.of("BP-001", "mer_001", "tok_abc",
                10000L, "MONTHLY", LocalDateTime.now().plusDays(30));
        when(billingPlanRepository.findByPlanId("BP-001")).thenReturn(Optional.of(plan));
        when(billingPlanRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        billingPlanService.cancel("BP-001");

        assertThat(plan.getStatus()).isEqualTo(BillingStatus.CANCELLED);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew :services:billing:test --tests "com.picpay.billing.service.BillingPlanServiceTest" 2>&1 | tail -20
```

Expected: FAIL — `BillingPlanService`, `BillingPlan`, etc. not found.

- [ ] **Step 3: Update build.gradle with all dependencies**

```groovy
// services/billing/build.gradle
plugins {
    id 'org.springframework.boot'
}

dependencies {
    implementation project(':common')
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
    implementation 'org.springframework.kafka:spring-kafka'
    implementation 'org.redisson:redisson-spring-boot-starter:3.27.2'
    runtimeOnly 'org.postgresql:postgresql'
    testImplementation 'org.springframework.kafka:spring-kafka-test'
    testImplementation 'org.awaitility:awaitility:4.2.2'
    testRuntimeOnly 'com.h2database:h2'
}
```

- [ ] **Step 4: Update application.yml**

```yaml
# services/billing/src/main/resources/application.yml
spring:
  application:
    name: billing-service
  threads:
    virtual:
      enabled: true
  datasource:
    url: jdbc:postgresql://localhost:5432/picpay?currentSchema=billing
    username: picpay
    password: picpay
    driver-class-name: org.postgresql.Driver
  jpa:
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        default_schema: billing
  flyway:
    enabled: false
  data:
    redis:
      host: localhost
      port: 6379
  kafka:
    bootstrap-servers: localhost:9092
    producer:
      acks: all
      enable-idempotence: true
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.apache.kafka.common.serialization.StringSerializer

server:
  port: 8082

management:
  endpoints:
    web:
      exposure:
        include: health,info

payment:
  service:
    url: http://localhost:8081
```

- [ ] **Step 5: Add @EnableScheduling to BillingApplication**

```java
// services/billing/src/main/java/com/picpay/billing/BillingApplication.java
package com.picpay.billing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.picpay")
@EnableJpaAuditing
@EnableScheduling
public class BillingApplication {
    public static void main(String[] args) {
        SpringApplication.run(BillingApplication.class, args);
    }
}
```

- [ ] **Step 6: Create BillingStatus enum**

```java
// services/billing/src/main/java/com/picpay/billing/domain/BillingStatus.java
package com.picpay.billing.domain;

public enum BillingStatus {
    ACTIVE, PAUSED, CANCELLED
}
```

- [ ] **Step 7: Create BillingPlan entity**

```java
// services/billing/src/main/java/com/picpay/billing/domain/BillingPlan.java
package com.picpay.billing.domain;

import com.picpay.common.entity.BaseEntity;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "billing_plans", schema = "billing")
public class BillingPlan extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plan_id", nullable = false, unique = true)
    private String planId;

    @Column(name = "merchant_id", nullable = false)
    private String merchantId;

    @Column(name = "token_id", nullable = false)
    private String tokenId;

    @Column(name = "amount", nullable = false)
    private Long amount;

    @Column(name = "cycle", nullable = false)
    private String cycle;

    @Column(name = "next_billing_at", nullable = false)
    private LocalDateTime nextBillingAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private BillingStatus status = BillingStatus.ACTIVE;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    protected BillingPlan() {}

    public static BillingPlan of(String planId, String merchantId, String tokenId,
                                  Long amount, String cycle, LocalDateTime nextBillingAt) {
        BillingPlan p = new BillingPlan();
        p.planId = planId;
        p.merchantId = merchantId;
        p.tokenId = tokenId;
        p.amount = amount;
        p.cycle = cycle;
        p.nextBillingAt = nextBillingAt;
        return p;
    }

    public void cancel() {
        this.status = BillingStatus.CANCELLED;
    }

    public void pause() {
        this.status = BillingStatus.PAUSED;
    }

    public void advanceNextBillingAt() {
        if ("MONTHLY".equals(cycle)) {
            this.nextBillingAt = nextBillingAt.plusMonths(1);
        } else if ("WEEKLY".equals(cycle)) {
            this.nextBillingAt = nextBillingAt.plusWeeks(1);
        } else {
            this.nextBillingAt = nextBillingAt.plusDays(1);
        }
    }

    public Long getId() { return id; }
    public String getPlanId() { return planId; }
    public String getMerchantId() { return merchantId; }
    public String getTokenId() { return tokenId; }
    public Long getAmount() { return amount; }
    public String getCycle() { return cycle; }
    public LocalDateTime getNextBillingAt() { return nextBillingAt; }
    public BillingStatus getStatus() { return status; }
    public int getRetryCount() { return retryCount; }
}
```

- [ ] **Step 8: Create BillingPlanRepository**

```java
// services/billing/src/main/java/com/picpay/billing/repository/BillingPlanRepository.java
package com.picpay.billing.repository;

import com.picpay.billing.domain.BillingPlan;
import com.picpay.billing.domain.BillingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface BillingPlanRepository extends JpaRepository<BillingPlan, Long> {

    Optional<BillingPlan> findByPlanId(String planId);

    @Query("SELECT p FROM BillingPlan p WHERE p.status = :status AND p.nextBillingAt <= :now")
    List<BillingPlan> findDuePlans(@Param("status") BillingStatus status,
                                   @Param("now") LocalDateTime now);
}
```

- [ ] **Step 9: Create DTOs**

```java
// services/billing/src/main/java/com/picpay/billing/dto/CreateBillingPlanRequest.java
package com.picpay.billing.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

public record CreateBillingPlanRequest(
        @NotBlank String merchantId,
        @NotBlank String tokenId,
        @NotNull @Min(1) Long amount,
        @NotBlank String cycle,
        @NotNull LocalDateTime nextBillingAt
) {}
```

```java
// services/billing/src/main/java/com/picpay/billing/dto/BillingPlanResponse.java
package com.picpay.billing.dto;

import com.picpay.billing.domain.BillingPlan;
import java.time.LocalDateTime;

public record BillingPlanResponse(
        String planId,
        String merchantId,
        String tokenId,
        Long amount,
        String cycle,
        LocalDateTime nextBillingAt,
        String status,
        int retryCount,
        LocalDateTime createdAt
) {
    public static BillingPlanResponse from(BillingPlan plan) {
        return new BillingPlanResponse(
                plan.getPlanId(),
                plan.getMerchantId(),
                plan.getTokenId(),
                plan.getAmount(),
                plan.getCycle(),
                plan.getNextBillingAt(),
                plan.getStatus().name(),
                plan.getRetryCount(),
                plan.getCreatedAt()
        );
    }
}
```

- [ ] **Step 10: Create BillingPlanService**

```java
// services/billing/src/main/java/com/picpay/billing/service/BillingPlanService.java
package com.picpay.billing.service;

import com.picpay.billing.domain.BillingPlan;
import com.picpay.billing.dto.BillingPlanResponse;
import com.picpay.billing.dto.CreateBillingPlanRequest;
import com.picpay.billing.repository.BillingPlanRepository;
import com.picpay.common.exception.BusinessException;
import com.picpay.common.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class BillingPlanService {

    private final BillingPlanRepository billingPlanRepository;

    public BillingPlanService(BillingPlanRepository billingPlanRepository) {
        this.billingPlanRepository = billingPlanRepository;
    }

    @Transactional
    public BillingPlanResponse create(CreateBillingPlanRequest request) {
        String planId = "BP-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        BillingPlan plan = BillingPlan.of(
                planId,
                request.merchantId(),
                request.tokenId(),
                request.amount(),
                request.cycle(),
                request.nextBillingAt()
        );
        return BillingPlanResponse.from(billingPlanRepository.save(plan));
    }

    @Transactional(readOnly = true)
    public BillingPlanResponse findByPlanId(String planId) {
        return BillingPlanResponse.from(getOrThrow(planId));
    }

    @Transactional
    public void cancel(String planId) {
        BillingPlan plan = getOrThrow(planId);
        plan.cancel();
        billingPlanRepository.save(plan);
    }

    public BillingPlan getOrThrow(String planId) {
        return billingPlanRepository.findByPlanId(planId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLAN_NOT_FOUND));
    }
}
```

- [ ] **Step 11: Create BillingController**

```java
// services/billing/src/main/java/com/picpay/billing/controller/BillingController.java
package com.picpay.billing.controller;

import com.picpay.billing.dto.BillingPlanResponse;
import com.picpay.billing.dto.CreateBillingPlanRequest;
import com.picpay.billing.service.BillingPlanService;
import com.picpay.common.response.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/billing")
public class BillingController {

    private final BillingPlanService billingPlanService;

    public BillingController(BillingPlanService billingPlanService) {
        this.billingPlanService = billingPlanService;
    }

    @PostMapping("/plans")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<BillingPlanResponse> createPlan(@Valid @RequestBody CreateBillingPlanRequest request) {
        return ApiResponse.ok(billingPlanService.create(request));
    }

    @GetMapping("/plans/{planId}")
    public ApiResponse<BillingPlanResponse> getPlan(@PathVariable String planId) {
        return ApiResponse.ok(billingPlanService.findByPlanId(planId));
    }

    @DeleteMapping("/plans/{planId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelPlan(@PathVariable String planId) {
        billingPlanService.cancel(planId);
    }
}
```

- [ ] **Step 12: Write BillingControllerTest**

```java
// services/billing/src/test/java/com/picpay/billing/controller/BillingControllerTest.java
package com.picpay.billing.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picpay.billing.dto.BillingPlanResponse;
import com.picpay.billing.dto.CreateBillingPlanRequest;
import com.picpay.billing.service.BillingPlanService;
import com.picpay.common.exception.BusinessException;
import com.picpay.common.exception.ErrorCode;
import com.picpay.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(BillingController.class)
@Import(GlobalExceptionHandler.class)
class BillingControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean BillingPlanService billingPlanService;

    @Test
    void post_billingPlans_returns201WithActiveStatus() throws Exception {
        BillingPlanResponse response = new BillingPlanResponse(
                "BP-001", "mer_001", "tok_abc", 10000L, "MONTHLY",
                LocalDateTime.of(2026, 6, 1, 0, 0), "ACTIVE", 0, LocalDateTime.now());
        when(billingPlanService.create(any())).thenReturn(response);

        CreateBillingPlanRequest request = new CreateBillingPlanRequest(
                "mer_001", "tok_abc", 10000L, "MONTHLY",
                LocalDateTime.of(2026, 6, 1, 0, 0));

        mockMvc.perform(post("/v1/billing/plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    @Test
    void post_billingPlans_missingMerchantId_returns400() throws Exception {
        String body = """
                {"tokenId":"tok_abc","amount":10000,"cycle":"MONTHLY",
                 "nextBillingAt":"2026-06-01T00:00:00"}
                """;
        mockMvc.perform(post("/v1/billing/plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void get_billingPlans_planId_notFound_returns404() throws Exception {
        when(billingPlanService.findByPlanId("unknown"))
                .thenThrow(new BusinessException(ErrorCode.PLAN_NOT_FOUND));

        mockMvc.perform(get("/v1/billing/plans/unknown"))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_billingPlans_planId_returns204() throws Exception {
        doNothing().when(billingPlanService).cancel("BP-001");

        mockMvc.perform(delete("/v1/billing/plans/BP-001"))
                .andExpect(status().isNoContent());
    }
}
```

- [ ] **Step 13: Run all billing tests**

```bash
./gradlew :services:billing:test 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`, 7 tests pass.

- [ ] **Step 14: Commit**

```bash
git add services/billing/
git commit -m "feat(billing): add BillingPlan entity, CRUD API, and build config (Layer 5 S20)"
```

---

### Task 2: BillingHistory Entity + PaymentClient + BillingScheduler Basic (S21)

**Files:**
- Create: `services/billing/src/main/java/com/picpay/billing/domain/BillingHistory.java`
- Create: `services/billing/src/main/java/com/picpay/billing/repository/BillingHistoryRepository.java`
- Create: `services/billing/src/main/java/com/picpay/billing/config/RestClientConfig.java`
- Create: `services/billing/src/main/java/com/picpay/billing/service/PaymentClient.java`
- Create: `services/billing/src/main/java/com/picpay/billing/service/BillingScheduler.java`
- Test: `services/billing/src/test/java/com/picpay/billing/service/PaymentClientTest.java`
- Test: `services/billing/src/test/java/com/picpay/billing/service/BillingSchedulerTest.java`

- [ ] **Step 1: Write the failing PaymentClient test**

```java
// services/billing/src/test/java/com/picpay/billing/service/PaymentClientTest.java
package com.picpay.billing.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentClientTest {

    @Mock RestClient paymentRestClient;
    @Mock RestClient.RequestBodyUriSpec uriSpec;
    @Mock RestClient.RequestBodySpec bodySpec;
    @Mock RestClient.ResponseSpec responseSpec;
    @InjectMocks PaymentClient paymentClient;

    @Test
    void requestPayment_success_returnsTid() throws Exception {
        JsonNode json = new ObjectMapper().readTree(
                """{"success":true,"data":{"tid":"TXN-001","status":"PAID"}}""");

        when(paymentRestClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri("/v1/payments")).thenReturn(bodySpec);
        when(bodySpec.body(any())).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(JsonNode.class)).thenReturn(json);

        String tid = paymentClient.requestPayment("mer_001", "order-001", "tok_abc", 10000L);

        assertThat(tid).isEqualTo("TXN-001");
    }
}
```

- [ ] **Step 2: Write the failing BillingSchedulerTest**

```java
// services/billing/src/test/java/com/picpay/billing/service/BillingSchedulerTest.java
package com.picpay.billing.service;

import com.picpay.billing.domain.BillingHistory;
import com.picpay.billing.domain.BillingPlan;
import com.picpay.billing.domain.BillingStatus;
import com.picpay.billing.repository.BillingHistoryRepository;
import com.picpay.billing.repository.BillingPlanRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BillingSchedulerTest {

    @Mock BillingPlanRepository billingPlanRepository;
    @Mock BillingHistoryRepository billingHistoryRepository;
    @Mock PaymentClient paymentClient;
    @InjectMocks BillingScheduler billingScheduler;

    @Test
    void execute_duePlan_callsPaymentAndSavesSuccessHistory() {
        BillingPlan plan = BillingPlan.of("BP-001", "mer_001", "tok_abc",
                10000L, "MONTHLY", LocalDateTime.now().minusHours(1));

        when(billingPlanRepository.findDuePlans(eq(BillingStatus.ACTIVE), any()))
                .thenReturn(List.of(plan));
        when(paymentClient.requestPayment(any(), any(), any(), any())).thenReturn("TXN-001");
        when(billingPlanRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(billingHistoryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        billingScheduler.execute();

        verify(paymentClient).requestPayment(eq("mer_001"), any(), eq("tok_abc"), eq(10000L));
        verify(billingHistoryRepository).save(argThat(h -> "SUCCESS".equals(h.getStatus())));
    }

    @Test
    void execute_paymentFails_savesFailureHistory() {
        BillingPlan plan = BillingPlan.of("BP-001", "mer_001", "tok_abc",
                10000L, "MONTHLY", LocalDateTime.now().minusHours(1));

        when(billingPlanRepository.findDuePlans(eq(BillingStatus.ACTIVE), any()))
                .thenReturn(List.of(plan));
        when(paymentClient.requestPayment(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Payment service unavailable"));
        when(billingHistoryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        billingScheduler.execute();

        verify(billingHistoryRepository).save(argThat(h -> "FAILED".equals(h.getStatus())));
    }

    @Test
    void execute_noDuePlans_doesNothing() {
        when(billingPlanRepository.findDuePlans(any(), any())).thenReturn(List.of());

        billingScheduler.execute();

        verifyNoInteractions(paymentClient);
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

```bash
./gradlew :services:billing:test --tests "com.picpay.billing.service.PaymentClientTest" --tests "com.picpay.billing.service.BillingSchedulerTest" 2>&1 | tail -10
```

Expected: FAIL — class not found.

- [ ] **Step 4: Create BillingHistory entity**

```java
// services/billing/src/main/java/com/picpay/billing/domain/BillingHistory.java
package com.picpay.billing.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "billing_histories", schema = "billing")
public class BillingHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plan_id", nullable = false)
    private String planId;

    @Column(name = "tid")
    private String tid;

    @Column(name = "amount", nullable = false)
    private Long amount;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    protected BillingHistory() {}

    public static BillingHistory success(String planId, String tid, Long amount) {
        BillingHistory h = new BillingHistory();
        h.planId = planId;
        h.tid = tid;
        h.amount = amount;
        h.status = "SUCCESS";
        return h;
    }

    public static BillingHistory failure(String planId, Long amount, String failureReason) {
        BillingHistory h = new BillingHistory();
        h.planId = planId;
        h.amount = amount;
        h.status = "FAILED";
        h.failureReason = failureReason;
        return h;
    }

    public Long getId() { return id; }
    public String getPlanId() { return planId; }
    public String getTid() { return tid; }
    public Long getAmount() { return amount; }
    public String getStatus() { return status; }
    public String getFailureReason() { return failureReason; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
```

- [ ] **Step 5: Create BillingHistoryRepository**

```java
// services/billing/src/main/java/com/picpay/billing/repository/BillingHistoryRepository.java
package com.picpay.billing.repository;

import com.picpay.billing.domain.BillingHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BillingHistoryRepository extends JpaRepository<BillingHistory, Long> {
    List<BillingHistory> findByPlanIdOrderByCreatedAtDesc(String planId);
}
```

- [ ] **Step 6: Create RestClientConfig**

```java
// services/billing/src/main/java/com/picpay/billing/config/RestClientConfig.java
package com.picpay.billing.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    @Bean
    public RestClient paymentRestClient(@Value("${payment.service.url}") String paymentServiceUrl) {
        return RestClient.builder()
                .baseUrl(paymentServiceUrl)
                .build();
    }
}
```

- [ ] **Step 7: Create PaymentClient**

```java
// services/billing/src/main/java/com/picpay/billing/service/PaymentClient.java
package com.picpay.billing.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Component
public class PaymentClient {

    private final RestClient paymentRestClient;

    public PaymentClient(RestClient paymentRestClient) {
        this.paymentRestClient = paymentRestClient;
    }

    public String requestPayment(String merchantId, String orderId, String tokenId, Long amount) {
        Map<String, Object> body = Map.of(
                "merchantId", merchantId,
                "orderId", orderId,
                "tokenId", tokenId,
                "amount", amount,
                "method", "CARD"
        );

        JsonNode response = paymentRestClient.post()
                .uri("/v1/payments")
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        return response.path("data").path("tid").asText();
    }
}
```

- [ ] **Step 8: Create BillingScheduler (basic, no lock yet)**

```java
// services/billing/src/main/java/com/picpay/billing/service/BillingScheduler.java
package com.picpay.billing.service;

import com.picpay.billing.domain.BillingHistory;
import com.picpay.billing.domain.BillingPlan;
import com.picpay.billing.domain.BillingStatus;
import com.picpay.billing.repository.BillingHistoryRepository;
import com.picpay.billing.repository.BillingPlanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Component
public class BillingScheduler {

    private static final Logger log = LoggerFactory.getLogger(BillingScheduler.class);

    private final BillingPlanRepository billingPlanRepository;
    private final BillingHistoryRepository billingHistoryRepository;
    private final PaymentClient paymentClient;

    public BillingScheduler(BillingPlanRepository billingPlanRepository,
                             BillingHistoryRepository billingHistoryRepository,
                             PaymentClient paymentClient) {
        this.billingPlanRepository = billingPlanRepository;
        this.billingHistoryRepository = billingHistoryRepository;
        this.paymentClient = paymentClient;
    }

    @Scheduled(fixedDelay = 60000)
    public void execute() {
        List<BillingPlan> duePlans = billingPlanRepository.findDuePlans(
                BillingStatus.ACTIVE, LocalDateTime.now());

        for (BillingPlan plan : duePlans) {
            processPlan(plan);
        }
    }

    void processPlan(BillingPlan plan) {
        String orderId = "BILL-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        try {
            String tid = paymentClient.requestPayment(
                    plan.getMerchantId(), orderId, plan.getTokenId(), plan.getAmount());

            plan.advanceNextBillingAt();
            billingPlanRepository.save(plan);
            billingHistoryRepository.save(
                    BillingHistory.success(plan.getPlanId(), tid, plan.getAmount()));

            log.info("[Billing] Success: planId={}, tid={}", plan.getPlanId(), tid);
        } catch (Exception e) {
            String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
            log.error("[Billing] Failed: planId={}", plan.getPlanId(), e);
            billingHistoryRepository.save(
                    BillingHistory.failure(plan.getPlanId(), plan.getAmount(), reason));
        }
    }
}
```

- [ ] **Step 9: Run all billing tests**

```bash
./gradlew :services:billing:test 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`, all tests pass.

- [ ] **Step 10: Commit**

```bash
git add services/billing/
git commit -m "feat(billing): add BillingHistory, PaymentClient, and basic BillingScheduler (Layer 5 S21)"
```

---

### Task 3: Redisson Distributed Lock (S22)

**Files:**
- Create: `services/billing/src/main/java/com/picpay/billing/config/RedissonConfig.java`
- Modify: `services/billing/src/main/java/com/picpay/billing/service/BillingScheduler.java`
- Modify: `services/billing/src/test/java/com/picpay/billing/service/BillingSchedulerTest.java`

- [ ] **Step 1: Write the failing lock tests (add to BillingSchedulerTest)**

Replace the entire `BillingSchedulerTest.java` with this version that includes lock tests:

```java
// services/billing/src/test/java/com/picpay/billing/service/BillingSchedulerTest.java
package com.picpay.billing.service;

import com.picpay.billing.domain.BillingHistory;
import com.picpay.billing.domain.BillingPlan;
import com.picpay.billing.domain.BillingStatus;
import com.picpay.billing.repository.BillingHistoryRepository;
import com.picpay.billing.repository.BillingPlanRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BillingSchedulerTest {

    @Mock BillingPlanRepository billingPlanRepository;
    @Mock BillingHistoryRepository billingHistoryRepository;
    @Mock PaymentClient paymentClient;
    @Mock RedissonClient redissonClient;
    @InjectMocks BillingScheduler billingScheduler;

    private BillingPlan duePlan() {
        return BillingPlan.of("BP-001", "mer_001", "tok_abc",
                10000L, "MONTHLY", LocalDateTime.now().minusHours(1));
    }

    private RLock acquiredLock() throws InterruptedException {
        RLock lock = mock(RLock.class);
        when(lock.tryLock(0, 30, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        return lock;
    }

    @Test
    void execute_lockAcquired_processesAndUnlocks() throws InterruptedException {
        BillingPlan plan = duePlan();
        RLock lock = acquiredLock();

        when(billingPlanRepository.findDuePlans(eq(BillingStatus.ACTIVE), any()))
                .thenReturn(List.of(plan));
        when(redissonClient.getLock("lock:billing:BP-001")).thenReturn(lock);
        when(paymentClient.requestPayment(any(), any(), any(), any())).thenReturn("TXN-001");
        when(billingPlanRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(billingHistoryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        billingScheduler.execute();

        verify(paymentClient).requestPayment(eq("mer_001"), any(), eq("tok_abc"), eq(10000L));
        verify(billingHistoryRepository).save(argThat(h -> "SUCCESS".equals(h.getStatus())));
        verify(lock).unlock();
    }

    @Test
    void execute_lockNotAcquired_skipsProcessing() throws InterruptedException {
        BillingPlan plan = duePlan();
        RLock lock = mock(RLock.class);
        when(lock.tryLock(0, 30, TimeUnit.SECONDS)).thenReturn(false);

        when(billingPlanRepository.findDuePlans(eq(BillingStatus.ACTIVE), any()))
                .thenReturn(List.of(plan));
        when(redissonClient.getLock("lock:billing:BP-001")).thenReturn(lock);

        billingScheduler.execute();

        verifyNoInteractions(paymentClient);
    }

    @Test
    void execute_paymentFails_savesFailureHistoryAndUnlocks() throws InterruptedException {
        BillingPlan plan = duePlan();
        RLock lock = acquiredLock();

        when(billingPlanRepository.findDuePlans(eq(BillingStatus.ACTIVE), any()))
                .thenReturn(List.of(plan));
        when(redissonClient.getLock("lock:billing:BP-001")).thenReturn(lock);
        when(paymentClient.requestPayment(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Payment service unavailable"));
        when(billingHistoryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        billingScheduler.execute();

        verify(billingHistoryRepository).save(argThat(h -> "FAILED".equals(h.getStatus())));
        verify(lock).unlock();
    }

    @Test
    void execute_noDuePlans_doesNothing() {
        when(billingPlanRepository.findDuePlans(any(), any())).thenReturn(List.of());

        billingScheduler.execute();

        verifyNoInteractions(paymentClient, redissonClient);
    }
}
```

- [ ] **Step 2: Run tests to verify lock tests fail**

```bash
./gradlew :services:billing:test --tests "com.picpay.billing.service.BillingSchedulerTest" 2>&1 | tail -10
```

Expected: FAIL — `RedissonClient` not in constructor.

- [ ] **Step 3: Create RedissonConfig**

```java
// services/billing/src/main/java/com/picpay/billing/config/RedissonConfig.java
package com.picpay.billing.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    @Bean
    public RedissonClient redissonClient(@Value("${spring.data.redis.host}") String host,
                                          @Value("${spring.data.redis.port}") int port) {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + host + ":" + port);
        return Redisson.create(config);
    }
}
```

- [ ] **Step 4: Update BillingScheduler with Redisson lock**

Replace `services/billing/src/main/java/com/picpay/billing/service/BillingScheduler.java`:

```java
package com.picpay.billing.service;

import com.picpay.billing.domain.BillingHistory;
import com.picpay.billing.domain.BillingPlan;
import com.picpay.billing.domain.BillingStatus;
import com.picpay.billing.repository.BillingHistoryRepository;
import com.picpay.billing.repository.BillingPlanRepository;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
public class BillingScheduler {

    private static final Logger log = LoggerFactory.getLogger(BillingScheduler.class);
    private static final long LOCK_TTL_SECONDS = 30;

    private final BillingPlanRepository billingPlanRepository;
    private final BillingHistoryRepository billingHistoryRepository;
    private final PaymentClient paymentClient;
    private final RedissonClient redissonClient;

    public BillingScheduler(BillingPlanRepository billingPlanRepository,
                             BillingHistoryRepository billingHistoryRepository,
                             PaymentClient paymentClient,
                             RedissonClient redissonClient) {
        this.billingPlanRepository = billingPlanRepository;
        this.billingHistoryRepository = billingHistoryRepository;
        this.paymentClient = paymentClient;
        this.redissonClient = redissonClient;
    }

    @Scheduled(fixedDelay = 60000)
    public void execute() {
        List<BillingPlan> duePlans = billingPlanRepository.findDuePlans(
                BillingStatus.ACTIVE, LocalDateTime.now());

        for (BillingPlan plan : duePlans) {
            String lockKey = "lock:billing:" + plan.getPlanId();
            RLock lock = redissonClient.getLock(lockKey);
            boolean acquired = false;
            try {
                acquired = lock.tryLock(0, LOCK_TTL_SECONDS, TimeUnit.SECONDS);
                if (!acquired) {
                    log.info("[Billing] Lock not acquired, skipping: planId={}", plan.getPlanId());
                    continue;
                }
                processPlan(plan);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[Billing] Interrupted acquiring lock: planId={}", plan.getPlanId());
                return;
            } finally {
                if (acquired && lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }
    }

    void processPlan(BillingPlan plan) {
        String orderId = "BILL-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        try {
            String tid = paymentClient.requestPayment(
                    plan.getMerchantId(), orderId, plan.getTokenId(), plan.getAmount());

            plan.advanceNextBillingAt();
            billingPlanRepository.save(plan);
            billingHistoryRepository.save(
                    BillingHistory.success(plan.getPlanId(), tid, plan.getAmount()));

            log.info("[Billing] Success: planId={}, tid={}", plan.getPlanId(), tid);
        } catch (Exception e) {
            String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
            log.error("[Billing] Failed: planId={}", plan.getPlanId(), e);
            billingHistoryRepository.save(
                    BillingHistory.failure(plan.getPlanId(), plan.getAmount(), reason));
        }
    }
}
```

- [ ] **Step 5: Run all billing tests**

```bash
./gradlew :services:billing:test 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`, all tests pass.

- [ ] **Step 6: Commit**

```bash
git add services/billing/
git commit -m "feat(billing): add Redisson distributed lock to BillingScheduler (Layer 5 S22)"
```

---

### Task 4: BillingRetryJob Entity + RetryScheduler (S23)

**Files:**
- Create: `services/billing/src/main/java/com/picpay/billing/domain/RetryStatus.java`
- Create: `services/billing/src/main/java/com/picpay/billing/domain/BillingRetryJob.java`
- Create: `services/billing/src/main/java/com/picpay/billing/repository/BillingRetryJobRepository.java`
- Modify: `services/billing/src/main/java/com/picpay/billing/service/BillingScheduler.java`
- Create: `services/billing/src/main/java/com/picpay/billing/service/RetryScheduler.java`
- Test: `services/billing/src/test/java/com/picpay/billing/service/RetrySchedulerTest.java`

- [ ] **Step 1: Write the failing RetryScheduler tests**

```java
// services/billing/src/test/java/com/picpay/billing/service/RetrySchedulerTest.java
package com.picpay.billing.service;

import com.picpay.billing.domain.BillingHistory;
import com.picpay.billing.domain.BillingPlan;
import com.picpay.billing.domain.BillingRetryJob;
import com.picpay.billing.domain.BillingStatus;
import com.picpay.billing.domain.RetryStatus;
import com.picpay.billing.repository.BillingHistoryRepository;
import com.picpay.billing.repository.BillingPlanRepository;
import com.picpay.billing.repository.BillingRetryJobRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RetrySchedulerTest {

    @Mock BillingRetryJobRepository billingRetryJobRepository;
    @Mock BillingPlanRepository billingPlanRepository;
    @Mock BillingHistoryRepository billingHistoryRepository;
    @Mock PaymentClient paymentClient;
    @InjectMocks RetryScheduler retryScheduler;

    @Test
    void processRetryJob_success_marksJobDoneAndSavesSuccessHistory() {
        BillingPlan plan = BillingPlan.of("BP-001", "mer_001", "tok_abc",
                10000L, "MONTHLY", LocalDateTime.now().minusHours(1));
        BillingRetryJob job = BillingRetryJob.create("BP-001", "previous error");

        when(billingPlanRepository.findByPlanId("BP-001")).thenReturn(Optional.of(plan));
        when(paymentClient.requestPayment(any(), any(), any(), any())).thenReturn("TXN-001");
        when(billingPlanRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(billingHistoryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(billingRetryJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        retryScheduler.processRetryJob(job);

        assertThat(job.getStatus()).isEqualTo(RetryStatus.DONE);
        verify(billingHistoryRepository).save(argThat(h -> "SUCCESS".equals(h.getStatus())));
    }

    @Test
    void processRetryJob_failureNotExhausted_schedulesNextRetry() {
        BillingPlan plan = BillingPlan.of("BP-001", "mer_001", "tok_abc",
                10000L, "MONTHLY", LocalDateTime.now().minusHours(1));
        BillingRetryJob job = BillingRetryJob.create("BP-001", "error");

        when(billingPlanRepository.findByPlanId("BP-001")).thenReturn(Optional.of(plan));
        when(paymentClient.requestPayment(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("still failing"));
        when(billingHistoryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(billingRetryJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        retryScheduler.processRetryJob(job);

        assertThat(job.getStatus()).isEqualTo(RetryStatus.PENDING);
        assertThat(job.getRetryCount()).isEqualTo(1);
    }

    @Test
    void processRetryJob_exhaustedRetries_marksDeadAndPausesPlan() {
        BillingPlan plan = BillingPlan.of("BP-001", "mer_001", "tok_abc",
                10000L, "MONTHLY", LocalDateTime.now().minusHours(1));
        BillingRetryJob job = BillingRetryJob.create("BP-001", "error");
        // Simulate 2 prior retries (retryCount=2, one more fail → retryCount=3=maxRetry → DEAD)
        job.prepareNextRetry("error1"); // retryCount=1
        job.prepareNextRetry("error2"); // retryCount=2

        when(billingPlanRepository.findByPlanId("BP-001")).thenReturn(Optional.of(plan));
        when(paymentClient.requestPayment(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("final failure"));
        when(billingPlanRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(billingHistoryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(billingRetryJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        retryScheduler.processRetryJob(job);

        assertThat(job.getStatus()).isEqualTo(RetryStatus.DEAD);
        assertThat(plan.getStatus()).isEqualTo(BillingStatus.PAUSED);
        verify(billingHistoryRepository).save(argThat(h -> "FAILED".equals(h.getStatus())));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew :services:billing:test --tests "com.picpay.billing.service.RetrySchedulerTest" 2>&1 | tail -10
```

Expected: FAIL — `RetryScheduler`, `BillingRetryJob`, etc. not found.

- [ ] **Step 3: Create RetryStatus enum**

```java
// services/billing/src/main/java/com/picpay/billing/domain/RetryStatus.java
package com.picpay.billing.domain;

public enum RetryStatus {
    PENDING, DONE, DEAD
}
```

- [ ] **Step 4: Create BillingRetryJob entity**

```java
// services/billing/src/main/java/com/picpay/billing/domain/BillingRetryJob.java
package com.picpay.billing.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "billing_retry_jobs", schema = "billing")
public class BillingRetryJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plan_id", nullable = false)
    private String planId;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "max_retry", nullable = false)
    private int maxRetry = 3;

    @Column(name = "next_retry_at", nullable = false)
    private LocalDateTime nextRetryAt;

    @Column(name = "last_error")
    private String lastError;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private RetryStatus status = RetryStatus.PENDING;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    protected BillingRetryJob() {}

    // First retry fires after 30 seconds (2^0 * 30)
    public static BillingRetryJob create(String planId, String lastError) {
        BillingRetryJob job = new BillingRetryJob();
        job.planId = planId;
        job.lastError = lastError;
        job.nextRetryAt = LocalDateTime.now().plusSeconds(30);
        return job;
    }

    public void markDone() {
        this.status = RetryStatus.DONE;
        this.updatedAt = LocalDateTime.now();
    }

    public void markDead() {
        this.status = RetryStatus.DEAD;
        this.updatedAt = LocalDateTime.now();
    }

    // Increments retryCount and schedules next: 2^retryCount * 30s (60s, 120s, ...)
    public void prepareNextRetry(String error) {
        this.retryCount++;
        this.lastError = error;
        long delaySeconds = (long) Math.pow(2, retryCount) * 30;
        this.nextRetryAt = LocalDateTime.now().plusSeconds(delaySeconds);
        this.updatedAt = LocalDateTime.now();
    }

    public boolean isExhaustedAfterIncrement() {
        return (this.retryCount + 1) >= maxRetry;
    }

    public Long getId() { return id; }
    public String getPlanId() { return planId; }
    public int getRetryCount() { return retryCount; }
    public int getMaxRetry() { return maxRetry; }
    public LocalDateTime getNextRetryAt() { return nextRetryAt; }
    public String getLastError() { return lastError; }
    public RetryStatus getStatus() { return status; }
}
```

- [ ] **Step 5: Create BillingRetryJobRepository**

```java
// services/billing/src/main/java/com/picpay/billing/repository/BillingRetryJobRepository.java
package com.picpay.billing.repository;

import com.picpay.billing.domain.BillingRetryJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface BillingRetryJobRepository extends JpaRepository<BillingRetryJob, Long> {

    @Query("SELECT j FROM BillingRetryJob j WHERE j.status = 'PENDING' AND j.nextRetryAt <= :now")
    List<BillingRetryJob> findDueRetryJobs(@Param("now") LocalDateTime now);
}
```

- [ ] **Step 6: Update BillingScheduler to create retry job on failure**

Replace `processPlan` in `BillingScheduler.java` — also add `BillingRetryJobRepository` to constructor.

```java
// services/billing/src/main/java/com/picpay/billing/service/BillingScheduler.java
package com.picpay.billing.service;

import com.picpay.billing.domain.BillingHistory;
import com.picpay.billing.domain.BillingPlan;
import com.picpay.billing.domain.BillingRetryJob;
import com.picpay.billing.domain.BillingStatus;
import com.picpay.billing.repository.BillingHistoryRepository;
import com.picpay.billing.repository.BillingPlanRepository;
import com.picpay.billing.repository.BillingRetryJobRepository;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
public class BillingScheduler {

    private static final Logger log = LoggerFactory.getLogger(BillingScheduler.class);
    private static final long LOCK_TTL_SECONDS = 30;

    private final BillingPlanRepository billingPlanRepository;
    private final BillingHistoryRepository billingHistoryRepository;
    private final BillingRetryJobRepository billingRetryJobRepository;
    private final PaymentClient paymentClient;
    private final RedissonClient redissonClient;

    public BillingScheduler(BillingPlanRepository billingPlanRepository,
                             BillingHistoryRepository billingHistoryRepository,
                             BillingRetryJobRepository billingRetryJobRepository,
                             PaymentClient paymentClient,
                             RedissonClient redissonClient) {
        this.billingPlanRepository = billingPlanRepository;
        this.billingHistoryRepository = billingHistoryRepository;
        this.billingRetryJobRepository = billingRetryJobRepository;
        this.paymentClient = paymentClient;
        this.redissonClient = redissonClient;
    }

    @Scheduled(fixedDelay = 60000)
    public void execute() {
        List<BillingPlan> duePlans = billingPlanRepository.findDuePlans(
                BillingStatus.ACTIVE, LocalDateTime.now());

        for (BillingPlan plan : duePlans) {
            String lockKey = "lock:billing:" + plan.getPlanId();
            RLock lock = redissonClient.getLock(lockKey);
            boolean acquired = false;
            try {
                acquired = lock.tryLock(0, LOCK_TTL_SECONDS, TimeUnit.SECONDS);
                if (!acquired) {
                    log.info("[Billing] Lock not acquired, skipping: planId={}", plan.getPlanId());
                    continue;
                }
                processPlan(plan);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[Billing] Interrupted acquiring lock: planId={}", plan.getPlanId());
                return;
            } finally {
                if (acquired && lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }
    }

    void processPlan(BillingPlan plan) {
        String orderId = "BILL-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        try {
            String tid = paymentClient.requestPayment(
                    plan.getMerchantId(), orderId, plan.getTokenId(), plan.getAmount());

            plan.advanceNextBillingAt();
            billingPlanRepository.save(plan);
            billingHistoryRepository.save(
                    BillingHistory.success(plan.getPlanId(), tid, plan.getAmount()));

            log.info("[Billing] Success: planId={}, tid={}", plan.getPlanId(), tid);
        } catch (Exception e) {
            String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
            log.error("[Billing] Failed: planId={}", plan.getPlanId(), e);
            billingHistoryRepository.save(
                    BillingHistory.failure(plan.getPlanId(), plan.getAmount(), reason));
            billingRetryJobRepository.save(BillingRetryJob.create(plan.getPlanId(), reason));
        }
    }
}
```

- [ ] **Step 7: Update BillingSchedulerTest to add BillingRetryJobRepository mock**

Add `@Mock BillingRetryJobRepository billingRetryJobRepository;` field to `BillingSchedulerTest` and update the failure test to verify retry job creation:

```java
// Add this field:
@Mock BillingRetryJobRepository billingRetryJobRepository;

// Update execute_paymentFails test:
@Test
void execute_paymentFails_savesFailureHistoryAndCreatesRetryJob() throws InterruptedException {
    BillingPlan plan = duePlan();
    RLock lock = acquiredLock();

    when(billingPlanRepository.findDuePlans(eq(BillingStatus.ACTIVE), any()))
            .thenReturn(List.of(plan));
    when(redissonClient.getLock("lock:billing:BP-001")).thenReturn(lock);
    when(paymentClient.requestPayment(any(), any(), any(), any()))
            .thenThrow(new RuntimeException("Payment service unavailable"));
    when(billingHistoryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(billingRetryJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    billingScheduler.execute();

    verify(billingHistoryRepository).save(argThat(h -> "FAILED".equals(h.getStatus())));
    verify(billingRetryJobRepository).save(argThat(j -> "BP-001".equals(j.getPlanId())));
    verify(lock).unlock();
}
```

The full updated `BillingSchedulerTest.java`:

```java
package com.picpay.billing.service;

import com.picpay.billing.domain.BillingPlan;
import com.picpay.billing.domain.BillingStatus;
import com.picpay.billing.repository.BillingHistoryRepository;
import com.picpay.billing.repository.BillingPlanRepository;
import com.picpay.billing.repository.BillingRetryJobRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BillingSchedulerTest {

    @Mock BillingPlanRepository billingPlanRepository;
    @Mock BillingHistoryRepository billingHistoryRepository;
    @Mock BillingRetryJobRepository billingRetryJobRepository;
    @Mock PaymentClient paymentClient;
    @Mock RedissonClient redissonClient;
    @InjectMocks BillingScheduler billingScheduler;

    private BillingPlan duePlan() {
        return BillingPlan.of("BP-001", "mer_001", "tok_abc",
                10000L, "MONTHLY", LocalDateTime.now().minusHours(1));
    }

    private RLock acquiredLock() throws InterruptedException {
        RLock lock = mock(RLock.class);
        when(lock.tryLock(0, 30, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        return lock;
    }

    @Test
    void execute_lockAcquired_processesAndUnlocks() throws InterruptedException {
        BillingPlan plan = duePlan();
        RLock lock = acquiredLock();

        when(billingPlanRepository.findDuePlans(eq(BillingStatus.ACTIVE), any()))
                .thenReturn(List.of(plan));
        when(redissonClient.getLock("lock:billing:BP-001")).thenReturn(lock);
        when(paymentClient.requestPayment(any(), any(), any(), any())).thenReturn("TXN-001");
        when(billingPlanRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(billingHistoryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        billingScheduler.execute();

        verify(paymentClient).requestPayment(eq("mer_001"), any(), eq("tok_abc"), eq(10000L));
        verify(billingHistoryRepository).save(argThat(h -> "SUCCESS".equals(h.getStatus())));
        verify(lock).unlock();
    }

    @Test
    void execute_lockNotAcquired_skipsProcessing() throws InterruptedException {
        BillingPlan plan = duePlan();
        RLock lock = mock(RLock.class);
        when(lock.tryLock(0, 30, TimeUnit.SECONDS)).thenReturn(false);

        when(billingPlanRepository.findDuePlans(eq(BillingStatus.ACTIVE), any()))
                .thenReturn(List.of(plan));
        when(redissonClient.getLock("lock:billing:BP-001")).thenReturn(lock);

        billingScheduler.execute();

        verifyNoInteractions(paymentClient);
    }

    @Test
    void execute_paymentFails_savesFailureHistoryAndCreatesRetryJob() throws InterruptedException {
        BillingPlan plan = duePlan();
        RLock lock = acquiredLock();

        when(billingPlanRepository.findDuePlans(eq(BillingStatus.ACTIVE), any()))
                .thenReturn(List.of(plan));
        when(redissonClient.getLock("lock:billing:BP-001")).thenReturn(lock);
        when(paymentClient.requestPayment(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Payment service unavailable"));
        when(billingHistoryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(billingRetryJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        billingScheduler.execute();

        verify(billingHistoryRepository).save(argThat(h -> "FAILED".equals(h.getStatus())));
        verify(billingRetryJobRepository).save(argThat(j -> "BP-001".equals(j.getPlanId())));
        verify(lock).unlock();
    }

    @Test
    void execute_noDuePlans_doesNothing() {
        when(billingPlanRepository.findDuePlans(any(), any())).thenReturn(List.of());

        billingScheduler.execute();

        verifyNoInteractions(paymentClient, redissonClient);
    }
}
```

- [ ] **Step 8: Create RetryScheduler**

```java
// services/billing/src/main/java/com/picpay/billing/service/RetryScheduler.java
package com.picpay.billing.service;

import com.picpay.billing.domain.BillingHistory;
import com.picpay.billing.domain.BillingPlan;
import com.picpay.billing.domain.BillingRetryJob;
import com.picpay.billing.domain.BillingStatus;
import com.picpay.billing.repository.BillingHistoryRepository;
import com.picpay.billing.repository.BillingPlanRepository;
import com.picpay.billing.repository.BillingRetryJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class RetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(RetryScheduler.class);

    private final BillingRetryJobRepository billingRetryJobRepository;
    private final BillingPlanRepository billingPlanRepository;
    private final BillingHistoryRepository billingHistoryRepository;
    private final PaymentClient paymentClient;

    public RetryScheduler(BillingRetryJobRepository billingRetryJobRepository,
                           BillingPlanRepository billingPlanRepository,
                           BillingHistoryRepository billingHistoryRepository,
                           PaymentClient paymentClient) {
        this.billingRetryJobRepository = billingRetryJobRepository;
        this.billingPlanRepository = billingPlanRepository;
        this.billingHistoryRepository = billingHistoryRepository;
        this.paymentClient = paymentClient;
    }

    @Scheduled(fixedDelay = 10000)
    public void execute() {
        List<BillingRetryJob> jobs = billingRetryJobRepository.findDueRetryJobs(LocalDateTime.now());
        for (BillingRetryJob job : jobs) {
            processRetryJob(job);
        }
    }

    void processRetryJob(BillingRetryJob job) {
        BillingPlan plan = billingPlanRepository.findByPlanId(job.getPlanId()).orElse(null);
        if (plan == null || plan.getStatus() != BillingStatus.ACTIVE) {
            job.markDead();
            billingRetryJobRepository.save(job);
            return;
        }

        String orderId = "RETRY-" + job.getId() + "-" + job.getRetryCount();
        try {
            String tid = paymentClient.requestPayment(
                    plan.getMerchantId(), orderId, plan.getTokenId(), plan.getAmount());

            plan.advanceNextBillingAt();
            billingPlanRepository.save(plan);
            billingHistoryRepository.save(
                    BillingHistory.success(plan.getPlanId(), tid, plan.getAmount()));
            job.markDone();
            billingRetryJobRepository.save(job);

            log.info("[Retry] Success: planId={}, retryCount={}, tid={}",
                    job.getPlanId(), job.getRetryCount(), tid);
        } catch (Exception e) {
            String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
            log.error("[Retry] Failed: planId={}, retryCount={}",
                    job.getPlanId(), job.getRetryCount(), e);

            billingHistoryRepository.save(
                    BillingHistory.failure(plan.getPlanId(), plan.getAmount(), reason));

            if (job.isExhaustedAfterIncrement()) {
                job.prepareNextRetry(reason);
                job.markDead();
                plan.pause();
                billingPlanRepository.save(plan);
                billingRetryJobRepository.save(job);
                log.warn("[Retry] Max retries exhausted, plan PAUSED: planId={}", job.getPlanId());
            } else {
                job.prepareNextRetry(reason);
                billingRetryJobRepository.save(job);
            }
        }
    }
}
```

- [ ] **Step 9: Run all billing tests**

```bash
./gradlew :services:billing:test 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`, all tests pass.

- [ ] **Step 10: Commit**

```bash
git add services/billing/
git commit -m "feat(billing): add BillingRetryJob entity and RetryScheduler with exponential backoff (Layer 5 S23)"
```

---

### Task 5: Kafka Publish + Billing History API (S24)

**Files:**
- Create: `services/billing/src/main/java/com/picpay/billing/config/KafkaConfig.java`
- Create: `services/billing/src/main/java/com/picpay/billing/dto/BillingHistoryResponse.java`
- Create: `services/billing/src/main/java/com/picpay/billing/service/BillingHistoryService.java`
- Modify: `services/billing/src/main/java/com/picpay/billing/service/BillingScheduler.java`
- Modify: `services/billing/src/main/java/com/picpay/billing/controller/BillingController.java`
- Modify: `services/billing/src/test/java/com/picpay/billing/service/BillingSchedulerTest.java`
- Modify: `services/billing/src/test/java/com/picpay/billing/controller/BillingControllerTest.java`

- [ ] **Step 1: Write the failing Kafka publish test (add to BillingSchedulerTest)**

Add `@Mock KafkaTemplate<String, String> kafkaTemplate;` to `BillingSchedulerTest` and add this test:

```java
// Add field:
@SuppressWarnings("unchecked")
@Mock KafkaTemplate<String, String> kafkaTemplate;

// Add test:
@Test
void execute_success_publishesBillingExecutedEvent() throws InterruptedException {
    BillingPlan plan = duePlan();
    RLock lock = acquiredLock();

    when(billingPlanRepository.findDuePlans(eq(BillingStatus.ACTIVE), any()))
            .thenReturn(List.of(plan));
    when(redissonClient.getLock("lock:billing:BP-001")).thenReturn(lock);
    when(paymentClient.requestPayment(any(), any(), any(), any())).thenReturn("TXN-001");
    when(billingPlanRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(billingHistoryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    billingScheduler.execute();

    verify(kafkaTemplate).send(eq("billing.executed"), eq("BP-001"), contains("SUCCESS"));
}
```

- [ ] **Step 2: Write the failing history API test (add to BillingControllerTest)**

Add `@MockitoBean BillingHistoryService billingHistoryService;` to `BillingControllerTest` and add this test:

```java
// Add field:
@MockitoBean BillingHistoryService billingHistoryService;

// Add test:
@Test
void get_billingPlans_planId_history_returnsHistoryList() throws Exception {
    BillingHistoryResponse historyEntry = new BillingHistoryResponse(
            1L, "BP-001", "TXN-001", 10000L, "SUCCESS", null, LocalDateTime.now());
    when(billingHistoryService.findByPlanId("BP-001")).thenReturn(List.of(historyEntry));

    mockMvc.perform(get("/v1/billing/plans/BP-001/history"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].status").value("SUCCESS"))
            .andExpect(jsonPath("$.data[0].tid").value("TXN-001"));
}
```

- [ ] **Step 3: Run tests to verify they fail**

```bash
./gradlew :services:billing:test --tests "com.picpay.billing.service.BillingSchedulerTest" --tests "com.picpay.billing.controller.BillingControllerTest" 2>&1 | tail -10
```

Expected: FAIL — `KafkaTemplate` not in constructor, `BillingHistoryService` not found.

- [ ] **Step 4: Create KafkaConfig**

```java
// services/billing/src/main/java/com/picpay/billing/config/KafkaConfig.java
package com.picpay.billing.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {

    @Bean
    public NewTopic billingExecuted() {
        return TopicBuilder.name("billing.executed")
                .partitions(3)
                .replicas(1)
                .build();
    }
}
```

- [ ] **Step 5: Create BillingHistoryResponse DTO**

```java
// services/billing/src/main/java/com/picpay/billing/dto/BillingHistoryResponse.java
package com.picpay.billing.dto;

import com.picpay.billing.domain.BillingHistory;
import java.time.LocalDateTime;

public record BillingHistoryResponse(
        Long id,
        String planId,
        String tid,
        Long amount,
        String status,
        String failureReason,
        LocalDateTime createdAt
) {
    public static BillingHistoryResponse from(BillingHistory history) {
        return new BillingHistoryResponse(
                history.getId(),
                history.getPlanId(),
                history.getTid(),
                history.getAmount(),
                history.getStatus(),
                history.getFailureReason(),
                history.getCreatedAt()
        );
    }
}
```

- [ ] **Step 6: Create BillingHistoryService**

```java
// services/billing/src/main/java/com/picpay/billing/service/BillingHistoryService.java
package com.picpay.billing.service;

import com.picpay.billing.dto.BillingHistoryResponse;
import com.picpay.billing.repository.BillingHistoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class BillingHistoryService {

    private final BillingHistoryRepository billingHistoryRepository;

    public BillingHistoryService(BillingHistoryRepository billingHistoryRepository) {
        this.billingHistoryRepository = billingHistoryRepository;
    }

    @Transactional(readOnly = true)
    public List<BillingHistoryResponse> findByPlanId(String planId) {
        return billingHistoryRepository.findByPlanIdOrderByCreatedAtDesc(planId)
                .stream()
                .map(BillingHistoryResponse::from)
                .toList();
    }
}
```

- [ ] **Step 7: Update BillingScheduler with KafkaTemplate**

Replace the full `BillingScheduler.java`:

```java
// services/billing/src/main/java/com/picpay/billing/service/BillingScheduler.java
package com.picpay.billing.service;

import com.picpay.billing.domain.BillingHistory;
import com.picpay.billing.domain.BillingPlan;
import com.picpay.billing.domain.BillingRetryJob;
import com.picpay.billing.domain.BillingStatus;
import com.picpay.billing.repository.BillingHistoryRepository;
import com.picpay.billing.repository.BillingPlanRepository;
import com.picpay.billing.repository.BillingRetryJobRepository;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
public class BillingScheduler {

    private static final Logger log = LoggerFactory.getLogger(BillingScheduler.class);
    private static final long LOCK_TTL_SECONDS = 30;
    private static final String TOPIC = "billing.executed";

    private final BillingPlanRepository billingPlanRepository;
    private final BillingHistoryRepository billingHistoryRepository;
    private final BillingRetryJobRepository billingRetryJobRepository;
    private final PaymentClient paymentClient;
    private final RedissonClient redissonClient;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public BillingScheduler(BillingPlanRepository billingPlanRepository,
                             BillingHistoryRepository billingHistoryRepository,
                             BillingRetryJobRepository billingRetryJobRepository,
                             PaymentClient paymentClient,
                             RedissonClient redissonClient,
                             KafkaTemplate<String, String> kafkaTemplate) {
        this.billingPlanRepository = billingPlanRepository;
        this.billingHistoryRepository = billingHistoryRepository;
        this.billingRetryJobRepository = billingRetryJobRepository;
        this.paymentClient = paymentClient;
        this.redissonClient = redissonClient;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Scheduled(fixedDelay = 60000)
    public void execute() {
        List<BillingPlan> duePlans = billingPlanRepository.findDuePlans(
                BillingStatus.ACTIVE, LocalDateTime.now());

        for (BillingPlan plan : duePlans) {
            String lockKey = "lock:billing:" + plan.getPlanId();
            RLock lock = redissonClient.getLock(lockKey);
            boolean acquired = false;
            try {
                acquired = lock.tryLock(0, LOCK_TTL_SECONDS, TimeUnit.SECONDS);
                if (!acquired) {
                    log.info("[Billing] Lock not acquired, skipping: planId={}", plan.getPlanId());
                    continue;
                }
                processPlan(plan);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[Billing] Interrupted acquiring lock: planId={}", plan.getPlanId());
                return;
            } finally {
                if (acquired && lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }
    }

    void processPlan(BillingPlan plan) {
        String orderId = "BILL-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        try {
            String tid = paymentClient.requestPayment(
                    plan.getMerchantId(), orderId, plan.getTokenId(), plan.getAmount());

            plan.advanceNextBillingAt();
            billingPlanRepository.save(plan);
            billingHistoryRepository.save(
                    BillingHistory.success(plan.getPlanId(), tid, plan.getAmount()));

            String payload = String.format(
                    "{\"planId\":\"%s\",\"tid\":\"%s\",\"amount\":%d,\"status\":\"SUCCESS\"}",
                    plan.getPlanId(), tid, plan.getAmount());
            kafkaTemplate.send(TOPIC, plan.getPlanId(), payload);

            log.info("[Billing] Success: planId={}, tid={}", plan.getPlanId(), tid);
        } catch (Exception e) {
            String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
            log.error("[Billing] Failed: planId={}", plan.getPlanId(), e);

            billingHistoryRepository.save(
                    BillingHistory.failure(plan.getPlanId(), plan.getAmount(), reason));
            billingRetryJobRepository.save(BillingRetryJob.create(plan.getPlanId(), reason));

            String payload = String.format(
                    "{\"planId\":\"%s\",\"amount\":%d,\"status\":\"FAILED\"}",
                    plan.getPlanId(), plan.getAmount());
            kafkaTemplate.send(TOPIC, plan.getPlanId(), payload);
        }
    }
}
```

- [ ] **Step 8: Update BillingSchedulerTest with full final version**

```java
// services/billing/src/test/java/com/picpay/billing/service/BillingSchedulerTest.java
package com.picpay.billing.service;

import com.picpay.billing.domain.BillingPlan;
import com.picpay.billing.domain.BillingStatus;
import com.picpay.billing.repository.BillingHistoryRepository;
import com.picpay.billing.repository.BillingPlanRepository;
import com.picpay.billing.repository.BillingRetryJobRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BillingSchedulerTest {

    @Mock BillingPlanRepository billingPlanRepository;
    @Mock BillingHistoryRepository billingHistoryRepository;
    @Mock BillingRetryJobRepository billingRetryJobRepository;
    @Mock PaymentClient paymentClient;
    @Mock RedissonClient redissonClient;
    @SuppressWarnings("unchecked")
    @Mock KafkaTemplate<String, String> kafkaTemplate;
    @InjectMocks BillingScheduler billingScheduler;

    private BillingPlan duePlan() {
        return BillingPlan.of("BP-001", "mer_001", "tok_abc",
                10000L, "MONTHLY", LocalDateTime.now().minusHours(1));
    }

    private RLock acquiredLock() throws InterruptedException {
        RLock lock = mock(RLock.class);
        when(lock.tryLock(0, 30, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        return lock;
    }

    @Test
    void execute_lockAcquired_processesAndUnlocks() throws InterruptedException {
        BillingPlan plan = duePlan();
        RLock lock = acquiredLock();

        when(billingPlanRepository.findDuePlans(eq(BillingStatus.ACTIVE), any()))
                .thenReturn(List.of(plan));
        when(redissonClient.getLock("lock:billing:BP-001")).thenReturn(lock);
        when(paymentClient.requestPayment(any(), any(), any(), any())).thenReturn("TXN-001");
        when(billingPlanRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(billingHistoryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        billingScheduler.execute();

        verify(paymentClient).requestPayment(eq("mer_001"), any(), eq("tok_abc"), eq(10000L));
        verify(billingHistoryRepository).save(argThat(h -> "SUCCESS".equals(h.getStatus())));
        verify(kafkaTemplate).send(eq("billing.executed"), eq("BP-001"), contains("SUCCESS"));
        verify(lock).unlock();
    }

    @Test
    void execute_lockNotAcquired_skipsProcessing() throws InterruptedException {
        BillingPlan plan = duePlan();
        RLock lock = mock(RLock.class);
        when(lock.tryLock(0, 30, TimeUnit.SECONDS)).thenReturn(false);

        when(billingPlanRepository.findDuePlans(eq(BillingStatus.ACTIVE), any()))
                .thenReturn(List.of(plan));
        when(redissonClient.getLock("lock:billing:BP-001")).thenReturn(lock);

        billingScheduler.execute();

        verifyNoInteractions(paymentClient);
    }

    @Test
    void execute_paymentFails_savesFailureHistoryAndCreatesRetryJob() throws InterruptedException {
        BillingPlan plan = duePlan();
        RLock lock = acquiredLock();

        when(billingPlanRepository.findDuePlans(eq(BillingStatus.ACTIVE), any()))
                .thenReturn(List.of(plan));
        when(redissonClient.getLock("lock:billing:BP-001")).thenReturn(lock);
        when(paymentClient.requestPayment(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Payment service unavailable"));
        when(billingHistoryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(billingRetryJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        billingScheduler.execute();

        verify(billingHistoryRepository).save(argThat(h -> "FAILED".equals(h.getStatus())));
        verify(billingRetryJobRepository).save(argThat(j -> "BP-001".equals(j.getPlanId())));
        verify(kafkaTemplate).send(eq("billing.executed"), eq("BP-001"), contains("FAILED"));
        verify(lock).unlock();
    }

    @Test
    void execute_noDuePlans_doesNothing() {
        when(billingPlanRepository.findDuePlans(any(), any())).thenReturn(List.of());

        billingScheduler.execute();

        verifyNoInteractions(paymentClient, redissonClient, kafkaTemplate);
    }
}
```

- [ ] **Step 9: Update BillingController with history endpoint**

```java
// services/billing/src/main/java/com/picpay/billing/controller/BillingController.java
package com.picpay.billing.controller;

import com.picpay.billing.dto.BillingHistoryResponse;
import com.picpay.billing.dto.BillingPlanResponse;
import com.picpay.billing.dto.CreateBillingPlanRequest;
import com.picpay.billing.service.BillingHistoryService;
import com.picpay.billing.service.BillingPlanService;
import com.picpay.common.response.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/v1/billing")
public class BillingController {

    private final BillingPlanService billingPlanService;
    private final BillingHistoryService billingHistoryService;

    public BillingController(BillingPlanService billingPlanService,
                              BillingHistoryService billingHistoryService) {
        this.billingPlanService = billingPlanService;
        this.billingHistoryService = billingHistoryService;
    }

    @PostMapping("/plans")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<BillingPlanResponse> createPlan(@Valid @RequestBody CreateBillingPlanRequest request) {
        return ApiResponse.ok(billingPlanService.create(request));
    }

    @GetMapping("/plans/{planId}")
    public ApiResponse<BillingPlanResponse> getPlan(@PathVariable String planId) {
        return ApiResponse.ok(billingPlanService.findByPlanId(planId));
    }

    @DeleteMapping("/plans/{planId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelPlan(@PathVariable String planId) {
        billingPlanService.cancel(planId);
    }

    @GetMapping("/plans/{planId}/history")
    public ApiResponse<List<BillingHistoryResponse>> getHistory(@PathVariable String planId) {
        return ApiResponse.ok(billingHistoryService.findByPlanId(planId));
    }
}
```

- [ ] **Step 10: Update BillingControllerTest with full final version**

```java
// services/billing/src/test/java/com/picpay/billing/controller/BillingControllerTest.java
package com.picpay.billing.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picpay.billing.dto.BillingHistoryResponse;
import com.picpay.billing.dto.BillingPlanResponse;
import com.picpay.billing.dto.CreateBillingPlanRequest;
import com.picpay.billing.service.BillingHistoryService;
import com.picpay.billing.service.BillingPlanService;
import com.picpay.common.exception.BusinessException;
import com.picpay.common.exception.ErrorCode;
import com.picpay.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(BillingController.class)
@Import(GlobalExceptionHandler.class)
class BillingControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean BillingPlanService billingPlanService;
    @MockitoBean BillingHistoryService billingHistoryService;

    @Test
    void post_billingPlans_returns201WithActiveStatus() throws Exception {
        BillingPlanResponse response = new BillingPlanResponse(
                "BP-001", "mer_001", "tok_abc", 10000L, "MONTHLY",
                LocalDateTime.of(2026, 6, 1, 0, 0), "ACTIVE", 0, LocalDateTime.now());
        when(billingPlanService.create(any())).thenReturn(response);

        CreateBillingPlanRequest request = new CreateBillingPlanRequest(
                "mer_001", "tok_abc", 10000L, "MONTHLY",
                LocalDateTime.of(2026, 6, 1, 0, 0));

        mockMvc.perform(post("/v1/billing/plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    @Test
    void post_billingPlans_missingMerchantId_returns400() throws Exception {
        String body = """
                {"tokenId":"tok_abc","amount":10000,"cycle":"MONTHLY",
                 "nextBillingAt":"2026-06-01T00:00:00"}
                """;
        mockMvc.perform(post("/v1/billing/plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void get_billingPlans_planId_notFound_returns404() throws Exception {
        when(billingPlanService.findByPlanId("unknown"))
                .thenThrow(new BusinessException(ErrorCode.PLAN_NOT_FOUND));

        mockMvc.perform(get("/v1/billing/plans/unknown"))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_billingPlans_planId_returns204() throws Exception {
        doNothing().when(billingPlanService).cancel("BP-001");

        mockMvc.perform(delete("/v1/billing/plans/BP-001"))
                .andExpect(status().isNoContent());
    }

    @Test
    void get_billingPlans_planId_history_returnsHistoryList() throws Exception {
        BillingHistoryResponse entry = new BillingHistoryResponse(
                1L, "BP-001", "TXN-001", 10000L, "SUCCESS", null, LocalDateTime.now());
        when(billingHistoryService.findByPlanId("BP-001")).thenReturn(List.of(entry));

        mockMvc.perform(get("/v1/billing/plans/BP-001/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("SUCCESS"))
                .andExpect(jsonPath("$.data[0].tid").value("TXN-001"));
    }
}
```

- [ ] **Step 11: Run all billing tests**

```bash
./gradlew :services:billing:test 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`, all tests pass (13 tests total).

- [ ] **Step 12: Run full project build**

```bash
./gradlew build 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 13: Commit**

```bash
git add services/billing/
git commit -m "feat(billing): add Kafka billing.executed publish and billing history API (Layer 5 S24)"
```

---

## Self-Review

**Spec coverage:**
- ✅ S20: BillingPlan entity + CRUD API (Task 1)
- ✅ S21: @Scheduled billing executor + Payment RestClient (Task 2)
- ✅ S22: Redisson distributed lock `lock:billing:{planId}` TTL 30s (Task 3)
- ✅ S23: `billing_retry_jobs` exponential backoff + DEAD/PAUSED (Task 4)
- ✅ S24: `billing.executed` Kafka publish + `GET /v1/billing/plans/{planId}/history` (Task 5)

**Type consistency:**
- `BillingPlan.of(...)` factory used consistently in tests and service
- `BillingHistory.success()` and `.failure()` factories used in both BillingScheduler and RetryScheduler
- `BillingRetryJob.create()` used in BillingScheduler; `prepareNextRetry()`/`isExhaustedAfterIncrement()` used in RetryScheduler
- `ApiResponse.ok()` used consistently (not `.success()`)

**No placeholders:** All steps contain complete code.
