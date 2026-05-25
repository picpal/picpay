# Layer 1: Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Gradle 멀티모듈 프로젝트 뼈대 + 공통 모듈 + Docker Compose 인프라 + 전체 DB 스키마를 구축한다.

**Architecture:** 루트 Gradle 프로젝트 아래 `common` 모듈과 `services/{gateway,payment,billing,token,notification}` 5개 서비스 모듈로 구성. 공통 예외/응답 처리는 `common`에 집중시켜 서비스 모듈이 의존한다. DB는 PostgreSQL 단일 인스턴스에 스키마(payment, token, billing, merchant, notification)를 분리하고 Flyway로 마이그레이션한다.

**Tech Stack:** Java 21, Spring Boot 3.4.0, Gradle 8.10, PostgreSQL 16, Kafka 7.5 (Confluent), Redis 7, Docker Compose, Flyway

---

## 파일 구조

```
picpay/
├── build.gradle                          # 루트 빌드 설정
├── settings.gradle                       # 모듈 등록
├── gradle.properties                     # 공통 버전 속성
├── docker-compose.yml                    # 로컬 인프라
├── common/
│   ├── build.gradle
│   └── src/
│       ├── main/java/com/picpay/common/
│       │   ├── response/
│       │   │   └── ApiResponse.java      # 공통 응답 래퍼
│       │   ├── exception/
│       │   │   ├── ErrorCode.java        # 에러 코드 enum
│       │   │   ├── BusinessException.java
│       │   │   └── GlobalExceptionHandler.java
│       │   └── entity/
│       │       └── BaseEntity.java       # createdAt/updatedAt
│       └── test/java/com/picpay/common/
│           ├── response/
│           │   └── ApiResponseTest.java
│           └── exception/
│               ├── ErrorCodeTest.java
│               └── BusinessExceptionTest.java
├── services/
│   ├── gateway/
│   │   ├── build.gradle
│   │   └── src/main/
│   │       ├── java/com/picpay/gateway/GatewayApplication.java
│   │       └── resources/application.yml
│   ├── payment/
│   │   ├── build.gradle
│   │   └── src/main/
│   │       ├── java/com/picpay/payment/PaymentApplication.java
│   │       └── resources/application.yml
│   ├── billing/
│   │   ├── build.gradle
│   │   └── src/main/
│   │       ├── java/com/picpay/billing/BillingApplication.java
│   │       └── resources/application.yml
│   ├── token/
│   │   ├── build.gradle
│   │   └── src/main/
│   │       ├── java/com/picpay/token/TokenApplication.java
│   │       └── resources/application.yml
│   └── notification/
│       ├── build.gradle
│       └── src/main/
│           ├── java/com/picpay/notification/NotificationApplication.java
│           └── resources/application.yml
└── db/
    └── migration/
        └── V1__init.sql                  # 전체 스키마 초기화
```

---

## Task 1: Gradle 멀티모듈 스켈레톤 (S1)

**Files:**
- Create: `build.gradle`
- Create: `settings.gradle`
- Create: `gradle.properties`
- Create: `common/build.gradle`
- Create: `services/gateway/build.gradle`
- Create: `services/payment/build.gradle`
- Create: `services/billing/build.gradle`
- Create: `services/token/build.gradle`
- Create: `services/notification/build.gradle`
- Create: `services/{각 서비스}/src/main/java/com/picpay/{service}/{Service}Application.java` × 5
- Create: `services/{각 서비스}/src/main/resources/application.yml` × 5

- [ ] **Step 1: Gradle Wrapper 생성**

```bash
gradle wrapper --gradle-version 8.10
```

Expected: `gradlew`, `gradlew.bat`, `gradle/wrapper/` 생성됨

- [ ] **Step 2: `gradle.properties` 작성**

```properties
# gradle.properties
springBootVersion=3.4.0
springDependencyManagementVersion=1.1.6
```

- [ ] **Step 3: `settings.gradle` 작성**

```groovy
rootProject.name = 'picpay'

include 'common'
include 'services:gateway'
include 'services:payment'
include 'services:billing'
include 'services:token'
include 'services:notification'
```

- [ ] **Step 4: 루트 `build.gradle` 작성**

```groovy
plugins {
    id 'java'
    id 'org.springframework.boot' version "${springBootVersion}" apply false
    id 'io.spring.dependency-management' version "${springDependencyManagementVersion}" apply false
}

allprojects {
    group = 'com.picpay'
    version = '0.0.1-SNAPSHOT'
}

subprojects {
    apply plugin: 'java'
    apply plugin: 'io.spring.dependency-management'

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    dependencyManagement {
        imports {
            mavenBom "org.springframework.boot:spring-boot-dependencies:${springBootVersion}"
        }
    }

    repositories {
        mavenCentral()
    }

    dependencies {
        testImplementation 'org.springframework.boot:spring-boot-starter-test'
    }

    test {
        useJUnitPlatform()
    }
}
```

- [ ] **Step 5: `common/build.gradle` 작성**

```groovy
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
}
```

- [ ] **Step 6: 각 서비스 `build.gradle` 작성 (5개 동일 패턴)**

`services/payment/build.gradle` (나머지 4개도 동일하게 작성, `port`만 다름):

```groovy
plugins {
    id 'org.springframework.boot'
}

dependencies {
    implementation project(':common')
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
    runtimeOnly 'org.postgresql:postgresql'
}
```

gateway는 Spring Cloud Gateway 사용이므로 별도:

```groovy
// services/gateway/build.gradle
plugins {
    id 'org.springframework.boot'
}

ext {
    springCloudVersion = '2024.0.0'
}

dependencies {
    implementation project(':common')
    implementation 'org.springframework.cloud:spring-cloud-starter-gateway'
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
    implementation 'org.springframework.boot:spring-boot-starter-data-redis-reactive'
}

dependencyManagement {
    imports {
        mavenBom "org.springframework.cloud:spring-cloud-dependencies:${springCloudVersion}"
    }
}
```

- [ ] **Step 7: 각 서비스 Application 클래스 생성 (5개)**

`services/payment/src/main/java/com/picpay/payment/PaymentApplication.java`:

```java
package com.picpay.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class PaymentApplication {
    public static void main(String[] args) {
        SpringApplication.run(PaymentApplication.class, args);
    }
}
```

나머지 4개도 동일 패턴 (`billing`, `token`, `notification`, `gateway` 패키지명만 변경).

- [ ] **Step 8: 각 서비스 `application.yml` 작성**

`services/payment/src/main/resources/application.yml`:

```yaml
spring:
  application:
    name: payment-service
  threads:
    virtual:
      enabled: true

server:
  port: 8081

management:
  endpoints:
    web:
      exposure:
        include: health,info
```

포트 매핑:
| 서비스 | 포트 |
|--------|------|
| gateway | 8080 |
| payment | 8081 |
| billing | 8082 |
| token | 8083 |
| notification | 8084 |

- [ ] **Step 9: 빌드 확인**

```bash
./gradlew build -x test
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 10: Commit**

```bash
git add build.gradle settings.gradle gradle.properties common/ services/ gradlew gradlew.bat gradle/
git commit -m "feat: initialize gradle multi-module project skeleton

- 루트 + common + 5개 서비스 모듈 구성
- Java 21, Spring Boot 3.4.0
- Virtual Threads 활성화 (spring.threads.virtual.enabled=true)"
```

---

## Task 2: common 모듈 — ApiResponse (S2-a)

**Files:**
- Create: `common/src/main/java/com/picpay/common/response/ApiResponse.java`
- Create: `common/src/test/java/com/picpay/common/response/ApiResponseTest.java`

- [ ] **Step 1: 실패 테스트 작성**

`common/src/test/java/com/picpay/common/response/ApiResponseTest.java`:

```java
package com.picpay.common.response;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ApiResponseTest {

    @Test
    void ok_성공응답_생성() {
        ApiResponse<String> response = ApiResponse.ok("data");

        assertThat(response.success()).isTrue();
        assertThat(response.data()).isEqualTo("data");
        assertThat(response.error()).isNull();
    }

    @Test
    void error_실패응답_생성() {
        ApiResponse<?> response = ApiResponse.error("PAYMENT_NOT_FOUND", "Payment not found");

        assertThat(response.success()).isFalse();
        assertThat(response.data()).isNull();
        assertThat(response.error().code()).isEqualTo("PAYMENT_NOT_FOUND");
        assertThat(response.error().message()).isEqualTo("Payment not found");
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

```bash
./gradlew :common:test --tests "com.picpay.common.response.ApiResponseTest"
```

Expected: FAIL — `ApiResponse` 클래스 없음

- [ ] **Step 3: `ApiResponse` 구현**

`common/src/main/java/com/picpay/common/response/ApiResponse.java`:

```java
package com.picpay.common.response;

public record ApiResponse<T>(boolean success, T data, ErrorDetail error) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static <T> ApiResponse<T> error(String code, String message) {
        return new ApiResponse<>(false, null, new ErrorDetail(code, message));
    }

    public record ErrorDetail(String code, String message) {}
}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
./gradlew :common:test --tests "com.picpay.common.response.ApiResponseTest"
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add common/src/
git commit -m "feat(common): add ApiResponse<T> record"
```

---

## Task 3: common 모듈 — ErrorCode + BusinessException (S2-b)

**Files:**
- Create: `common/src/main/java/com/picpay/common/exception/ErrorCode.java`
- Create: `common/src/main/java/com/picpay/common/exception/BusinessException.java`
- Create: `common/src/test/java/com/picpay/common/exception/ErrorCodeTest.java`
- Create: `common/src/test/java/com/picpay/common/exception/BusinessExceptionTest.java`

- [ ] **Step 1: 실패 테스트 작성**

`common/src/test/java/com/picpay/common/exception/ErrorCodeTest.java`:

```java
package com.picpay.common.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import static org.assertj.core.api.Assertions.assertThat;

class ErrorCodeTest {

    @Test
    void PAYMENT_NOT_FOUND_속성_확인() {
        assertThat(ErrorCode.PAYMENT_NOT_FOUND.getCode()).isEqualTo("PAYMENT_NOT_FOUND");
        assertThat(ErrorCode.PAYMENT_NOT_FOUND.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void RATE_LIMIT_EXCEEDED_속성_확인() {
        assertThat(ErrorCode.RATE_LIMIT_EXCEEDED.getCode()).isEqualTo("RATE_LIMIT_EXCEEDED");
        assertThat(ErrorCode.RATE_LIMIT_EXCEEDED.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }
}
```

`common/src/test/java/com/picpay/common/exception/BusinessExceptionTest.java`:

```java
package com.picpay.common.exception;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class BusinessExceptionTest {

    @Test
    void BusinessException_errorCode_보존() {
        BusinessException ex = new BusinessException(ErrorCode.PAYMENT_NOT_FOUND);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_NOT_FOUND);
        assertThat(ex.getMessage()).isEqualTo(ErrorCode.PAYMENT_NOT_FOUND.getMessage());
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

```bash
./gradlew :common:test --tests "com.picpay.common.exception.*"
```

Expected: FAIL

- [ ] **Step 3: `ErrorCode` 구현**

`common/src/main/java/com/picpay/common/exception/ErrorCode.java`:

```java
package com.picpay.common.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    DUPLICATE_ORDER("DUPLICATE_ORDER", "Duplicate orderId", HttpStatus.CONFLICT),
    PAYMENT_NOT_FOUND("PAYMENT_NOT_FOUND", "Payment not found", HttpStatus.NOT_FOUND),
    INVALID_STATUS_TRANSITION("INVALID_STATUS_TRANSITION", "Invalid status transition", HttpStatus.BAD_REQUEST),
    TOKEN_NOT_FOUND("TOKEN_NOT_FOUND", "Token not found", HttpStatus.NOT_FOUND),
    PLAN_NOT_FOUND("PLAN_NOT_FOUND", "Billing plan not found", HttpStatus.NOT_FOUND),
    RATE_LIMIT_EXCEEDED("RATE_LIMIT_EXCEEDED", "API rate limit exceeded", HttpStatus.TOO_MANY_REQUESTS),
    UNAUTHORIZED("UNAUTHORIZED", "Authentication failed", HttpStatus.UNAUTHORIZED),
    IDEMPOTENCY_CONFLICT("IDEMPOTENCY_CONFLICT", "Idempotency key conflict", HttpStatus.CONFLICT),
    INTERNAL_ERROR("INTERNAL_ERROR", "Internal server error", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String code;
    private final String message;
    private final HttpStatus status;

    ErrorCode(String code, String message, HttpStatus status) {
        this.code = code;
        this.message = message;
        this.status = status;
    }

    public String getCode() { return code; }
    public String getMessage() { return message; }
    public HttpStatus getStatus() { return status; }
}
```

- [ ] **Step 4: `BusinessException` 구현**

`common/src/main/java/com/picpay/common/exception/BusinessException.java`:

```java
package com.picpay.common.exception;

public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

```bash
./gradlew :common:test --tests "com.picpay.common.exception.*"
```

Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add common/src/
git commit -m "feat(common): add ErrorCode enum and BusinessException"
```

---

## Task 4: common 모듈 — GlobalExceptionHandler + BaseEntity (S2-c)

**Files:**
- Create: `common/src/main/java/com/picpay/common/exception/GlobalExceptionHandler.java`
- Create: `common/src/main/java/com/picpay/common/entity/BaseEntity.java`
- Create: `common/src/test/java/com/picpay/common/exception/GlobalExceptionHandlerTest.java`

- [ ] **Step 1: 실패 테스트 작성**

`common/src/test/java/com/picpay/common/exception/GlobalExceptionHandlerTest.java`:

```java
package com.picpay.common.exception;

import com.picpay.common.response.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void BusinessException_처리_시_해당_상태코드_반환() {
        BusinessException ex = new BusinessException(ErrorCode.PAYMENT_NOT_FOUND);

        ResponseEntity<ApiResponse<?>> response = handler.handleBusinessException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().error().code()).isEqualTo("PAYMENT_NOT_FOUND");
    }

    @Test
    void 알수없는_예외_처리_시_500_반환() {
        Exception ex = new RuntimeException("unexpected");

        ResponseEntity<ApiResponse<?>> response = handler.handleException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().error().code()).isEqualTo("INTERNAL_ERROR");
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

```bash
./gradlew :common:test --tests "com.picpay.common.exception.GlobalExceptionHandlerTest"
```

Expected: FAIL

- [ ] **Step 3: `GlobalExceptionHandler` 구현**

`common/src/main/java/com/picpay/common/exception/GlobalExceptionHandler.java`:

```java
package com.picpay.common.exception;

import com.picpay.common.response.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<?>> handleBusinessException(BusinessException e) {
        ErrorCode errorCode = e.getErrorCode();
        return ResponseEntity
                .status(errorCode.getStatus())
                .body(ApiResponse.error(errorCode.getCode(), errorCode.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<?>> handleException(Exception e) {
        log.error("Unhandled exception", e);
        ErrorCode errorCode = ErrorCode.INTERNAL_ERROR;
        return ResponseEntity
                .status(errorCode.getStatus())
                .body(ApiResponse.error(errorCode.getCode(), errorCode.getMessage()));
    }
}
```

- [ ] **Step 4: `BaseEntity` 구현** (JPA Auditing 어노테이션 기반, 단위 테스트 불필요)

> **주의:** `@EntityListeners(AuditingEntityListener.class)`가 동작하려면 각 서비스의 Application 클래스에 `@EnableJpaAuditing`이 필요하다. Task 1의 각 서비스 Application 클래스에 아래를 추가한다:
> ```java
> @SpringBootApplication
> @EnableJpaAuditing
> public class PaymentApplication { ... }
> ```

`common/src/main/java/com/picpay/common/entity/BaseEntity.java`:

```java
package com.picpay.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
```

- [ ] **Step 5: 테스트 통과 확인**

```bash
./gradlew :common:test
```

Expected: PASS (전체 common 테스트)

- [ ] **Step 6: Commit**

```bash
git add common/src/
git commit -m "feat(common): add GlobalExceptionHandler and BaseEntity"
```

---

## Task 5: Docker Compose (S3)

**Files:**
- Create: `docker-compose.yml`

- [ ] **Step 1: `docker-compose.yml` 작성**

```yaml
version: '3.8'

services:
  postgres:
    image: postgres:16
    container_name: picpay-postgres
    ports:
      - "5432:5432"
    environment:
      POSTGRES_DB: picpay
      POSTGRES_USER: picpay
      POSTGRES_PASSWORD: picpay
    volumes:
      - postgres_data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U picpay"]
      interval: 10s
      timeout: 5s
      retries: 5

  zookeeper:
    image: confluentinc/cp-zookeeper:7.5.0
    container_name: picpay-zookeeper
    ports:
      - "2181:2181"
    environment:
      ZOOKEEPER_CLIENT_PORT: 2181
      ZOOKEEPER_TICK_TIME: 2000

  kafka:
    image: confluentinc/cp-kafka:7.5.0
    container_name: picpay-kafka
    ports:
      - "9092:9092"
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_AUTO_CREATE_TOPICS_ENABLE: 'false'
    depends_on:
      - zookeeper
    healthcheck:
      test: ["CMD", "kafka-topics", "--bootstrap-server", "localhost:9092", "--list"]
      interval: 10s
      timeout: 10s
      retries: 5

  redis:
    image: redis:7
    container_name: picpay-redis
    ports:
      - "6379:6379"
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 10s
      timeout: 5s
      retries: 5

volumes:
  postgres_data:
```

- [ ] **Step 2: 인프라 기동 확인**

```bash
docker-compose up -d
```

Expected: 4개 컨테이너 모두 `healthy` 또는 `running`

- [ ] **Step 3: 각 서비스 연결 확인**

```bash
# PostgreSQL
docker exec picpay-postgres pg_isready -U picpay
# Expected: /var/run/postgresql:5432 - accepting connections

# Redis
docker exec picpay-redis redis-cli ping
# Expected: PONG

# Kafka
docker exec picpay-kafka kafka-topics --bootstrap-server localhost:9092 --list
# Expected: (빈 목록, 에러 없음)
```

- [ ] **Step 4: Commit**

```bash
git add docker-compose.yml
git commit -m "feat: add docker-compose for local infrastructure

- PostgreSQL 16, Kafka 7.5 (Confluent), Redis 7
- 각 서비스 healthcheck 설정"
```

---

## Task 6: DB 스키마 전체 Flyway 마이그레이션 (S4)

**Files:**
- Create: `db/migration/V1__init.sql`
- Modify: `services/payment/src/main/resources/application.yml` (Flyway + DB 설정 추가)
- Modify: 나머지 4개 서비스 `application.yml` 동일 패턴

- [ ] **Step 1: `V1__init.sql` 작성**

`db/migration/V1__init.sql`:

```sql
-- ========================
-- payment 스키마
-- ========================
CREATE SCHEMA IF NOT EXISTS payment;

CREATE TABLE payment.payments (
    id          BIGSERIAL PRIMARY KEY,
    tid         VARCHAR(64)  NOT NULL UNIQUE,
    merchant_id VARCHAR(64)  NOT NULL,
    order_id    VARCHAR(64)  NOT NULL,
    token_id    VARCHAR(64)  NOT NULL,
    amount      BIGINT       NOT NULL,
    method      VARCHAR(20)  NOT NULL,
    status      VARCHAR(20)  NOT NULL DEFAULT 'READY',
    pg_tid      VARCHAR(64),
    idempotency_key VARCHAR(64) UNIQUE,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE TABLE payment.partial_cancellations (
    id               BIGSERIAL PRIMARY KEY,
    payment_id       BIGINT       NOT NULL REFERENCES payment.payments(id),
    cancel_tid       VARCHAR(64)  NOT NULL UNIQUE,
    cancel_amount    BIGINT       NOT NULL,
    remaining_amount BIGINT       NOT NULL,
    reason           VARCHAR(500),
    pg_cancel_tid    VARCHAR(64),
    status           VARCHAR(20)  NOT NULL,
    created_at       TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_partial_cancel_payment ON payment.partial_cancellations(payment_id);

CREATE TABLE payment.outbox_events (
    id             BIGSERIAL PRIMARY KEY,
    aggregate_type VARCHAR(50)  NOT NULL,
    aggregate_id   VARCHAR(64)  NOT NULL,
    event_type     VARCHAR(50)  NOT NULL,
    topic          VARCHAR(100) NOT NULL,
    payload        JSONB        NOT NULL,
    status         VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    retry_count    INT          NOT NULL DEFAULT 0,
    max_retry      INT          NOT NULL DEFAULT 5,
    last_error     VARCHAR(500),
    created_at     TIMESTAMP    NOT NULL DEFAULT NOW(),
    published_at   TIMESTAMP
);
CREATE INDEX idx_outbox_pending ON payment.outbox_events(status, created_at)
    WHERE status IN ('PENDING', 'FAILED');

-- ========================
-- token 스키마
-- ========================
CREATE SCHEMA IF NOT EXISTS token;

CREATE TABLE token.card_tokens (
    id                      BIGSERIAL PRIMARY KEY,
    token_id                VARCHAR(64)  NOT NULL UNIQUE,
    merchant_id             VARCHAR(64)  NOT NULL,
    card_number_enc         TEXT         NOT NULL,
    card_expiry_enc         TEXT         NOT NULL,
    card_last_four          VARCHAR(4)   NOT NULL,
    card_number_deleted_at  TIMESTAMP,
    status                  VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at              TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE TABLE token.easy_pay_methods (
    id          BIGSERIAL PRIMARY KEY,
    method_id   VARCHAR(64)  NOT NULL UNIQUE,
    user_id     VARCHAR(64)  NOT NULL,
    token_id    VARCHAR(64)  NOT NULL,
    method_name VARCHAR(100),
    status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- ========================
-- billing 스키마
-- ========================
CREATE SCHEMA IF NOT EXISTS billing;

CREATE TABLE billing.billing_plans (
    id              BIGSERIAL PRIMARY KEY,
    plan_id         VARCHAR(64)  NOT NULL UNIQUE,
    merchant_id     VARCHAR(64)  NOT NULL,
    token_id        VARCHAR(64)  NOT NULL,
    amount          BIGINT       NOT NULL,
    cycle           VARCHAR(20)  NOT NULL,
    next_billing_at TIMESTAMP    NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    retry_count     INT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE TABLE billing.billing_histories (
    id             BIGSERIAL PRIMARY KEY,
    plan_id        VARCHAR(64)  NOT NULL,
    tid            VARCHAR(64),
    amount         BIGINT       NOT NULL,
    status         VARCHAR(20)  NOT NULL,
    failure_reason VARCHAR(500),
    created_at     TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_billing_history_plan ON billing.billing_histories(plan_id);

CREATE TABLE billing.billing_retry_jobs (
    id            BIGSERIAL PRIMARY KEY,
    plan_id       VARCHAR(64)  NOT NULL,
    retry_count   INT          NOT NULL DEFAULT 0,
    max_retry     INT          NOT NULL DEFAULT 3,
    next_retry_at TIMESTAMP    NOT NULL,
    last_error    VARCHAR(500),
    status        VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_retry_pending ON billing.billing_retry_jobs(status, next_retry_at)
    WHERE status = 'PENDING';

-- ========================
-- merchant 스키마
-- ========================
CREATE SCHEMA IF NOT EXISTS merchant;

CREATE TABLE merchant.merchants (
    id            BIGSERIAL PRIMARY KEY,
    merchant_id   VARCHAR(64)  NOT NULL UNIQUE,
    merchant_name VARCHAR(200) NOT NULL,
    api_key       VARCHAR(128) NOT NULL UNIQUE,
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT NOW()
);

INSERT INTO merchant.merchants (merchant_id, merchant_name, api_key)
VALUES ('mer_001', 'Test Merchant', 'test-api-key-001');

-- ========================
-- notification 스키마
-- ========================
CREATE SCHEMA IF NOT EXISTS notification;

CREATE TABLE notification.processed_events (
    event_id     VARCHAR(64) PRIMARY KEY,
    topic        VARCHAR(100) NOT NULL,
    processed_at TIMESTAMP    NOT NULL DEFAULT NOW()
);
```

- [ ] **Step 2: payment 서비스에 Flyway 의존성 + DB 설정 추가**

`services/payment/build.gradle`에 추가:

```groovy
dependencies {
    // 기존 의존성 유지
    implementation 'org.flywaydb:flyway-core'
    implementation 'org.flywaydb:flyway-database-postgresql'
}
```

`services/payment/src/main/resources/application.yml` 수정:

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

server:
  port: 8081

management:
  endpoints:
    web:
      exposure:
        include: health,info
```

- [ ] **Step 3: Flyway SQL 파일을 각 서비스 classpath에 심볼릭 링크 또는 복사**

Payment 서비스의 resources에 migration 디렉토리 생성:

```bash
mkdir -p services/payment/src/main/resources/db/migration
cp db/migration/V1__init.sql services/payment/src/main/resources/db/migration/
```

> **마이그레이션 전략:** Payment 서비스만 Flyway를 활성화해 전체 V1__init.sql을 실행한다. 나머지 4개 서비스는 Flyway를 비활성화한다. 각 서비스 `application.yml`에 아래를 추가:
>
> ```yaml
> # services/token/src/main/resources/application.yml (billing, notification, gateway 동일)
> spring:
>   application:
>     name: token-service
>   threads:
>     virtual:
>       enabled: true
>   datasource:
>     url: jdbc:postgresql://localhost:5432/picpay?currentSchema=token
>     username: picpay
>     password: picpay
>     driver-class-name: org.postgresql.Driver
>   jpa:
>     hibernate:
>       ddl-auto: validate
>     properties:
>       hibernate:
>         default_schema: token
>   flyway:
>     enabled: false   # Payment 서비스가 전체 스키마 관리
>
> server:
>   port: 8083
> ```
>
> | 서비스 | Flyway | default_schema | port |
> |--------|--------|----------------|------|
> | payment | enabled | payment | 8081 |
> | billing | disabled | billing | 8082 |
> | token | disabled | token | 8083 |
> | notification | disabled | notification | 8084 |
> | gateway | disabled | — | 8080 |

- [ ] **Step 4: 마이그레이션 실행 확인**

```bash
# docker-compose 실행 중인지 확인
docker ps | grep picpay-postgres

# Payment 서비스 단독 기동 (마이그레이션만 확인)
./gradlew :services:payment:bootRun &
sleep 10
# 로그에 "Successfully applied 1 migration" 확인 후 종료
kill %1
```

- [ ] **Step 5: DB 테이블 생성 확인**

```bash
docker exec -it picpay-postgres psql -U picpay -d picpay -c "\dn"
```

Expected:
```
     List of schemas
     Name      |  Owner
--------------+--------
 billing      | picpay
 merchant     | picpay
 notification | picpay
 payment      | picpay
 public       | pg_database_owner
 token        | picpay
```

```bash
docker exec -it picpay-postgres psql -U picpay -d picpay -c "\dt payment.*"
```

Expected: `payments`, `partial_cancellations`, `outbox_events` 테이블 존재

- [ ] **Step 6: Commit**

```bash
git add db/ services/payment/src/main/resources/ services/payment/build.gradle
git commit -m "feat: add Flyway migration V1__init.sql with all 5 schemas

- payment: payments, partial_cancellations, outbox_events
- token: card_tokens, easy_pay_methods
- billing: billing_plans, billing_histories, billing_retry_jobs
- merchant: merchants (seed: mer_001)
- notification: processed_events"
```

---

## Layer 1 완료 체크리스트

- [ ] `./gradlew build` 성공
- [ ] `./gradlew :common:test` 전체 통과
- [ ] `docker-compose up -d` 후 4개 컨테이너 healthy
- [ ] PostgreSQL 5개 스키마 + 전체 테이블 생성 확인
- [ ] git log에 5개 커밋 존재

완료 후 → **Layer 2 (Token Service) 플랜**으로 이동
